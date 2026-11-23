package com.wisp.app.nostr

import com.wisp.app.relay.HttpClientFactory
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies SafeHttp's Tor-mode SSRF checks: when Tor is active, local clearnet
 * DNS resolution must never run (preventing DNS leaks and allowing .onion),
 * while loopback, RFC 1918, link-local, localhost, and .local/.internal hosts
 * remain blocked.
 */
class SafeHttpTorTest {
    @After
    fun tearDown() {
        HttpClientFactory.setTorSocks(HttpClientFactory.TorSocks.Off)
    }

    @Test
    fun allowsOnionAndPublicDomainsUnderTorWithoutDns() {
        HttpClientFactory.setTorSocks(HttpClientFactory.TorSocks.Ready(9050))
        assertTrue(SafeHttp.isAllowedPublicHost("example.com"))
        assertTrue(SafeHttp.isAllowedPublicHost("nonexistent-test-host-xyz.onion"))
        assertTrue(SafeHttp.isAllowedPublicHost("1.1.1.1"))
    }

    @Test
    fun blocksPrivateAndLocalHostsUnderTor() {
        HttpClientFactory.setTorSocks(HttpClientFactory.TorSocks.Pending)
        assertFalse(SafeHttp.isAllowedPublicHost("localhost"))
        assertFalse(SafeHttp.isAllowedPublicHost("sub.localhost"))
        assertFalse(SafeHttp.isAllowedPublicHost("printer.local"))
        assertFalse(SafeHttp.isAllowedPublicHost("metadata.internal"))
        assertFalse(SafeHttp.isAllowedPublicHost("127.0.0.1"))
        assertFalse(SafeHttp.isAllowedPublicHost("10.0.0.1"))
        assertFalse(SafeHttp.isAllowedPublicHost("172.16.0.1"))
        assertFalse(SafeHttp.isAllowedPublicHost("192.168.1.1"))
        assertFalse(SafeHttp.isAllowedPublicHost("169.254.169.254"))
        assertFalse(SafeHttp.isAllowedPublicHost("[::1]"))
        assertFalse(SafeHttp.isAllowedPublicHost("fc00::1"))
        assertFalse(SafeHttp.isAllowedPublicHost("2130706433"))
        assertFalse(SafeHttp.isAllowedPublicHost("0x7f000001"))
    }
}
