package com.wisp.app.relay

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class LocalRelayWritePolicy { OWN_NOTES, TAGGED, ALL_NOTES }

sealed interface LocalRelayStatus {
    data object Disabled : LocalRelayStatus
    data object Connecting : LocalRelayStatus
    data object Connected : LocalRelayStatus
    data class Error(val message: String) : LocalRelayStatus
}

@Serializable
data class LocalRelayConfig(
    val url: String,
    val enabled: Boolean = true,
    val writePolicy: LocalRelayWritePolicy = LocalRelayWritePolicy.OWN_NOTES,
    val kinds: Set<Int> = setOf(1, 1059, 9735)
)

enum class RelaySetType(val displayName: String, val eventKind: Int) {
    GENERAL("General", 10002),
    DM("DM", 10050),
    SEARCH("Search", 10007),
    BLOCKED("Blocked", 10006),
    LOCAL("Local", 0)
}

@Serializable
data class RelayConfig(
    val url: String,
    val read: Boolean = true,
    val write: Boolean = true,
    val auth: Boolean = true
) {
    companion object {
        const val LEGACY_FIREWISP_RELAY = "wss://firewisp.relay.rtard.de"
        const val CANONICAL_FIREWISP_RELAY = "wss://relay.firewisp.de"
        const val CITRINE_DEFAULT_URL = "ws://127.0.0.1:4869"

        val DEFAULTS = listOf(
            RelayConfig(CANONICAL_FIREWISP_RELAY, read = true, write = true),
            RelayConfig("wss://relay.damus.io", read = true, write = true),
            RelayConfig("wss://relay.primal.net", read = true, write = true),
            RelayConfig("wss://indexer.coracle.social", read = true, write = false),
            RelayConfig("wss://relay.nos.social", read = true, write = false)
        )

        /** Default DM relays applied when a user has no DM relay set (kind 10050). */
        val DEFAULT_DM_RELAYS = listOf(
            "wss://auth.nostr1.com"
        )

        /** Fallback indexer relays used when the user hasn't configured search relays (kind 10007). */
        val DEFAULT_INDEXER_RELAYS = listOf(
            "wss://indexer.coracle.social",
            "wss://relay.nos.social",
            "wss://nos.lol",
            "wss://indexer.nostrarchives.com",
            "wss://relay.damus.io",
            "wss://relay.primal.net"
        )

        /** Canonical key for publication targets and responses, using OkHttp's URL parser. */
        fun publicationUrl(url: String): String? {
            if (!url.startsWith("wss://", ignoreCase = true)) return null
            val parsed = ("https://" + url.substring(6)).toHttpUrlOrNull() ?: return null
            val canonical = "wss://" + parsed.toString().substring(8)
            return if (parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
                canonical.removeSuffix("/")
            } else canonical
        }

        private val IP_HOST_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

        /**
         * Structural URL validation — can this URL be stored in a relay list?
         * Rejects: non-wss schemes, localhost, IP addresses, URLs with ports.
         */
        fun isValidUrl(url: String): Boolean {
            if (!url.startsWith("wss://")) return false
            val afterScheme = url.removePrefix("wss://")
            val hostPort = afterScheme.split("/", limit = 2)[0]
            if (":" in hostPort) return false // has a port
            val host = hostPort.lowercase()
            if (host == "localhost" || host.endsWith(".localhost")) return false
            if (IP_HOST_REGEX.matches(host)) return false
            return true
        }

        private val MDNS_LOCAL_HOST_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\\.local$")

        /** Returns true if [host] is loopback (localhost, 127.0.0.1, ::1). */
        fun isLoopbackHost(host: String): Boolean {
            val h = host.trim().lowercase().removeSurrounding("[", "]")
            return h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0:0:0:0:0:0:0:1"
        }

        /**
         * Returns true if [host] is strictly a loopback address, RFC 1918 private IPv4
         * (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), or *.local mDNS LAN hostname.
         * Never matches public domains or link-local metadata IPs (169.254.x.x).
         */
        fun isLocalRelayHost(host: String): Boolean {
            val h = host.trim().lowercase().removeSurrounding("[", "]")
            if (h.isEmpty()) return false
            if (isLoopbackHost(h)) return true
            if (MDNS_LOCAL_HOST_REGEX.matches(h)) return true
            if (IP_HOST_REGEX.matches(h)) {
                val parts = h.split('.').map { it.toIntOrNull() ?: return false }
                if (parts.size != 4 || parts.any { it !in 0..255 }) return false
                val (a, b) = parts[0] to parts[1]
                return a == 10 ||
                    (a == 192 && b == 168) ||
                    (a == 172 && b in 16..31)
            }
            return false
        }

        /**
         * Validates a local relay URL (Citrine loopback or RFC 1918 / *.local LAN relay).
         * Accepts ws:// or wss:// with optional port, rejects credentials and public hosts.
         */
        fun isLocalRelayUrl(url: String): Boolean {
            val trimmed = url.trim()
            val httpEquivalent = when {
                trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
                trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
                else -> return false
            }
            val parsed = httpEquivalent.toHttpUrlOrNull() ?: return false
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
            if (parsed.port !in 1..65535) return false
            return isLocalRelayHost(parsed.host)
        }

        fun isLoopbackRelayUrl(url: String): Boolean {
            val trimmed = url.trim()
            val httpEquivalent = when {
                trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
                trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
                else -> return false
            }
            val parsed = httpEquivalent.toHttpUrlOrNull() ?: return false
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
            return isLoopbackHost(parsed.host)
        }
    }
}
