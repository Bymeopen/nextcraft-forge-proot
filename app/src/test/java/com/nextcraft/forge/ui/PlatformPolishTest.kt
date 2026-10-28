package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 13+ polish that is easy to lose in a manifest or icon edit. */
class PlatformPolishTest {
    private val main = File("src/main")

    @Test
    fun manifest_optsIntoPredictiveBack() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(
            "enableOnBackInvokedCallback=true is required for the system back preview (Back is handled by BackHandler only)",
            """android:enableOnBackInvokedCallback="true"""" in manifest,
        )
    }

    @Test
    fun launcherIcons_haveMonochromeLayer() {
        listOf("ic_launcher", "ic_launcher_round").forEach { name ->
            val xml = File(main, "res/mipmap-anydpi/$name.xml").readText()
            assertTrue("$name needs <monochrome> for themed icons", "<monochrome" in xml)
        }
    }
}
