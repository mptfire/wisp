package com.wisp.app.nostr

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Abstraction over event signing and NIP-44 encrypt/decrypt.
 * LocalSigner uses keys directly; RemoteSigner delegates to an external app via NIP-55.
 */
interface NostrSigner {
    val pubkeyHex: String
    suspend fun signEvent(kind: Int, content: String, tags: List<List<String>> = emptyList(), createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent
    suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String
    suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String
}

/**
 * Signs events and performs NIP-44 operations locally using the private key.
 */
class LocalSigner(
    private val privkey: ByteArray,
    private val pubkey: ByteArray
) : NostrSigner {
    override val pubkeyHex: String = pubkey.toHex()

    override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
        return NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = kind,
            content = content,
            tags = tags,
            createdAt = createdAt
        )
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String {
        val convKey = Nip44.getConversationKey(privkey, peerPubkeyHex.hexToByteArray())
        return Nip44.encrypt(plaintext, convKey)
    }

    override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
        val convKey = Nip44.getConversationKey(privkey, peerPubkeyHex.hexToByteArray())
        return Nip44.decrypt(ciphertext, convKey)
    }
}

// --- NIP-55 Remote Signer (Content Resolver + Intent Fallback) ---

class SignerRejectedException(message: String) : Exception(message)
class SignerCancelledException(message: String) : Exception(message)

/**
 * Delegates all signing/encryption to an external signer app via NIP-55 (e.g. Amber).
 * Tries the ContentResolver (silent mode) first. If the signer returns no cursor
 * (permissions not granted / "always ask" mode), falls back to intent-based signing
 * which launches the signer's approval UI via [SignerIntentBridge].
 */
