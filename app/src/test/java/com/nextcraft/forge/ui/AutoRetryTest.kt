package com.nextcraft.forge.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRetryTest {
    @Test
    fun passingProviderFailuresAreRetried() {
        assertTrue(isTransientAgentFailure("API error (attempt 1): request failed: stream idle timeout (5m0s): context deadline exceeded"))
        assertTrue(isTransientAgentFailure("Google's Antigravity server returned an error. Wait a moment and try again"))
        assertTrue(isTransientAgentFailure("overloaded_error"))
        assertTrue(isTransientAgentFailure("There was a network issue connecting to the server, please try again."))
    }

    @Test
    fun realProblemsAreNotRetried() {
        assertFalse(isTransientAgentFailure("Antigravity needs Google sign-in. Open Settings → Coding agent."))
        assertFalse(isTransientAgentFailure("Your Antigravity account is out of credits."))
        assertFalse(isTransientAgentFailure("Stopped by user"))
    }
}
