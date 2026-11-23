package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.EncryptedMedia
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.SafeHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class BlossomRepository(private val context: Context, pubkeyHex: String? = null) {
    private var activePubkey = pubkeyHex
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val keyRepo = KeyRepository(context)

    private val _servers = MutableStateFlow(loadServers())
    val servers: StateFlow<List<String>> = _servers

    // Strong reference to prevent GC — syncs flow when prefs change from any instance
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "blossom_servers") _servers.value = loadServers()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private val httpClient
        get() = com.wisp.app.relay.HttpClientFactory.createHttpClient(
            connectTimeoutSeconds = 30,
            readTimeoutSeconds = 60,
            writeTimeoutSeconds = 60
        )

    fun updateFromEvent(event: NostrEvent) {
        if (event.kind != Blossom.KIND_SERVER_LIST) return
        val urls = Blossom.parseServerList(event)
        saveBlossomServers(urls)
    }

    fun saveBlossomServers(urls: List<String>) {
        val list = urls.ifEmpty { listOf(Blossom.DEFAULT_SERVER) }
        prefs.edit().putString("blossom_servers", json.encodeToString(list)).apply()
        _servers.value = list
    }

    // In-memory reset only. `prefs` is shared with KeyRepository
    // (`wisp_prefs_{pubkey}`), so `prefs.edit().clear()` would also wipe
    // `relays`, `dm_relays`, etc. `reload(newPubkey)` below
    // repoints to the new account's file.
    fun clear() {
        _servers.value = listOf(Blossom.DEFAULT_SERVER)
    }

    fun reload(pubkeyHex: String?) {
        clear()
        activePubkey = pubkeyHex
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        _servers.value = loadServers()
    }

    fun refreshFromPrefs() {
        _servers.value = loadServers()
    }

    private fun loadServers(): List<String> = readServersFromPrefs(context, activePubkey)

    suspend fun uploadMedia(
        fileBytes: ByteArray,
        mimeType: String,
        ext: String,
        signer: NostrSigner? = null
    ): String = withContext(Dispatchers.IO) {
        val sha256Hex = Blossom.sha256Hex(fileBytes)
        val mediaAuthHeader = if (signer != null) {
            Blossom.createMediaAuth(signer, sha256Hex)
        } else {
            val keypair = keyRepo.getKeypair() ?: throw IllegalStateException("Not logged in")
            Blossom.createMediaAuth(keypair.privkey, keypair.pubkey, sha256Hex)
        }
        var uploadAuthHeader: String? = null
        suspend fun getUploadAuthHeader(): String {
            uploadAuthHeader?.let { return it }
            val header = if (signer != null) {
                Blossom.createUploadAuth(signer, sha256Hex)
            } else {
                val keypair = keyRepo.getKeypair() ?: throw IllegalStateException("Not logged in")
                Blossom.createUploadAuth(keypair.privkey, keypair.pubkey, sha256Hex)
            }
            uploadAuthHeader = header
            return header
        }
        val mediaType = mimeType.toMediaType()
        val body = fileBytes.toRequestBody(mediaType)

        // Build ordered server list with Primal as final fallback
        // nostr.build uses blossom.band for their Blossom endpoint — substitute transparently
        val serverList = loadServers().map { url ->
            if (url.trimEnd('/').equals("https://nostr.build", ignoreCase = true)) {
                "https://blossom.band"
            } else url
        }
        val candidates = (serverList + Blossom.DEFAULT_SERVER).distinct()

        var lastException: Exception? = null
        for (server in candidates) {
            try {
                return@withContext uploadToServer(
                    server, body, mediaAuthHeader, mimeType, sha256Hex, ::getUploadAuthHeader
                )
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: Exception("Upload failed: no servers available")
    }

    /**
     * Returns the served HTTPS URL after verifying the server's descriptor.
     * BUD-05 `/media` uses `t=media` auth; if unsupported (404/405/501), falls
     * back to BUD-02 `/upload` with its own `t=upload` auth and verifies that
     * the descriptor's `sha256` matches [expectedSha256Hex] (fail-closed).
     */
    private suspend fun uploadToServer(
        server: String,
        body: okhttp3.RequestBody,
        mediaAuthHeader: String,
        mimeType: String,
        expectedSha256Hex: String,
        getUploadAuthHeader: suspend () -> String
    ): String {
        val mediaUrl = server.trimEnd('/') + "/media"
        val mediaResult = uploadToEndpoint(
            url = mediaUrl,
            body = body,
            authHeader = mediaAuthHeader,
            mimeType = mimeType,
            expectedSha256Hex = null,
            unsupportedStatusCodes = setOf(404, 405, 501)
        )
        if (mediaResult != null) return mediaResult

        val uploadUrl = server.trimEnd('/') + "/upload"
        return uploadToEndpoint(
            url = uploadUrl,
            body = body,
            authHeader = getUploadAuthHeader(),
            mimeType = mimeType,
            expectedSha256Hex = expectedSha256Hex
        ) ?: throw Exception("Upload failed: unsupported /upload endpoint")
    }

    private fun uploadToEndpoint(
        url: String,
        body: okhttp3.RequestBody,
        authHeader: String,
        mimeType: String,
        expectedSha256Hex: String?,
        unsupportedStatusCodes: Set<Int> = emptySet()
    ): String? {
        val request = Request.Builder()
            .url(url)
            .put(body)
            .header("Authorization", authHeader)
            .header("Content-Type", mimeType)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> {
                    val responseBody = SafeHttp.readUtf8Capped(response.body, SafeHttp.DEFAULT_MAX_JSON_BYTES)
                        ?: throw Exception("Empty or oversized response")
                    val root = json.parseToJsonElement(responseBody) as? kotlinx.serialization.json.JsonObject
                        ?: throw Exception("Malformed upload response")
                    val urlField = root["url"]
                        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        ?: throw Exception("No url in response")
                    if (!urlField.startsWith("https://", ignoreCase = true)) {
                        throw Exception("Blossom returned non-HTTPS URL")
                    }
                    if (expectedSha256Hex != null) {
                        val serverSha256 = root["sha256"]
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            ?.lowercase()?.trim()
                        if (serverSha256.isNullOrBlank() || serverSha256 != expectedSha256Hex.lowercase()) {
                            throw Exception("Blossom sha256 missing or mismatch")
                        }
                    }
                    urlField
                }
                response.code in unsupportedStatusCodes -> null
                else -> throw Exception("Upload failed: ${response.code} ${response.message}")
            }
        }
    }

    data class EncryptedUploadResult(
        val url: String,
        val keyHex: String,
        val nonceHex: String,
        val originalSha256Hex: String,
        val encryptedSha256Hex: String,
        val originalSize: Long
    )

    /**
     * Encrypt a file with AES-256-GCM and upload the encrypted blob to Blossom.
     * Always uses /upload (not /media) since media processing would corrupt the ciphertext.
     */
    suspend fun uploadEncryptedMedia(
        fileBytes: ByteArray,
        mimeType: String,
        signer: NostrSigner? = null
    ): EncryptedUploadResult = withContext(Dispatchers.IO) {
        val encrypted = EncryptedMedia.encryptFile(fileBytes)
        val sha256Hex = encrypted.encryptedSha256Hex
        val authHeader = if (signer != null) {
            Blossom.createUploadAuth(signer, sha256Hex)
        } else {
            val keypair = keyRepo.getKeypair() ?: throw IllegalStateException("Not logged in")
            Blossom.createUploadAuth(keypair.privkey, keypair.pubkey, sha256Hex)
        }
        val body = encrypted.encryptedBytes.toRequestBody("application/octet-stream".toMediaType())

        val serverList = loadServers().map { url ->
            if (url.trimEnd('/').equals("https://nostr.build", ignoreCase = true)) {
                "https://blossom.band"
            } else url
        }
        val candidates = (serverList + Blossom.DEFAULT_SERVER).distinct()

        var lastException: Exception? = null
        for (server in candidates) {
            try {
                val url = uploadToServerRaw(server, body, authHeader, sha256Hex)
                return@withContext EncryptedUploadResult(
                    url = url,
                    keyHex = encrypted.keyHex,
                    nonceHex = encrypted.nonceHex,
                    originalSha256Hex = encrypted.originalSha256Hex,
                    encryptedSha256Hex = encrypted.encryptedSha256Hex,
                    originalSize = fileBytes.size.toLong()
                )
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: Exception("Upload failed: no servers available")
    }

    /**
     * Upload directly to /upload endpoint only (no /media fallback).
     * Used for encrypted blobs where media processing would corrupt the data.
     */
    private fun uploadToServerRaw(
        server: String,
        body: okhttp3.RequestBody,
        authHeader: String,
        expectedSha256Hex: String
    ): String {
        val url = server.trimEnd('/') + "/upload"
        val request = Request.Builder()
            .url(url)
            .put(body)
            .header("Authorization", authHeader)
            .header("Content-Type", "application/octet-stream")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val responseBody = SafeHttp.readUtf8Capped(response.body, SafeHttp.DEFAULT_MAX_JSON_BYTES)
                    ?: throw Exception("Empty or oversized response")
                val root = json.parseToJsonElement(responseBody) as? kotlinx.serialization.json.JsonObject
                    ?: throw Exception("Malformed upload response")
                val urlField = root["url"]
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: throw Exception("No url in response")
                if (!urlField.startsWith("https://", ignoreCase = true)) {
                    throw Exception("Blossom returned non-HTTPS URL")
                }
                val serverSha256 = root["sha256"]
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?.lowercase()?.trim()
                if (serverSha256.isNullOrBlank() || serverSha256 != expectedSha256Hex.lowercase()) {
                    throw Exception("Blossom sha256 missing or mismatch")
                }
                return urlField
            }
            throw Exception("Upload failed: ${response.code} ${response.message}")
        }
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_prefs_$pubkeyHex" else "wisp_prefs"

        private val serversJson = Json { ignoreUnknownKeys = true }

        /**
         * The account's configured Blossom servers straight from its prefs
         * file — for readers without a repository instance (the BUD-10
         * resolver), so an account switch is picked up without constructing
         * anything.
         */
        fun readServersFromPrefs(context: Context, pubkeyHex: String?): List<String> {
            val prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
            val str = prefs.getString("blossom_servers", null) ?: return listOf(Blossom.DEFAULT_SERVER)
            return try {
                serversJson.decodeFromString<List<String>>(str).ifEmpty { listOf(Blossom.DEFAULT_SERVER) }
            } catch (_: Exception) {
                listOf(Blossom.DEFAULT_SERVER)
            }
        }
    }
}
