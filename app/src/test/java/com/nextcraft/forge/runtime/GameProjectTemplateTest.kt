package com.nextcraft.forge.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProjectTemplateTest {
    private val files = GameProjectTemplateGenerator.files("Space Run")

    @Test
    fun hasAndroidAppDesktopAndCoreModules() {
        assertTrue(files.getValue("settings.gradle.kts").contains("include(\":core\", \":app\", \":desktop\")"))
        assertTrue(files.keys.any { it.startsWith("core/src/main/java/") && it.endsWith("ForgeGame.java") })
        assertTrue(files.keys.any { it.endsWith("android/AndroidLauncher.java") })
        assertTrue(files.keys.any { it.endsWith("desktop/DesktopLauncher.java") })
        assertTrue(files.containsKey(".nextcraft/game-project.json"))
    }

    @Test
    fun androidBuildUnpacksNativesPerAbi() {
        val gradle = files.getValue("app/build.gradle.kts")
        // The generated Gradle script must keep its own string template, not a Kotlin-side substitution.
        assertTrue(gradle.contains("natives-\$abi.jar"))
        assertTrue(gradle.contains("natives-arm64-v8a"))
        assertTrue(gradle.contains("dependsOn(copyAndroidNatives)"))
    }

    @Test
    fun noUnresolvedTemplateText() {
        files.values.forEach { content ->
            assertFalse(content, content.contains("\${'"))
            assertFalse(content, content.contains("\$GDX_VERSION") || content.contains("\$namespace"))
        }
    }
}
