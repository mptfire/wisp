package com.wisp.app.nostr

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SafeHttpHardeningTest {

    @Test
    fun `isAllowedPublicHost rejects loopback, private, link-local, CGNAT, and malformed hosts`() {
        val rejected = listOf(
            "",
            "localhost",
            "LOCALHOST.",
            "sub.localhost",
            "printer.local",
            "meta.internal",
            "127.0.0.1",
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "100.127.255.254",
            "169.254.169.254",
            "172.16.0.1",
            "192.168.1.1",
            "224.0.0.1",
            "::1",
            "fe80::1",
            "fc00::1",
            "fd12:3456::1",
            "user@example.com",
            "example.com/path",
            "example.com?q=1"
        )
        for (host in rejected) {
            assertFalse("Expected $host to be rejected", SafeHttp.isAllowedPublicHost(host))
        }
    }

    @Test
    fun `isPublicUnicast accepts public IPv4 and global unicast IPv6`() {
        assertTrue(SafeHttp.isPublicUnicast(InetAddress.getByName("1.1.1.1")))
        assertTrue(SafeHttp.isPublicUnicast(InetAddress.getByName("8.8.8.8")))
        assertTrue(SafeHttp.isPublicUnicast(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun `validateHttpsUrl rejects cleartext http, credentials, and private targets`() {
        assertNull(SafeHttp.validateHttpsUrl("http://1.1.1.1/callback"))
        assertNull(SafeHttp.validateHttpsUrl("https://user:pass@1.1.1.1/callback"))
        assertNull(SafeHttp.validateHttpsUrl("https://127.0.0.1:8080/callback"))
        assertNull(SafeHttp.validateHttpsUrl("https://169.254.169.254/latest/meta-data/"))
        assertNull(SafeHttp.validateHttpsUrl("javascript:alert(1)"))
        assertEquals("https://1.1.1.1/cb", SafeHttp.validateHttpsUrl("https://1.1.1.1/cb"))
    }

    @Test
    fun `readUtf8Capped allows chunked responses with unknown contentLength (-1) and enforces byte cap`() {
        val chunkedBody = object : ResponseBody() {
            private val data = """{"allowsNostr":true,"callback":"https://1.1.1.1/cb"}""".toByteArray()
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength(): Long = -1L // chunked / HTTP/2
            override fun source(): BufferedSource = Buffer().write(data)
        }
        val text = SafeHttp.readUtf8Capped(chunkedBody, 1024)
        assertNotNull("Chunked response with contentLength == -1 must succeed", text)
        assertTrue(text!!.contains("allowsNostr"))

        val oversizedChunked = object : ResponseBody() {
            private val data = ByteArray(2048) { 'a'.code.toByte() }
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = Buffer().write(data)
        }
        assertNull("Oversized streaming body must be refused", SafeHttp.readUtf8Capped(oversizedChunked, 1024))

        val declaredOversized = "small".toResponseBody("text/plain".toMediaType())
        assertNull(SafeHttp.readUtf8Capped(declaredOversized, 2))
    }

    @Test
    fun `Bolt11 rejects non-ln HRP`() {
        // Valid bech32 checksum for non-lightning prefix should fail decode
        assertNull(Bolt11.decode("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
    }
}
