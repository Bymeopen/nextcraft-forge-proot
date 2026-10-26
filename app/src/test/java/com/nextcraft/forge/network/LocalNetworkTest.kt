package com.nextcraft.forge.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain HTTP must stay limited to loopback; API keys must never go out over cleartext. */
class LocalNetworkTest {
    @Test
    fun httpsAlwaysAllowed() {
        assertTrue(LocalNetwork.isPlainHttpAllowed("https://api.anthropic.com/v1/messages"))
    }

    @Test
    fun httpOnlyOnLoopback() {
        assertTrue(LocalNetwork.isPlainHttpAllowed("http://localhost:8080/v1"))
        assertTrue(LocalNetwork.isPlainHttpAllowed("http://127.0.0.1:3000"))
        assertFalse(LocalNetwork.isPlainHttpAllowed("http://192.168.1.5:8080"))
        assertFalse(LocalNetwork.isPlainHttpAllowed("http://example.com"))
        assertFalse(LocalNetwork.isPlainHttpAllowed("ftp://localhost"))
        assertFalse(LocalNetwork.isPlainHttpAllowed("not a url"))
    }

    @Test
    fun userInfoTrickDoesNotMakeRemoteHostLoopback() {
        assertFalse(LocalNetwork.isPlainHttpAllowed("http://localhost@evil.example/"))
        assertFalse(LocalNetwork.isPlainHttpAllowed("http://127.0.0.1.evil.example/"))
    }

    @Test
    fun localHostClassification() {
        assertTrue(LocalNetwork.isLocalHost("10.0.0.2"))
        assertTrue(LocalNetwork.isLocalHost("172.16.0.1"))
        assertFalse(LocalNetwork.isLocalHost("172.32.0.1"))
        assertTrue(LocalNetwork.isLocalHost("100.100.1.1"))
        assertTrue(LocalNetwork.isLocalHost("fe80::1%wlan0"))
        assertTrue(LocalNetwork.isLocalHost("::ffff:192.168.0.9"))
        assertFalse(LocalNetwork.isLocalHost("8.8.8.8"))
        assertFalse(LocalNetwork.isLocalHost(""))
    }

    @Test
    fun portProbeRejectsInvalidPorts() {
        assertFalse(LocalNetwork.isLocalPortOpen(0))
        assertFalse(LocalNetwork.isLocalPortOpen(70000))
        assertEquals(emptyList<Int>(), LocalNetwork.scanOpenLocalPorts(listOf(0, -1)))
    }
}
