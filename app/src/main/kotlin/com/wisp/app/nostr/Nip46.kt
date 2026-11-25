package com.wisp.app.nostr

import com.wisp.app.relay.RelayConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * NIP-46 Nostr Remote Signing ("Nostr Connect" / Bunker) protocol primitives.
 *
 * Supports both signer-initiated `bunker://` URIs and client-initiated `nostrconnect://` URIs,
 * NIP-44 v2 payload encryption (default) with transparent NIP-04 fallback for older nsecBunker
 * instances, and safe `auth_url` challenge extraction.
 */
object Nip46 {
    const val KIND_NOSTR_CONNECT = 24133

    val DEFAULT_NOSTRCONNECT_RELAYS = listOf(
        "wss://bucket.coracle.social",
        "wss://relay.primal.net",
        "wss://relay.nostr.net",
        "wss://offchain.pub"
    )

    const val DEFAULT_PERMISSIONS =
        "sign_event:0,sign_event:1,sign_event:3,sign_event:5,sign_event:6,sign_event:7," +
            "sign_event:13,sign_event:16,sign_event:1111,sign_event:9734," +
            "sign_event:10000,sign_event:10001,sign_event:10002,sign_event:10003," +
            "sign_event:10030,sign_event:10050,sign_event:10063,sign_event:22242,sign_event:24242," +
            "sign_event:30000,sign_event:30003,sign_event:30030,sign_event:30078," +
            "nip44_encrypt,nip44_decrypt"

    private val json = Json { ignoreUnknownKeys = true }
    private val rng = SecureRandom()
    private val HEX_64 = Regex("^[0-9a-f]{64}$")

    data class BunkerUri(
        val remoteSignerPubkey: String,
        val relays: List<String>,
        val secret: String? = null
    )

    @Serializable
    data class BunkerSession(
        val userPubkeyHex: String,
        val remoteSignerPubkeyHex: String,
        val clientPrivkeyHex: String,
        val relays: List<String>,
        val useNip04: Boolean = false
    ) {
        fun clientPrivkeyBytes(): ByteArray = clientPrivkeyHex.hexToByteArray()
        fun clientPubkeyHex(): String = Keys.xOnlyPubkey(clientPrivkeyBytes()).toHex()
    }

    data class RpcRequest(
        val id: String,
        val method: String,
        val params: List<String> = emptyList()
    )

    data class RpcResponse(
        val id: String,
        val result: String? = null,
        val error: String? = null
    )

    fun generateSecret(byteLength: Int = 16): String {
        val bytes = ByteArray(byteLength)
        rng.nextBytes(bytes)
        return bytes.toHex()
    }

    fun generateRequestId(): String = generateSecret(12)

    /**
     * Parses a `bunker://<remote-signer-pubkey>?relay=...&secret=...` URI.
     * Tolerates optional `nostr:` prefix, uppercase pubkeys, multiple `relay=` params,
     * and both URL-encoded and raw relay query values.
     */
    fun parseBunkerUri(raw: String): BunkerUri? {
        val trimmed = raw.trim()
        val stripped = if (trimmed.length >= 6 && trimmed.substring(0, 6).equals("nostr:", ignoreCase = true)) {
            trimmed.substring(6).trim()
        } else {
            trimmed
        }
        if (!stripped.startsWith("bunker://", ignoreCase = true)) return null

        val rest = stripped.substring("bunker://".length)
        val qIdx = rest.indexOf('?')
        if (qIdx < 0) return null

        val pubkeyHex = rest.substring(0, qIdx).trim().trimEnd('/').lowercase()
        if (!HEX_64.matches(pubkeyHex)) return null

        val rawQuery = rest.substring(qIdx + 1)
        val relays = mutableListOf<String>()
        var secret: String? = null

        for (pair in rawQuery.split('&')) {
            if (pair.isBlank()) continue
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) continue
            val key = runCatching { URLDecoder.decode(pair.substring(0, eqIdx), "UTF-8").trim().lowercase() }
                .getOrNull() ?: continue
            val value = runCatching { URLDecoder.decode(pair.substring(eqIdx + 1), "UTF-8").trim() }
                .getOrNull() ?: continue
            when (key) {
                "relay" -> {
                    val cleanRelay = value.trimEnd('/')
                    if (RelayConfig.isValidUrl(cleanRelay) || RelayConfig.isLocalRelayUrl(cleanRelay)) {
                        if (cleanRelay !in relays) relays.add(cleanRelay)
                    }
                }
                "secret" -> if (value.isNotEmpty()) secret = value
            }
        }

