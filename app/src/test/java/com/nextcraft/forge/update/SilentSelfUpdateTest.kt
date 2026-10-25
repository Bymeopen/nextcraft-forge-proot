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
        assertTrue(script!!.contains("pm install -r -d ${SilentSelfUpdate.STAGING_APK}"))
        assertTrue(script.contains("am start --user 0 -n com.nextcraft.forge/com.nextcraft.forge.MainActivity"))
        assertTrue(script.endsWith("&"))
        assertNull(SilentSelfUpdate.detachedScript("x/y; rm -rf /"))
        assertNull(SilentSelfUpdate.detachedScript("com.a/b' ; id #"))
    }

    @Test fun logFailureIsReadFromTheLastExitCode() {
        assertFalse(SilentSelfUpdate.logShowsFailure(""))
        assertFalse(SilentSelfUpdate.logShowsFailure("Success\nrc=0\n"))
        assertTrue(SilentSelfUpdate.logShowsFailure("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\nrc=1\n"))
    }
}
