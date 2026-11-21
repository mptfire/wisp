package com.wisp.app.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.net.URLEncoder

enum class Nip05Result {
    /** Pubkey matches the NIP-05 record. */
    VERIFIED,
    /** Server responded but pubkey does not match — possible impersonation. */
    MISMATCH,
    /** Could not reach server or parse response — temporary/unknown failure. */
    ERROR
}

object Nip05 {
    private val json = Json { ignoreUnknownKeys = true }
    private val PUBKEY_HEX = Regex("^[0-9a-f]{64}$")

    /**
     * Verify a NIP-05 identifier against a pubkey.
     * Parses "local@domain", fetches https://domain/.well-known/nostr.json?name=local,
     * and checks that names[local] matches the given pubkey hex.
     *
     * Returns [Nip05Result.VERIFIED] on match, [Nip05Result.MISMATCH] when the server
     * responds with a different pubkey, or [Nip05Result.ERROR] on network/parse failures.
     */
    suspend fun verify(identifier: String, pubkeyHex: String, httpClient: OkHttpClient): Nip05Result =
        withContext(Dispatchers.IO) {
            try {
                val parts = identifier.split("@", limit = 2)
                if (parts.size != 2) return@withContext Nip05Result.ERROR

                val local = parts[0].trim().ifEmpty { return@withContext Nip05Result.ERROR }
                val domain = parts[1].trim().ifEmpty { return@withContext Nip05Result.ERROR }
                if (!SafeHttp.isAllowedPublicHost(domain)) return@withContext Nip05Result.ERROR

                val encodedLocal = URLEncoder.encode(local, "UTF-8")
                val url = "https://$domain/.well-known/nostr.json?name=$encodedLocal"
                val response = SafeHttp.executeGuardedGet(httpClient, url)
                    ?: return@withContext Nip05Result.ERROR

                response.use {
                    if (!it.isSuccessful) return@withContext Nip05Result.ERROR

                    val body = SafeHttp.readUtf8Capped(it.body, SafeHttp.DEFAULT_MAX_JSON_BYTES)
                        ?: return@withContext Nip05Result.ERROR
                    val root = json.parseToJsonElement(body).jsonObject
                    val names = root["names"]?.jsonObject ?: return@withContext Nip05Result.MISMATCH
                    // Case-insensitive lookup per NIP-05 spec (servers may return capitalized keys)
                    val registeredPubkey = (names[local] ?: names.entries.firstOrNull { entry ->
                        entry.key.equals(local, ignoreCase = true)
                    }?.value)?.jsonPrimitive?.content
                        ?: return@withContext Nip05Result.MISMATCH

                    if (registeredPubkey.equals(pubkeyHex, ignoreCase = true))
                        Nip05Result.VERIFIED
                    else
                        Nip05Result.MISMATCH
                }
            } catch (_: Exception) {
                Nip05Result.ERROR
            }
        }

    data class Resolved(val pubkey: String, val relays: List<String>)

    /**
     * Look up the pubkey (and any relay hints) registered for a NIP-05 identifier.
     * An empty local part (`@domain`) resolves as the root `_@domain`.
     * Returns null when the name isn't registered or the server can't be reached.
     */
    suspend fun resolve(identifier: String, httpClient: OkHttpClient): Resolved? =
        withContext(Dispatchers.IO) {
            try {
                val parts = identifier.split("@", limit = 2)
                if (parts.size != 2) return@withContext null
                val local = parts[0].trim().ifEmpty { "_" }
                val domain = parts[1].trim().ifEmpty { return@withContext null }
                if (!SafeHttp.isAllowedPublicHost(domain)) return@withContext null

                val encodedLocal = URLEncoder.encode(local, "UTF-8")
                val url = "https://$domain/.well-known/nostr.json?name=$encodedLocal"
                val response = SafeHttp.executeGuardedGet(httpClient, url)
                    ?: return@withContext null
                response.use {
                    if (!it.isSuccessful) return@withContext null
                    val body = SafeHttp.readUtf8Capped(it.body, SafeHttp.DEFAULT_MAX_JSON_BYTES)
                        ?: return@withContext null
                    val root = json.parseToJsonElement(body).jsonObject
                    val names = root["names"]?.jsonObject ?: return@withContext null
                    val pubkey = (names[local] ?: names.entries.firstOrNull { entry ->
                        entry.key.equals(local, ignoreCase = true)
                    }?.value)?.jsonPrimitive?.content?.lowercase()
                        ?: return@withContext null
                    if (!PUBKEY_HEX.matches(pubkey)) return@withContext null
                    // Only trust wss:// hints; drop malformed entries instead of
                    // letting one bad element discard the whole hint list.
                    val relays = try {
                        root["relays"]?.jsonObject?.get(pubkey)?.jsonArray
                            ?.mapNotNull { entry ->
                                runCatching { entry.jsonPrimitive.content.trim() }.getOrNull()
                            }
                            ?.filter { it.startsWith("wss://") }
                            ?.distinct()
                            ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    Resolved(pubkey, relays)
                }
            } catch (_: Exception) {
                null
            }
        }

    /** Strip the `_` root-domain local part for display (`_@domain` → `@domain`). */
    fun formatForDisplay(identifier: String): String =
        if (identifier.startsWith("_@")) identifier.substring(1) else identifier
}
