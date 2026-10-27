package com.nextcraft.forge.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentSelfUpdateTest {
    @Test fun scriptIsBuiltOnlyForASafeComponent() {
        val script = SilentSelfUpdate.detachedScript("com.nextcraft.forge/com.nextcraft.forge.MainActivity")
        assertNotNull(script)
        assertTrue(script!!.contains("pm install -r -S \"\$size\" < ${SilentSelfUpdate.STAGING_APK}"))
        assertFalse(script.contains("pm install -r -d"))
        assertTrue(script.contains("chmod 700 ${SilentSelfUpdate.STAGING_DIR}"))
        assertTrue(script.contains("am start --user 0 -n com.nextcraft.forge/com.nextcraft.forge.MainActivity"))
        assertTrue(script.endsWith("&"))
        val debugDowngradeScript = SilentSelfUpdate.detachedScript("com.nextcraft.forge/com.nextcraft.forge.MainActivity", allowDowngrade = true)
        assertTrue(debugDowngradeScript!!.contains("pm install -r -d -S \"\$size\" < ${SilentSelfUpdate.STAGING_APK}"))
        assertNull(SilentSelfUpdate.detachedScript("x/y; rm -rf /"))
        assertNull(SilentSelfUpdate.detachedScript("com.a/b' ; id #"))
    }

    @Test fun logFailureIsReadFromTheLastExitCode() {
        assertFalse(SilentSelfUpdate.logShowsFailure(""))
        assertFalse(SilentSelfUpdate.logShowsFailure("Success\nrc=0\n"))
        assertTrue(SilentSelfUpdate.logShowsFailure("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\nrc=1\n"))
    }
}
