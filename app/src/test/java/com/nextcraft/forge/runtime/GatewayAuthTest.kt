package com.nextcraft.forge.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The loopback gateways accept only their per-session token, never the real provider key (A8). */
class GatewayAuthTest {
    private val token = "forge-local-1234"

    @Test
    fun sessionTokenIsAcceptedAsApiKeyOrBearer() {
        assertTrue(presentsSessionToken(mapOf("x-api-key" to token), token))
        assertTrue(presentsSessionToken(mapOf("authorization" to "Bearer $token"), token))
        assertTrue(presentsSessionToken(mapOf("authorization" to "bearer $token "), token))
    }

    @Test
    fun anythingElseIsRejected() {
        assertFalse(presentsSessionToken(emptyMap(), token))
        assertFalse(presentsSessionToken(mapOf("authorization" to "Bearer "), token))
        assertFalse(presentsSessionToken(mapOf("x-api-key" to "sk-real-provider-key"), token))
        assertFalse(presentsSessionToken(mapOf("x-api-key" to "$token-x"), token))
        // x-api-key wins over authorization, so a wrong api key is not rescued by a correct bearer.
        assertFalse(presentsSessionToken(mapOf("x-api-key" to "nope", "authorization" to "Bearer $token"), token))
    }
}