class RemoteSigner(
    override val pubkeyHex: String,
    private val contentResolver: ContentResolver,
    private val signerPackage: String
) : NostrSigner {

    private val npub: String = Nip19.npubEncode(pubkeyHex.hexToByteArray())

    override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
        val unsigned = NostrEvent.createUnsigned(pubkeyHex, kind, content, tags, createdAt)
        val eventJson = unsigned.toJson()

        // Try silent ContentResolver first
        val crResult = tryContentResolverSign(eventJson, unsigned)
        if (crResult != null) return crResult

        // Fall back to intent-based signing
        return signEventViaIntent(eventJson, unsigned)
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String {
        return tryContentResolver("NIP44_ENCRYPT", plaintext, peerPubkeyHex)
            ?: nip44ViaIntent("nip44_encrypt", plaintext, peerPubkeyHex)
    }

    override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
        return tryContentResolver("NIP44_DECRYPT", ciphertext, peerPubkeyHex)
            ?: nip44ViaIntent("nip44_decrypt", ciphertext, peerPubkeyHex)
    }

    // --- ContentResolver (silent mode) ---

    private suspend fun tryContentResolverSign(
        eventJson: String,
        unsigned: NostrEvent
    ): NostrEvent? = withContext(Dispatchers.IO) {
        if (signerPackage.isBlank()) return@withContext null
        val uri = Uri.parse("content://${signerPackage}.SIGN_EVENT")
        val projection = arrayOf(eventJson, "", npub)
        android.util.Log.d("RemoteSigner", "tryContentResolverSign: uri=$uri, npub=$npub, kind=${unsigned.kind}")
        val cursor = try {
            contentResolver.query(uri, projection, null, null, null)
        } catch (_: Exception) {
            null
        } ?: return@withContext null // no cursor → need intent fallback

        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            if (it.getColumnIndex("rejected") >= 0) throw SignerRejectedException("Signer rejected SIGN_EVENT kind=${unsigned.kind}")

            val eventIdx = it.getColumnIndex("event")
            if (eventIdx >= 0) {
                val signedJson = it.getString(eventIdx)
                if (!signedJson.isNullOrBlank()) {
                    return@withContext NostrEvent.fromJson(signedJson)
                }
            }

            val sigIdx = it.getColumnIndex("signature")
            val resIdx = it.getColumnIndex("result")
            val sig = (if (sigIdx >= 0) it.getString(sigIdx) else null)
                ?: (if (resIdx >= 0) it.getString(resIdx) else null)
                ?: return@withContext null
            return@withContext unsigned.withSignature(sig)
        }
    }

    private suspend fun tryContentResolver(
        method: String,
        data: String,
        peerPubkey: String = ""
    ): String? = withContext(Dispatchers.IO) {
        if (signerPackage.isBlank()) return@withContext null
        val uri = Uri.parse("content://${signerPackage}.$method")
        val projection = arrayOf(data, peerPubkey, npub)
        android.util.Log.d("RemoteSigner", "tryContentResolver: uri=$uri, method=$method")
        val cursor = try {
            contentResolver.query(uri, projection, null, null, null)
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            if (it.getColumnIndex("rejected") >= 0) throw SignerRejectedException("Signer rejected $method")

            val resIdx = it.getColumnIndex("result")
            if (resIdx >= 0) return@withContext it.getString(resIdx)

            val sigIdx = it.getColumnIndex("signature")
            if (sigIdx >= 0) return@withContext it.getString(sigIdx)

            return@withContext if (it.columnCount > 0) it.getString(0) else null
        }
    }

    // --- Intent fallback (launches signer UI) ---

    private suspend fun signEventViaIntent(
        eventJson: String,
        unsigned: NostrEvent
    ): NostrEvent {
        val intent = buildSignerIntent("nostrsigner:$eventJson", "sign_event") {
            putExtra("id", unsigned.id)
        }
        android.util.Log.d("RemoteSigner", "signEventViaIntent: kind=${unsigned.kind}")
        return when (val result = SignerIntentBridge.requestSign(intent)) {
            is SignResult.Success -> {
                if (!result.event.isNullOrBlank()) {
                    NostrEvent.fromJson(result.event)
                } else {
                    unsigned.withSignature(result.result)
                }
            }
            is SignResult.Rejected -> throw SignerRejectedException("Signer rejected SIGN_EVENT kind=${unsigned.kind}")
            is SignResult.Cancelled -> throw SignerCancelledException("Signing cancelled by user")
        }
    }

    private suspend fun nip44ViaIntent(
        type: String,
        data: String,
        peerPubkeyHex: String
    ): String {
        val crMethod = if (type == "nip44_encrypt") "NIP44_ENCRYPT" else "NIP44_DECRYPT"
        val intent = buildSignerIntent("nostrsigner:$data", type) {
            putExtra("pubkey", peerPubkeyHex)
        }
        android.util.Log.d("RemoteSigner", "nip44ViaIntent: type=$type")
        // Retry ContentResolver under the mutex — a prior intent may have granted permission
        // since our initial CR attempt, avoiding a redundant Amber popup.
        val result = SignerIntentBridge.requestSignWithRetry(intent) {
            tryContentResolver(crMethod, data, peerPubkeyHex)
        }
        return when (result) {
            is SignResult.Success -> result.result
            is SignResult.Rejected -> throw SignerRejectedException("Signer rejected $type")
            is SignResult.Cancelled -> throw SignerCancelledException("$type cancelled by user")
        }
    }

    private inline fun buildSignerIntent(
        uriString: String,
        type: String,
        extras: Intent.() -> Unit = {}
    ): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (signerPackage.isNotBlank()) {
                `package` = signerPackage
            }
            putExtra("type", type)
            putExtra("current_user", npub)
            extras()
        }
    }
}

enum class ExternalSignerProtocol {
    /** Local Android IPC signer (`nostrsigner:` — e.g. Amber, Primal, Cambium). */
    NIP55,
    /** Remote/app-to-app NIP-46 bunker pairing (`nostrconnect:` — e.g. Amethyst). */
    NIP46
}

/**
 * An installed Android external signer app discovered via `nostrsigner:` (NIP-55)
 * or `nostrconnect:` (NIP-46).
 */
