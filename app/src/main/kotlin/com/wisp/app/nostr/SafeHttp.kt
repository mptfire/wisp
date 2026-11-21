package com.wisp.app.nostr

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * Shared SSRF, redirect, and bounded-body guards for outbound HTTP requests
 * driven by untrusted Nostr metadata (LNURL / lud16 / callbacks, NIP-05,
 * NIP-11, DM encrypted media, media downloads).
 */
object SafeHttp {
    const val DEFAULT_MAX_JSON_BYTES = 262_144
    const val MAX_REDIRECTS = 5

    /**
     * True iff [domain] is a valid public hostname resolving exclusively to
     * public unicast IP addresses (rejects localhost, *.localhost, *.local,
     * *.internal, loopback, RFC 1918, link-local / cloud metadata, CGNAT
     * 100.64.0.0/10, multicast, unspecified, and non-global / ULA IPv6).
     */
    fun isAllowedPublicHost(domain: String): Boolean {
        val host = domain.trim().lowercase().removeSuffix(".")
        if (host.isEmpty() || host.length > 253) return false
        if (host.any { it.isWhitespace() || it in setOf('@', '/', '\\', '?', '#') }) return false
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal")
        ) return false
        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (_: Exception) {
            return false
        }
        if (addresses.isEmpty()) return false
        return addresses.all { isPublicUnicast(it) }
    }

    internal fun isPublicUnicast(addr: InetAddress): Boolean {
        val a = addr.address
        return when (a.size) {
            4 -> {
                val b0 = a[0].toInt() and 0xFF
                val b1 = a[1].toInt() and 0xFF
                !(b0 == 0 ||
                    b0 == 10 ||
                    b0 == 127 ||
                    (b0 == 100 && b1 in 64..127) || // CGNAT RFC 6598
                    (b0 == 169 && b1 == 254) ||     // link-local / metadata
                    (b0 == 172 && b1 in 16..31) ||  // RFC 1918
                    (b0 == 192 && b1 == 168) ||     // RFC 1918
                    b0 >= 224)                      // multicast + reserved
            }
            16 -> {
                val b0 = a[0].toInt() and 0xFF
                val isGlobalUnicast = (b0 and 0xE0) == 0x20
                val isUla = (b0 and 0xFE) == 0xFC
                isGlobalUnicast && !isUla
            }
            else -> false
        }
    }

    /**
     * Parse [raw] as an HTTPS URL with no embedded credentials whose host
     * passes [isAllowedPublicHost]. Returns the normalized URL string or null.
     */
    fun validateHttpsUrl(raw: String): String? {
        val parsed = raw.trim().toHttpUrlOrNull() ?: return null
        if (!parsed.scheme.equals("https", ignoreCase = true)) return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (!isAllowedPublicHost(parsed.host)) return null
        return parsed.toString()
    }

    /**
     * Execute a GET against [rawUrl] with per-hop HTTPS + public-host
     * validation on redirects so a public server cannot redirect onto
     * loopback, RFC 1918, link-local, or cleartext HTTP.
     *
     * Caller owns and must close the returned [Response].
     */
    fun executeGuardedGet(
        baseClient: OkHttpClient,
        rawUrl: String,
        headers: Map<String, String> = emptyMap(),
        maxRedirects: Int = MAX_REDIRECTS
    ): Response? {
        var currentUrl = validateHttpsUrl(rawUrl) ?: return null
        val noRedirect = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        for (hop in 0..maxRedirects) {
            val reqBuilder = Request.Builder().url(currentUrl)
            for ((k, v) in headers) reqBuilder.header(k, v)
            val resp = noRedirect.newCall(reqBuilder.build()).execute()
            if (resp.code in listOf(301, 302, 303, 307, 308)) {
                val loc = resp.header("Location")
                resp.close()
                if (loc.isNullOrBlank() || hop == maxRedirects) return null
                val resolved = currentUrl.toHttpUrlOrNull()?.resolve(loc)?.toString() ?: return null
                currentUrl = validateHttpsUrl(resolved) ?: return null
                continue
            }
            return resp
        }
        return null
    }

    /**
     * Stream at most [maxBytes] from [body], tolerating chunked / HTTP/2
     * responses where `contentLength() == -1L` while still refusing both
     * declared-oversized and streaming-oversized payloads without OOM.
     */
    fun readBytesCapped(body: ResponseBody?, maxBytes: Int): ByteArray? {
        if (body == null || maxBytes <= 0) return null
        val declared = body.contentLength()
        if (declared > maxBytes.toLong()) return null
        val out = ByteArrayOutputStream(if (declared > 0) declared.toInt().coerceAtMost(65_536) else 4096)
        val buf = ByteArray(8192)
        var total = 0
        body.byteStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    fun readUtf8Capped(body: ResponseBody?, maxBytes: Int): String? {
        val bytes = readBytesCapped(body, maxBytes) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }
}
