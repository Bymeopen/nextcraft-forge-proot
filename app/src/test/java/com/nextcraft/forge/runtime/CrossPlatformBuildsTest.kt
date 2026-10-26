package com.nextcraft.forge.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrossPlatformBuildsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun project(vararg names: String): File = tmp.newFolder().also { dir ->
        names.forEach { name ->
            val f = File(dir, name)
            if (name.endsWith("/")) f.mkdirs() else f.writeText("x")
        }
    }

    @Test
    fun anEmptyFolderHasNoCrossTargets() {
        assertTrue(detectCrossTargets(project()).isEmpty())
    }

    @Test
    fun goProjectsBuildForWindowsAndBothLinuxArchitectures() {
        val targets = detectCrossTargets(project("go.mod"))
        assertEquals(listOf("go:windows-amd64", "go:linux-amd64", "go:linux-arm64"), targets.map { it.id })
        assertTrue(targets.all { it.onDevice })
        assertTrue(targets.first().command.startsWith("GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build"))
    }

    @Test
    fun cargoCmakeAndDotnetProjectsGetTheirTargets() {
        assertEquals(setOf("cargo:windows-x64", "cargo:linux-x64"), detectCrossTargets(project("Cargo.toml")).map { it.id }.toSet())
        assertEquals(setOf("cmake:windows-x64", "cmake:linux-x64"), detectCrossTargets(project("CMakeLists.txt")).map { it.id }.toSet())
        assertEquals(
            setOf("dotnet:win-x64", "dotnet:linux-x64", "dotnet:linux-arm64"),
            detectCrossTargets(project("App.csproj")).map { it.id }.toSet(),
        )
    }

    @Test
    fun iosIsNeverBuiltOnTheDevice() {
        val xcode = detectCrossTargets(project("App.xcodeproj/"))
        assertEquals(listOf("ios:ci"), xcode.map { it.id })
        assertFalse(xcode.single().onDevice)
        // A Flutter app with an ios/ folder: the Linux build is on device, iOS is CI only.
        val flutter = detectCrossTargets(project("pubspec.yaml", "ios/"))
        assertEquals(setOf("ios:ci", "flutter:linux"), flutter.map { it.id }.toSet())
        assertEquals(false, flutter.first { it.id == "ios:ci" }.onDevice)
    }

    @Test
    fun theBuildMatrixOffersOnlyTheOnDeviceCrossTargets() {
        val ids = detectBuildTargets(project("go.mod", "App.xcodeproj/")).map { it.id }
        assertTrue("go:windows-amd64" in ids)
        assertFalse("ios:ci" in ids)
    }

    @Test
    fun toolCheckNamesTheAptPackageAndExits127() {
        val cmd = withToolCheck("echo built", listOf("no-such-tool-xyz" to "some-package"))
        val process = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(127, process.waitFor())
        assertTrue(output, "apt-get install -y some-package" in output)
        assertFalse("built" in output)
    }

    @Test
    fun toolCheckPassesWhenTheToolExists() {
        val process = ProcessBuilder("sh", "-c", withToolCheck("echo built", listOf("sh" to "dash"))).redirectErrorStream(true).start()
        assertEquals("built", process.inputStream.bufferedReader().readText().trim())
        assertEquals(0, process.waitFor())
    }

    @Test
    fun installCommandListsEachPackageOnceAndSkipsCiOnlyTargets() {
        val targets = detectCrossTargets(project("CMakeLists.txt", "App.xcodeproj/"))
        val command = crossToolchainInstallCommand(targets)!!
        assertEquals(1, Regex("gcc-mingw-w64-x86-64").findAll(command).count())
        assertTrue("cmake" in command)
        assertNull(crossToolchainInstallCommand(detectCrossTargets(project("App.xcodeproj/"))))
    }

    @Test
    fun iosWorkflowRunsOnMacosWithoutSigning() {
        val yaml = iosWorkflowYaml("App.xcworkspace", "App")
        assertTrue("runs-on: macos-latest" in yaml)
        assertTrue("-workspace 'App.xcworkspace'" in yaml)
        assertTrue("CODE_SIGNING_ALLOWED=NO" in yaml)
        assertTrue("-project 'App.xcodeproj'" in iosWorkflowYaml("App.xcodeproj", "App"))
    }
}
