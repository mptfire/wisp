package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.HttpClientFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfileRepository internal constructor(prefs: SharedPreferences, val avatarDir: File) : AutoCloseable {
    constructor(context: Context) : this(
        context.getSharedPreferences("wisp_profiles", Context.MODE_PRIVATE),
        File(context.filesDir, "avatars").also { it.mkdirs() }
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = PersistedMetadataCache<ProfileData>(
        prefs, "p_",
        encode = { json.encodeToString(it) },
        decode = { json.decodeFromString<ProfileData>(it) },
        onFailure = { Log.w("ProfileRepository", "Metadata write failed", it) }
    )

    fun updateFromEvent(event: NostrEvent): ProfileData? {
        if (event.kind != 0) return null
        val profile = ProfileData.fromEvent(event) ?: return null
        return if (cache.update(event.pubkey, profile, event.created_at)) profile else null
    }

    fun get(pubkey: String): ProfileData? = cache.get(pubkey)

    fun has(pubkey: String): Boolean = get(pubkey) != null

    fun search(query: String, limit: Int = 10): List<ProfileData> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return cache.values().filter { profile ->
            profile.name?.lowercase()?.contains(lowerQuery) == true ||
            profile.displayName?.lowercase()?.contains(lowerQuery) == true ||
            profile.nip05?.lowercase()?.contains(lowerQuery) == true
        }.take(limit)
    }

    suspend fun flush() = cache.flush()

    override fun close() = cache.close()

    suspend fun shutdown() = cache.shutdown()

    /**
     * Returns a local File for the user's cached avatar, or null if not cached.
     */
    fun getLocalAvatar(pubkey: String): File? {
        val file = File(avatarDir, pubkey)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Downloads the avatar from [url] via [HttpClientFactory] (Tor-routed when active)
     * and saves it atomically to local storage with a 10 MB cap.
     */
    suspend fun cacheAvatar(pubkey: String, url: String) {
        if (!url.startsWith("https://", ignoreCase = true)) return
        withContext(Dispatchers.IO) {
            val file = File(avatarDir, pubkey)
            val tmpFile = File(avatarDir, "$pubkey.tmp")
            try {
                val req = Request.Builder().url(url).get().build()
                HttpClientFactory.getImageClient().newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@withContext
                    val body = response.body ?: return@withContext
                    body.byteStream().use { input ->
                        tmpFile.outputStream().use { output ->
                            val buf = ByteArray(8 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > 10L * 1024L * 1024L) {
                                    throw IllegalStateException("Avatar exceeded 10 MB safety limit")
                                }
                                output.write(buf, 0, n)
                            }
                        }
                    }
                }
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
                Log.d("ProfileRepository", "Cached avatar for ${pubkey.take(8)} (${file.length()} bytes)")
            } catch (e: Exception) {
                tmpFile.delete()
                Log.e("ProfileRepository", "Failed to cache avatar for ${pubkey.take(8)}", e)
            }
        }
    }

}
