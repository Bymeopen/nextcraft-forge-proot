package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.projectSlug
import java.io.File
import java.nio.file.Files

object AndroidProjectTemplateGenerator {
    const val MIN_SDK = 31
    const val CORE_KTX_VERSION = "1.19.1"
    const val ACTIVITY_COMPOSE_VERSION = "1.13.0"
    const val APPCOMPAT_VERSION = "1.8.0"
    const val MATERIAL_VERSION = "1.14.0"

    private const val COMPOSE_COMPILER_PLUGIN_ID = "org.jetbrains.kotlin.plugin.compose"

    private val RESERVED_JAVA_WORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null",
    )

    fun packageName(name: String): String {
        val candidate = projectSlug(name).replace('-', '_').take(40).ifBlank { "app" }
        val safeIdent = if (candidate.first().isLetter() && candidate !in RESERVED_JAVA_WORDS) {
            candidate
        } else {
            "app_$candidate"
        }
        return "com.nextcraft.$safeIdent"
    }

    fun generate(destination: File, appName: String, template: AndroidTemplate) {
        val parentFolder = requireNotNull(destination.parentFile) { "Destination must have a parent directory" }
        parentFolder.mkdirs()
        val tempDir = File(parentFolder, ".${destination.name}.android-template")
        tempDir.deleteRecursively()
        check(tempDir.mkdirs()) { "Could not prepare the Android project" }

        try {
            val appPkg = packageName(appName)
            emitSharedFiles(tempDir, appName, appPkg, template)
            when (template) {
                AndroidTemplate.COMPOSE -> emitComposeVariant(tempDir, appPkg)
                AndroidTemplate.XML -> emitXmlVariant(tempDir, appPkg)
            }
            if (destination.exists()) {
                check(destination.listFiles().isNullOrEmpty()) { "Project folder is not empty" }
                check(destination.delete()) { "Could not replace the empty project folder" }
            }
            check(tempDir.renameTo(destination)) { "Could not finish creating the Android project" }
        } catch (err: Throwable) {
            tempDir.deleteRecursively()
            throw err
        }
    }

    private fun emitSharedFiles(
        projectRoot: File,
        displayTitle: String,
        pkgId: String,
        variant: AndroidTemplate,
    ) {
        val useCompose = variant == AndroidTemplate.COMPOSE

        emitFile(
            projectRoot,
            "settings.gradle.kts",
            listOf(
                "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }",
                "dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }",
                "rootProject.name = ${quoteKotlinLiteral(displayTitle)}",
                "include(\":app\")",
            ).joinToString("\n"),
        )

        val rootPlugins = buildList {
            add("plugins {")
            add("    id(\"com.android.application\") version \"${AndroidToolchain.AGP_VERSION}\" apply false")
            add("    id(\"org.jetbrains.kotlin.android\") version \"${AndroidToolchain.KOTLIN_VERSION}\" apply false")
            if (useCompose) {
                add("    id(\"$COMPOSE_COMPILER_PLUGIN_ID\") version \"${AndroidToolchain.KOTLIN_VERSION}\" apply false")
            }
            add("}")
        }.joinToString("\n")
        emitFile(projectRoot, "build.gradle.kts", rootPlugins)

        emitFile(
            projectRoot,
            "gradle.properties",
            "android.useAndroidX=true\nkotlin.code.style=official\nandroid.nonTransitiveRClass=true",
        )

        val variantDeps = if (useCompose) {
            listOf(
                "implementation(platform(\"androidx.compose:compose-bom:${AndroidToolchain.COMPOSE_BOM_VERSION}\"))",
                "implementation(\"androidx.activity:activity-compose:$ACTIVITY_COMPOSE_VERSION\")",
                "implementation(\"androidx.compose.ui:ui\")",
                "implementation(\"androidx.compose.foundation:foundation\")",
                "implementation(\"androidx.compose.material3:material3\")",
            )
        } else {
            listOf(
                "implementation(\"androidx.appcompat:appcompat:$APPCOMPAT_VERSION\")",
                "implementation(\"com.google.android.material:material:$MATERIAL_VERSION\")",
            )
        }

        val appGradleLines = buildList {
            add("plugins {")
            add("    id(\"com.android.application\")")
            if (useCompose) add("    id(\"$COMPOSE_COMPILER_PLUGIN_ID\")")
            add("}")
            add("")
            add("android {")
            add("    namespace = \"$pkgId\"")
            add("    compileSdk {")
            add("        version = release(${AndroidToolchain.COMPILE_SDK}) { minorApiLevel = ${AndroidToolchain.COMPILE_SDK_MINOR} }")
            add("    }")
            add("")
            add("    defaultConfig {")
            add("        applicationId = \"$pkgId\"")
            add("        minSdk = $MIN_SDK")
            add("        targetSdk = ${AndroidToolchain.TARGET_SDK}")
            add("        versionCode = 1")
            add("        versionName = \"1.0\"")
            add("    }")
            add("")
            add("    compileOptions {")
            add("        sourceCompatibility = JavaVersion.VERSION_${AndroidToolchain.JAVA_TARGET}")
            add("        targetCompatibility = JavaVersion.VERSION_${AndroidToolchain.JAVA_TARGET}")
            add("    }")
            add("    buildFeatures { compose = $useCompose }")
            add("}")
            add("")
            add("dependencies {")
            add("    implementation(\"androidx.core:core-ktx:$CORE_KTX_VERSION\")")
            for (dep in variantDeps) {
                add("    $dep")
            }
            add("}")
        }.joinToString("\n")
        emitFile(projectRoot, "app/build.gradle.kts", appGradleLines)

        emitFile(projectRoot, ".gitignore", ".gradle/\nlocal.properties\n**/build/\n*.iml")
        emitFile(
            projectRoot,
            ".nextcraft/android-project.json",
            "{\"schemaVersion\":1,\"template\":\"${variant.name}\",\"packageName\":\"$pkgId\"}",
        )
        emitFile(
            projectRoot,
            "app/src/main/res/values/strings.xml",
            "<resources><string name=\"app_name\" formatted=\"false\">${escapeAaptString(displayTitle)}</string></resources>",
        )
        emitFile(projectRoot, "app/src/main/AndroidManifest.xml", buildLauncherManifestXml())
    }

    private fun buildLauncherManifestXml(): String = listOf(
        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">",
        "    <application android:theme=\"@style/Theme.NextCraftApp\" android:label=\"@string/app_name\">",
        "        <activity android:name=\".MainActivity\" android:exported=\"true\">",
        "            <intent-filter>",
        "                <action android:name=\"android.intent.action.MAIN\" />",
        "                <category android:name=\"android.intent.category.LAUNCHER\" />",
        "            </intent-filter>",
        "        </activity>",
        "    </application>",
        "</manifest>",
    ).joinToString("\n")

    private fun emitComposeVariant(projectRoot: File, pkgId: String) {
        val pkgDir = pkgId.replace('.', '/')
        val lightThemeXml = listOf(
            "<resources>",
            "    <style name=\"Theme.NextCraftApp\" parent=\"android:style/Theme.Material.Light.NoActionBar\">",
            "        <item name=\"android:fontFamily\">sans</item>",
            "        <item name=\"android:colorAccent\">#6750A4</item>",
            "        <item name=\"android:statusBarColor\">#FFFBFE</item>",
            "        <item name=\"android:navigationBarColor\">#FFFBFE</item>",
            "        <item name=\"android:windowLightStatusBar\">true</item>",
            "        <item name=\"android:windowLightNavigationBar\">true</item>",
            "    </style>",
            "</resources>",
        ).joinToString("\n")
        emitFile(projectRoot, "app/src/main/res/values/themes.xml", lightThemeXml)

        val activityKt = listOf(
            "package $pkgId",
            "",
            "import android.os.Bundle",
            "import androidx.activity.ComponentActivity",
            "import androidx.activity.compose.setContent",
            "import androidx.compose.foundation.layout.Arrangement",
            "import androidx.compose.foundation.layout.Column",
            "import androidx.compose.foundation.layout.fillMaxSize",
            "import androidx.compose.material3.MaterialTheme",
            "import androidx.compose.material3.Text",
            "import androidx.compose.runtime.Composable",
            "import androidx.compose.ui.Alignment",
            "import androidx.compose.ui.Modifier",
            "",
            "class MainActivity : ComponentActivity() {",
            "    override fun onCreate(savedInstanceState: Bundle?) {",
            "        super.onCreate(savedInstanceState)",
            "        setContent { MaterialTheme { Welcome() } }",
            "    }",
            "}",
            "",
            "@Composable",
            "private fun Welcome() {",
            "    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {",
            "        Text(\"Built with NextCraft Forge\")",
            "    }",
            "}",
        ).joinToString("\n")
        emitFile(projectRoot, "app/src/main/java/$pkgDir/MainActivity.kt", activityKt)
    }

    private fun emitXmlVariant(projectRoot: File, pkgId: String) {
        val pkgDir = pkgId.replace('.', '/')
        emitFile(projectRoot, "app/src/main/res/values/themes.xml", buildDayNightThemeXml("#6750A4", true))
        emitFile(projectRoot, "app/src/main/res/values-night/themes.xml", buildDayNightThemeXml("#D0BCFF", false))

        val layoutXml = listOf(
            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:gravity=\"center\" android:orientation=\"vertical\">",
            "    <TextView android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"Built with NextCraft Forge\" android:textSize=\"22sp\" />",
            "</LinearLayout>",
        ).joinToString("\n")
        emitFile(projectRoot, "app/src/main/res/layout/activity_main.xml", layoutXml)

        val activityKt = listOf(
            "package $pkgId",
            "",
            "import android.os.Bundle",
            "import androidx.appcompat.app.AppCompatActivity",
            "",
            "class MainActivity : AppCompatActivity() {",
            "    override fun onCreate(savedInstanceState: Bundle?) {",
            "        super.onCreate(savedInstanceState)",
            "        setContentView(R.layout.activity_main)",
            "    }",
            "}",
        ).joinToString("\n")
        emitFile(projectRoot, "app/src/main/java/$pkgDir/MainActivity.kt", activityKt)
    }

    private fun buildDayNightThemeXml(primaryHex: String, lightBars: Boolean): String = listOf(
        "<resources>",
        "    <style name=\"Theme.NextCraftApp\" parent=\"Theme.MaterialComponents.DayNight.NoActionBar\">",
        "        <item name=\"colorPrimary\">$primaryHex</item>",
        "        <item name=\"android:statusBarColor\">?android:colorBackground</item>",
        "        <item name=\"android:navigationBarColor\">?android:colorBackground</item>",
        "        <item name=\"android:windowLightStatusBar\">$lightBars</item>",
        "        <item name=\"android:windowLightNavigationBar\">$lightBars</item>",
        "    </style>",
        "</resources>",
    ).joinToString("\n")

    private fun emitFile(rootDir: File, relPath: String, body: String) {
        val canonicalBase = rootDir.canonicalFile.toPath()
        val dest = File(rootDir, relPath)
        dest.parentFile?.mkdirs()
        require(!Files.isSymbolicLink(dest.toPath())) { "Refusing to write template through symlink" }
        require(dest.canonicalFile.toPath().startsWith(canonicalBase)) { "Template path escapes root" }
        dest.writeText(body.trimIndent().trim() + "\n")
    }

    private fun quoteKotlinLiteral(raw: String): String {
        val sb = StringBuilder(raw.length + 4)
        sb.append('"')
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '$' -> sb.append("\\$")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> Unit
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun escapeAaptString(raw: String): String {
        val trimmed = raw.trim()
        val sb = StringBuilder(trimmed.length + 8)
        for (ch in trimmed) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\n' -> sb.append("\\n")
                '\r' -> Unit
                else -> sb.append(ch)
            }
        }
        val escaped = sb.toString()
        return if (escaped.startsWith("@") || escaped.startsWith("?")) "\\$escaped" else escaped
    }
}
