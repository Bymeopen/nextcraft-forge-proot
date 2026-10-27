package com.nextcraft.forge.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdaterManifestTest {
    private val pkg = "com.nextcraft.forge"

    private fun manifest(extra: String = "") = JSONObject(
        """{"packageName":"$pkg","versionCode":42,"versionName":"1.4.2","notes":"n",
            "artifacts":{"release":{"url":"https://x/r.apk","sha256":"AB","sizeBytes":10},
                         "debug":{"url":"https://x/d.apk","sha256":"cd","sizeBytes":20}}$extra}""",
    )

    @Test
    fun picksTheArtifactOfTheVariant() {
        val info = AppUpdater.parseManifest(manifest(), pkg, currentVersionCode = 41, variant = "debug")
        assertNotNull(info)
        assertEquals("https://x/d.apk", info!!.apkUrl)
        assertEquals("cd", info.sha256)
        assertEquals(20L, info.sizeBytes)
        assertEquals(42L, info.versionCode)
        assertEquals("1.4.2", info.versionName)
        assertEquals("ab", AppUpdater.parseManifest(manifest(), pkg, 41, "release")!!.sha256)
    }

    @Test
    fun unknownVariantNeverFallsBackToTheRootEntry() {
        // A14: a root-level url must not be handed to a variant the manifest does not list.
        val root = JSONObject("""{"packageName":"$pkg","versionCode":42,"url":"https://x/root.apk","sha256":"ab"}""")
        assertNull(AppUpdater.parseManifest(root, pkg, 41, "debug"))
        assertNull(AppUpdater.parseManifest(manifest(), pkg, 41, "beta"))
    }

    @Test
    fun topLevelVariantObjectIsStillAccepted() {
        val legacy = JSONObject("""{"versionCode":42,"debug":{"apkUrl":"https://x/d.apk","sha256":"ab"}}""")
        assertEquals("https://x/d.apk", AppUpdater.parseManifest(legacy, pkg, 41, "debug")!!.apkUrl)
    }

    @Test
    fun rejectsOtherPackagesOldVersionsAndPlainHttp() {
        assertNull(AppUpdater.parseManifest(manifest(), "com.other", 41, "debug"))
        assertNull(AppUpdater.parseManifest(manifest(), pkg, 42, "debug"))
        val http = JSONObject("""{"versionCode":42,"artifacts":{"debug":{"url":"http://x/d.apk"}}}""")
        assertNull(AppUpdater.parseManifest(http, pkg, 41, "debug"))
    }

    @Test
    fun aManifestForTheProotPackageIsOnlyOfferedToThatPackage() {
        val proot = "com.nextcraft.forge.proot"
        val manifest = JSONObject(
            """{"packageName":"$proot","versionCode":2047,"versionName":"1.0.47-forge",
                "artifacts":{"debug":{"url":"https://x/p.apk","sha256":"ef","sizeBytes":30}}}""",
        )
        assertNotNull(AppUpdater.parseManifest(manifest, proot, currentVersionCode = 1200, variant = "debug"))
        // The main app reading the PRoot manifest, or the reverse, must not be offered an APK it would have to reject.
        assertNull(AppUpdater.parseManifest(manifest, pkg, currentVersionCode = 1, variant = "debug"))
        assertNull(AppUpdater.parseManifest(manifest(), proot, currentVersionCode = 1, variant = "debug"))
    }
}
