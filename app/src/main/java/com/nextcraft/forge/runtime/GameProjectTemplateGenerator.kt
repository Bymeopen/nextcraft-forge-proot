package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.projectSlug
import java.io.File

/**
 * Starter for a libGDX game with one shared `core` module, an Android `app` (built and installed by the regular
 * Launch flow, which expects `:app`) and a `desktop` module whose `dist` task makes a runnable jar for Windows/Linux.
 * Java keeps the starter independent of Kotlin plugin versions; libGDX comes from Maven Central, so the first
 * build needs internet.
 */
internal object GameProjectTemplateGenerator {
    const val GDX_VERSION = "1.13.1"
    private const val MIN_SDK = 31

    /** ABIs whose libGDX natives are packed into the APK. */
    private val ANDROID_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default",
        "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import",
        "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return",
        "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
        "void", "volatile", "while", "true", "false", "null", "_",
    )

    fun generate(destination: File, appName: String) {
        check(!File(destination, "settings.gradle.kts").exists()) { "The folder already contains a Gradle project" }
        destination.mkdirs()
        files(appName).forEach { (path, content) ->
            File(destination, path).apply { parentFile?.mkdirs() }.writeText(content)
        }
    }

    /** Relative path to file content for a game called [appName]. */
    fun files(appName: String): Map<String, String> {
        val rawSlug = projectSlug(appName).replace(Regex("[^a-z0-9]"), "").ifBlank { "game" }
        // A package segment cannot start with a digit ("2048 Game") or be a Java keyword ("New"); both break javac and AGP.
        val slug = if (rawSlug.first().isDigit() || rawSlug in JAVA_KEYWORDS) "game$rawSlug" else rawSlug
        val namespace = "com.forge.$slug"
        val pkgPath = namespace.replace('.', '/')
        return linkedMapOf(
            "settings.gradle.kts" to settings(appName),
            "build.gradle.kts" to rootBuild(),
            "gradle.properties" to "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\n",
            "core/build.gradle.kts" to coreBuild(),
            "core/src/main/java/$pkgPath/ForgeGame.java" to gameJava(namespace, appName),
            "app/build.gradle.kts" to androidBuild(namespace),
            "app/src/main/AndroidManifest.xml" to manifest(),
            "app/src/main/res/values/strings.xml" to
                "<resources><string name=\"app_name\" formatted=\"false\">${xmlEscape(appName)}</string></resources>\n",
            "app/src/main/java/$pkgPath/android/AndroidLauncher.java" to androidLauncher(namespace),
            "desktop/build.gradle.kts" to desktopBuild(namespace),
            "desktop/src/main/java/$pkgPath/desktop/DesktopLauncher.java" to desktopLauncher(namespace, appName),
            ".gitignore" to ".gradle/\nlocal.properties\n**/build/\napp/src/main/jniLibs/\n*.iml\n",
            ".nextcraft/game-project.json" to """{"schemaVersion":1,"engine":"libgdx","gdx":"$GDX_VERSION"}""" + "\n",
            "README.md" to readme(appName),
        )
    }

    private fun settings(appName: String) = """
        pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories { google(); mavenCentral() }
        }
        rootProject.name = ${kotlinString(appName)}
        include(":core", ":app", ":desktop")
    """.trimIndent() + "\n"

    private fun rootBuild() = """
        plugins {
            id("com.android.application") version "${AndroidToolchain.AGP_VERSION}" apply false
        }
    """.trimIndent() + "\n"

    private fun coreBuild() = """
        plugins { `java-library` }

        java {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        dependencies {
            api("com.badlogicgames.gdx:gdx:$GDX_VERSION")
        }
    """.trimIndent() + "\n"

    private fun androidBuild(namespace: String): String {
        val natives = ANDROID_ABIS.joinToString("\n") { abi ->
            "    natives(\"com.badlogicgames.gdx:gdx-platform:$GDX_VERSION:natives-$abi\")"
        }
        val abiList = ANDROID_ABIS.joinToString(", ") { "\"$it\"" }
        return """
            plugins { id("com.android.application") }

            val natives: Configuration by configurations.creating

            android {
                namespace = "$namespace"
                compileSdk {
                    version = release(${AndroidToolchain.COMPILE_SDK}) { minorApiLevel = ${AndroidToolchain.COMPILE_SDK_MINOR} }
                }
                defaultConfig {
                    applicationId = "$namespace"
                    minSdk = $MIN_SDK
                    targetSdk = ${AndroidToolchain.TARGET_SDK}
                    versionCode = 1
                    versionName = "1.0"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            dependencies {
                implementation(project(":core"))
                implementation("com.badlogicgames.gdx:gdx-backend-android:$GDX_VERSION")
            $natives
            }

            // libGDX ships its Android .so files inside "natives" jars; unpack them into src/main/jniLibs/<abi>.
            val copyAndroidNatives by tasks.registering(Sync::class) {
                into(layout.projectDirectory.dir("src/main/jniLibs"))
                listOf($abiList).forEach { abi ->
                    from({ natives.filter { it.name.endsWith("natives-${'$'}abi.jar") }.map { zipTree(it) } }) {
                        include("*.so")
                        into(abi)
                    }
                }
            }
            tasks.named("preBuild") { dependsOn(copyAndroidNatives) }
        """.trimIndent() + "\n"
    }

    private fun manifest() = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-feature android:glEsVersion="0x00020000" android:required="true" />
            <application android:label="@string/app_name" android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen">
                <activity
                    android:name=".android.AndroidLauncher"
                    android:exported="true"
                    android:screenOrientation="sensorLandscape"
                    android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize|screenLayout">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent() + "\n"

    private fun androidLauncher(namespace: String) = """
        package $namespace.android;

        import android.os.Bundle;
        import com.badlogic.gdx.backends.android.AndroidApplication;
        import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
        import $namespace.ForgeGame;

        public class AndroidLauncher extends AndroidApplication {
            @Override
            protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
                config.useImmersiveMode = true;
                initialize(new ForgeGame(), config);
            }
        }
    """.trimIndent() + "\n"

    private fun desktopBuild(namespace: String) = """
        plugins { application }

        java {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        dependencies {
            implementation(project(":core"))
            implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$GDX_VERSION")
            implementation("com.badlogicgames.gdx:gdx-platform:$GDX_VERSION:natives-desktop")
        }

        application { mainClass.set("$namespace.desktop.DesktopLauncher") }

        // One runnable jar for Windows, Linux and macOS (needs Java 17+ on the PC): java -jar game.jar
        tasks.register<Jar>("dist") {
            group = "distribution"
            archiveFileName.set("game.jar")
            destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
            manifest { attributes["Main-Class"] = "$namespace.desktop.DesktopLauncher" }
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            from(sourceSets.main.get().output)
            dependsOn(configurations.runtimeClasspath)
            from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        }
    """.trimIndent() + "\n"

    private fun desktopLauncher(namespace: String, appName: String) = """
        package $namespace.desktop;

        import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
        import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
        import $namespace.ForgeGame;

        public class DesktopLauncher {
            public static void main(String[] args) {
                Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
                config.setTitle(${javaString(appName)});
                config.setWindowedMode(1280, 720);
                config.useVsync(true);
                new Lwjgl3Application(new ForgeGame(), config);
            }
        }
    """.trimIndent() + "\n"

    /** A ball that bounces around the screen; a tap or click changes its colour. No asset files needed. */
    private fun gameJava(namespace: String, appName: String) = """
        package $namespace;

        import com.badlogic.gdx.ApplicationAdapter;
        import com.badlogic.gdx.Gdx;
        import com.badlogic.gdx.graphics.Color;
        import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
        import com.badlogic.gdx.utils.ScreenUtils;

        /** ${appName.replace("*/", "* /").replace("\\", "/").replace(Regex("\\s+"), " ")}: shared game code for Android and desktop. */
        public class ForgeGame extends ApplicationAdapter {
            private ShapeRenderer shapes;
            private float x = 200f, y = 200f, vx = 320f, vy = 260f;
            private final float radius = 48f;
            private final Color color = new Color(0.4f, 0.4f, 1f, 1f);

            @Override
            public void create() {
                shapes = new ShapeRenderer();
            }

            @Override
            public void render() {
                float dt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
                float w = Gdx.graphics.getWidth();
                float h = Gdx.graphics.getHeight();
                x += vx * dt;
                y += vy * dt;
                if (x < radius || x > w - radius) { vx = -vx; x = Math.max(radius, Math.min(x, w - radius)); }
                if (y < radius || y > h - radius) { vy = -vy; y = Math.max(radius, Math.min(y, h - radius)); }
                if (Gdx.input.justTouched()) color.set((float) Math.random(), (float) Math.random(), (float) Math.random(), 1f);

                ScreenUtils.clear(0.05f, 0.05f, 0.08f, 1f);
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                shapes.setColor(color);
                shapes.circle(x, y, radius);
                shapes.end();
            }

            @Override
            public void dispose() {
                shapes.dispose();
            }
        }
    """.trimIndent() + "\n"

    private fun readme(appName: String) = """
        # $appName

        A libGDX $GDX_VERSION game: shared code in `core/`, Android in `app/`, desktop (Windows/Linux) in `desktop/`.

        - **Android:** use Launch (build and install the APK), or `gradle :app:assembleDebug`.
        - **PC:** `gradle :desktop:dist` makes `dist/game.jar`; run it on a PC with Java 17+: `java -jar game.jar`.
          The desktop build cannot run on the phone (it needs a desktop OpenGL window).

        The first build downloads libGDX from Maven Central, so it needs internet.
    """.trimIndent() + "\n"

    private fun kotlinString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
    private fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\""
    private fun xmlEscape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("'", "\\'").replace("\"", "\\\"")
}