data class InstalledSignerApp(
    val packageName: String,
    val label: String,
    val protocol: ExternalSignerProtocol = ExternalSignerProtocol.NIP55
)

/**
 * Helpers for NIP-55 and NIP-46 signer discovery and login.
 */
object RemoteSignerBridge {
    const val DEFAULT_PERMISSIONS =
        """[{"type":"sign_event","kind":0},{"type":"sign_event","kind":1},{"type":"sign_event","kind":3},{"type":"sign_event","kind":5},{"type":"sign_event","kind":6},{"type":"sign_event","kind":7},{"type":"sign_event","kind":13},{"type":"sign_event","kind":16},{"type":"sign_event","kind":1111},{"type":"sign_event","kind":9734},{"type":"sign_event","kind":10000},{"type":"sign_event","kind":10001},{"type":"sign_event","kind":10002},{"type":"sign_event","kind":10003},{"type":"sign_event","kind":10030},{"type":"sign_event","kind":10050},{"type":"sign_event","kind":10063},{"type":"sign_event","kind":22242},{"type":"sign_event","kind":24242},{"type":"sign_event","kind":30000},{"type":"sign_event","kind":30003},{"type":"sign_event","kind":30030},{"type":"sign_event","kind":30078},{"type":"nip44_encrypt"},{"type":"nip44_decrypt"}]"""

    private fun queryScheme(
        context: Context,
        scheme: String,
        protocol: ExternalSignerProtocol
    ): List<InstalledSignerApp> {
        val pm = context.packageManager
        val uris = if (scheme == "nostrconnect") {
            listOf(Uri.parse("nostrconnect://"), Uri.parse("nostrconnect:"))
        } else {
            listOf(Uri.parse("$scheme:"))
        }
        return uris.flatMap { uri ->
            val intent = Intent(Intent.ACTION_VIEW, uri)
            pm.queryIntentActivities(intent, 0).mapNotNull { info ->
                val pkg = info.activityInfo?.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: pkg
                InstalledSignerApp(packageName = pkg, label = label, protocol = protocol)
            }
        }
    }

    /**
     * Discovers all installed NIP-55 (`nostrsigner:`) and NIP-46 (`nostrconnect:`) signer apps.
     * When an app supports both schemes, prefers local NIP-55 IPC over relay-based NIP-46.
     */
    fun listAvailableSigners(context: Context): List<InstalledSignerApp> {
        val nip55 = queryScheme(context, "nostrsigner", ExternalSignerProtocol.NIP55)
        val nip46 = queryScheme(context, "nostrconnect", ExternalSignerProtocol.NIP46)
        return (nip55 + nip46)
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun isSignerAvailable(context: Context): Boolean = listAvailableSigners(context).isNotEmpty()

    fun resolveDefaultSignerPackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        return context.packageManager.queryIntentActivities(intent, 0)
            .firstOrNull()?.activityInfo?.packageName
    }

    fun resolveSignedPackage(
        returnedPackage: String?,
        selectedPackage: String?,
        context: Context
    ): String? {
        return returnedPackage?.trim()?.takeIf { it.isNotBlank() }
            ?: selectedPackage?.trim()?.takeIf { it.isNotBlank() }
            ?: resolveDefaultSignerPackage(context)
    }

    fun buildGetPublicKeyIntent(
        permissions: String? = DEFAULT_PERMISSIONS,
        packageName: String? = null
    ): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!packageName.isNullOrBlank()) {
            intent.`package` = packageName
        }
        intent.putExtra("type", "get_public_key")
        if (permissions != null) {
            intent.putExtra("permissions", permissions)
        }
        return intent
    }

    fun buildNostrConnectIntent(
        nostrConnectUri: String,
        packageName: String? = null
    ): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(nostrConnectUri)).apply {
            if (!packageName.isNullOrBlank()) {
                `package` = packageName
            }
        }
    }
}
