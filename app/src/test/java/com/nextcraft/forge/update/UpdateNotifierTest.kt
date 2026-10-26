package com.nextcraft.forge.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotifierTest {
    @Test
    fun aVersionIsAnnouncedOnlyOnce() {
        assertTrue(UpdateNotifier.shouldNotify(availableCode = 1044, lastNotifiedCode = 0))
        assertTrue(UpdateNotifier.shouldNotify(availableCode = 1044, lastNotifiedCode = 1043))
        assertFalse(UpdateNotifier.shouldNotify(availableCode = 1044, lastNotifiedCode = 1044))
        assertFalse(UpdateNotifier.shouldNotify(availableCode = 1043, lastNotifiedCode = 1044))
    }
}