        if (relays.isEmpty()) return null
        return BunkerUri(
            remoteSignerPubkey = pubkeyHex,
            relays = relays,
            secret = secret
        )
    }

    /**
     * Builds a client-initiated `nostrconnect://<client-pubkey>?...` URI for QR / deep-link pairing.
     */
    fun buildNostrConnectUri(
        clientPubkeyHex: String,
        relays: List<String> = DEFAULT_NOSTRCONNECT_RELAYS,
        secret: String,
        appName: String = "Fire Wisp",
        appUrl: String = "https://firewisp.de",
        perms: String = DEFAULT_PERMISSIONS
    ): String {
        val params = mutableListOf<String>()
        for (relay in relays) {
            params.add("relay=${URLEncoder.encode(relay, "UTF-8")}")
        }
        params.add("secret=${URLEncoder.encode(secret, "UTF-8")}")
        if (perms.isNotBlank()) {
            params.add("perms=${URLEncoder.encode(perms, "UTF-8")}")
        }
        if (appName.isNotBlank()) {
            params.add("name=${URLEncoder.encode(appName, "UTF-8")}")
        }
        if (appUrl.isNotBlank()) {
            params.add("url=${URLEncoder.encode(appUrl, "UTF-8")}")
        }
        return "nostrconnect://${clientPubkeyHex.lowercase()}?${params.joinToString("&")}"
    }

    fun buildUnsignedEventJson(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long
    ): String {
        return buildJsonObject {
            put("kind", kind)
            put("created_at", createdAt)
            put("tags", buildJsonArray {
                for (tag in tags) {
                    add(JsonArray(tag.map { JsonPrimitive(it) }))
                }
            })
            put("content", content)
        }.toString()
    }

    fun encryptPayload(
        plaintext: String,
        privkey: ByteArray,
        peerPubkeyHex: String,
        useNip04: Boolean = false
    ): String {
        val peerBytes = peerPubkeyHex.hexToByteArray()
        return if (useNip04) {
            val shared = Nip04.computeSharedSecret(privkey, peerBytes)
            Nip04.encrypt(plaintext, shared)
        } else {
            val convKey = Nip44.getConversationKey(privkey, peerBytes)
            Nip44.encrypt(plaintext, convKey)
        }
    }

    /**
     * Decrypts a NIP-46 payload, automatically detecting legacy NIP-04 (`?iv=`) vs NIP-44.
     * Returns `(plaintext, usedNip04)`.
     */
    fun decryptPayload(
        ciphertext: String,
        privkey: ByteArray,
        senderPubkeyHex: String
    ): Pair<String, Boolean> {
        val senderBytes = senderPubkeyHex.hexToByteArray()
        return if (ciphertext.contains("?iv=")) {
            val shared = Nip04.computeSharedSecret(privkey, senderBytes)
            Nip04.decrypt(ciphertext, shared) to true
        } else {
            val convKey = Nip44.getConversationKey(privkey, senderBytes)
            Nip44.decrypt(ciphertext, convKey) to false
        }
    }

    fun buildRequestEvent(
        clientPrivkey: ByteArray,
        remoteSignerPubkeyHex: String,
        request: RpcRequest,
        useNip04: Boolean = false
    ): NostrEvent {
        val clientPubkey = Keys.xOnlyPubkey(clientPrivkey)
        val payload = buildJsonObject {
            put("id", request.id)
            put("method", request.method)
            put("params", JsonArray(request.params.map { JsonPrimitive(it) }))
        }.toString()
        val encrypted = encryptPayload(payload, clientPrivkey, remoteSignerPubkeyHex, useNip04)
        return NostrEvent.create(
            privkey = clientPrivkey,
            pubkey = clientPubkey,
            kind = KIND_NOSTR_CONNECT,
            content = encrypted,
            tags = listOf(listOf("p", remoteSignerPubkeyHex.lowercase()))
        )
    }

    /**
     * Parses and decrypts an incoming kind-24133 response event.
     * When [expectedSignerPubkeyHex] is non-null, events from any other pubkey are rejected.
     * Returns `(RpcResponse, usedNip04)` or `null` if invalid / wrong sender / undecryptable.
     */
    fun parseResponseEvent(
        event: NostrEvent,
        clientPrivkey: ByteArray,
        expectedSignerPubkeyHex: String? = null
    ): Pair<RpcResponse, Boolean>? {
        if (event.kind != KIND_NOSTR_CONNECT) return null
        if (expectedSignerPubkeyHex != null && !event.pubkey.equals(expectedSignerPubkeyHex, ignoreCase = true)) {
            return null
        }
        return try {
            val (plaintext, usedNip04) = decryptPayload(event.content, clientPrivkey, event.pubkey)
            val obj = json.parseToJsonElement(plaintext).jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return null
            val result = obj["result"]?.let { el ->
                if (el is JsonPrimitive && el.isString) el.content else null
            }
            val error = obj["error"]?.let { el ->
                if (el is JsonPrimitive && el.isString) el.content else null
            }
            RpcResponse(id = id, result = result, error = error) to usedNip04
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a remote signer's initial pairing message for `nostrconnect://`.
     * Supports both:
     * - Modern NIP-46 `BunkerResponse`: `{"id":"...","result":"<secret>"}` (e.g. Amethyst, Amber)
     * - Legacy NIP-46 `BunkerRequest`: `{"id":"...","method":"connect","params":["<pubkey>","<secret>"]}`
     * Returns `(remoteSignerPubkeyHex, optionalRequestIdToAck, usedNip04)` or `null` if secret doesn't match.
     */
    data class NostrConnectHandshake(
        val remoteSignerPubkeyHex: String,
        val requestIdToAck: String?,
        val usedNip04: Boolean
    )

    fun parseNostrConnectHandshake(
        event: NostrEvent,
        clientPrivkey: ByteArray,
        expectedSecret: String
    ): NostrConnectHandshake? {
        if (event.kind != KIND_NOSTR_CONNECT) return null
        return try {
            val (plaintext, usedNip04) = decryptPayload(event.content, clientPrivkey, event.pubkey)
            val obj = json.parseToJsonElement(plaintext).jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return null
            val method = obj["method"]?.let { el ->
                if (el is JsonPrimitive && el.isString) el.content else null
            }
            if (method == "connect") {
                val paramsArr = obj["params"] as? JsonArray ?: return null
                val secretParam = paramsArr.getOrNull(1)?.let { el ->
                    if (el is JsonPrimitive && el.isString) el.content else null
                }
                if (secretParam != expectedSecret) return null
                return NostrConnectHandshake(
                    remoteSignerPubkeyHex = event.pubkey.lowercase(),
                    requestIdToAck = id,
                    usedNip04 = usedNip04
                )
            }
            val error = obj["error"]?.let { el ->
                if (el is JsonPrimitive && el.isString) el.content else null
            }
            if (!error.isNullOrBlank()) return null
            val result = obj["result"]?.let { el ->
                if (el is JsonPrimitive && el.isString) el.content else null
            }
            if (result != expectedSecret) return null
            NostrConnectHandshake(
                remoteSignerPubkeyHex = event.pubkey.lowercase(),
                requestIdToAck = null,
                usedNip04 = usedNip04
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildAckResponseEvent(
        clientPrivkey: ByteArray,
        remoteSignerPubkeyHex: String,
        requestId: String,
        useNip04: Boolean = false
    ): NostrEvent {
        val clientPubkey = Keys.xOnlyPubkey(clientPrivkey)
        val payload = buildJsonObject {
            put("id", requestId)
            put("result", "ack")
        }.toString()
        val encrypted = encryptPayload(payload, clientPrivkey, remoteSignerPubkeyHex, useNip04)
        return NostrEvent.create(
            privkey = clientPrivkey,
            pubkey = clientPubkey,
            kind = KIND_NOSTR_CONNECT,
            content = encrypted,
            tags = listOf(listOf("p", remoteSignerPubkeyHex.lowercase()))
        )
    }

    /**
     * If [response] is a NIP-46 `auth_url` challenge with a safe public `https://` URL,
     * returns that validated URL; otherwise returns `null`.
     */
    fun extractSafeAuthUrl(response: RpcResponse): String? {
        if (response.result != "auth_url") return null
        val candidate = response.error?.trim() ?: return null
        return SafeHttp.validateHttpsUrl(candidate)?.toString()
    }
}
