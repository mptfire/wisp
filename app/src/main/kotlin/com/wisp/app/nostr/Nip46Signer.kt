package com.wisp.app.nostr

import android.util.Log
import com.wisp.app.relay.HttpClientFactory
import com.wisp.app.relay.Relay
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.TorManager
import com.wisp.app.repo.TorPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Nip46Signer"

/**
 * Bridges NIP-46 `auth_url` approval challenges from the domain layer to Compose UI
 * (AuthScreen during login, Navigation during normal signing).
 */
object Nip46AuthBridge {
    private val _pendingAuthUrl = MutableStateFlow<String?>(null)
    val pendingAuthUrl: StateFlow<String?> = _pendingAuthUrl

    fun requestAuthUrl(url: String) {
        _pendingAuthUrl.value = url
    }

    fun dismissAuthUrl() {
        _pendingAuthUrl.value = null
    }
}

class BunkerSignerException(message: String) : Exception(message)

/**
 * [NostrSigner] backed by a NIP-46 remote bunker (nsec.app, nsecBunker, nak bunker,
 * Amber NIP-46, etc.) over kind-24133 encrypted JSON-RPC.
 *
 * Uses [HttpClientFactory.getRelayClient] for public `wss://` relays (preserving Tor
 * fail-closed routing) and [HttpClientFactory.getLocalRelayClient] for local relays.
 */
class Nip46Signer(
    val session: Nip46.BunkerSession
) : NostrSigner {

    override val pubkeyHex: String = session.userPubkeyHex.lowercase()

    private val clientPrivkey: ByteArray = session.clientPrivkeyBytes()
    private val clientPubkeyHex: String = session.clientPubkeyHex()
    @Volatile private var useNip04: Boolean = session.useNip04

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<Relay>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Nip46.RpcResponse>>()
    private val authTriggeredIds = ConcurrentHashMap.newKeySet<String>()
    private val subId = "nip46-${clientPubkeyHex.take(8)}"

    @Volatile private var started = false
    @Volatile private var disposed = false

    private fun clientProviderFor(url: String): () -> OkHttpClient =
        if (RelayConfig.isLocalRelayUrl(url)) {
            { HttpClientFactory.getLocalRelayClient() }
        } else {
            { HttpClientFactory.getRelayClient() }
        }

    @Synchronized
    fun ensureConnected() {
        if (disposed || started) return
        started = true
        for (url in session.relays) {
            val relay = Relay(RelayConfig(url, read = true, write = true), clientProviderFor(url), scope)
            relay.autoReconnect = true
            relays.add(relay)
            attachRelay(relay)
            relay.connect()
        }
    }

    private fun attachRelay(relay: Relay) {
        scope.launch {
            relay.connectionState.collect { connected ->
                if (connected && !disposed) {
                    subscribeOnRelay(relay)
                }
            }
        }
        scope.launch {
            relay.messages.collect { msg ->
                if (msg is RelayMessage.EventMsg && msg.event.kind == Nip46.KIND_NOSTR_CONNECT) {
                    val parsed = Nip46.parseResponseEvent(
                        event = msg.event,
                        clientPrivkey = clientPrivkey,
                        expectedSignerPubkeyHex = session.remoteSignerPubkeyHex
                    ) ?: return@collect
                    val (resp, respondedNip04) = parsed
                    if (respondedNip04) useNip04 = true

                    val authUrl = Nip46.extractSafeAuthUrl(resp)
                    if (authUrl != null) {
                        authTriggeredIds.add(resp.id)
                        Nip46AuthBridge.requestAuthUrl(authUrl)
                        return@collect
                    }

                    pending.remove(resp.id)?.complete(resp)
                }
            }
        }
    }

    private fun subscribeOnRelay(relay: Relay) {
        val since = (System.currentTimeMillis() / 1000) - 10
        val filter = Filter(
            kinds = listOf(Nip46.KIND_NOSTR_CONNECT),
            pTags = listOf(clientPubkeyHex),
            since = since
        )
        relay.send(ClientMessage.req(subId, filter))
    }

    private suspend fun awaitAnyConnected(timeoutMs: Long = 15_000L): Boolean {
        if (session.relays.none { RelayConfig.isLocalRelayUrl(it) } && TorPreferences.isEnabled()) {
            TorManager.awaitOnIfEnabled()
        }
        val effectiveTimeout = if (TorPreferences.isEnabled()) maxOf(timeoutMs, 25_000L) else timeoutMs
        ensureConnected()
        if (relays.any { it.isConnected }) return true
        val connected = CompletableDeferred<Boolean>()
        val jobs = relays.map { relay ->
            scope.launch {
                if (relay.awaitConnected(effectiveTimeout)) {
                    connected.complete(true)
                }
            }
        }
        val ok = withTimeoutOrNull(effectiveTimeout) { connected.await() } == true
        jobs.forEach { it.cancel() }
        return ok
    }

    private suspend fun sendRpc(
        method: String,
        params: List<String>,
        timeoutMs: Long = 30_000L
    ): String {
        if (disposed) throw BunkerSignerException("Bunker signer has been disconnected")
        if (!awaitAnyConnected()) {
            throw BunkerSignerException("Could not reach bunker relays")
        }

        val reqId = Nip46.generateRequestId()
        val deferred = CompletableDeferred<Nip46.RpcResponse>()
        pending[reqId] = deferred

        val event = Nip46.buildRequestEvent(
            clientPrivkey = clientPrivkey,
            remoteSignerPubkeyHex = session.remoteSignerPubkeyHex,
            request = Nip46.RpcRequest(id = reqId, method = method, params = params),
            useNip04 = useNip04
        )
        val msg = ClientMessage.event(event)
        var sentCount = 0
        for (relay in relays) {
            if (relay.send(msg)) sentCount++
        }
        if (sentCount == 0) {
            pending.remove(reqId)
            throw BunkerSignerException("Failed to send NIP-46 request to bunker relays")
        }

        try {
            val response = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // If an interactive auth_url popup was opened, grant an extra 60s window.
                if (authTriggeredIds.contains(reqId)) {
                    withTimeout(60_000L) { deferred.await() }
                } else {
                    throw BunkerSignerException("Bunker timed out waiting for $method")
                }
            }
            val err = response.error
            if (!err.isNullOrBlank()) {
                throw BunkerSignerException("Bunker rejected $method: $err")
            }
            return response.result
                ?: throw BunkerSignerException("Bunker returned empty result for $method")
        } finally {
            pending.remove(reqId)
            authTriggeredIds.remove(reqId)
        }
    }

    override suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long
    ): NostrEvent {
        val unsignedJson = Nip46.buildUnsignedEventJson(kind, content, tags, createdAt)
        val signedJson = sendRpc("sign_event", listOf(unsignedJson))
        val signed = NostrEvent.fromJson(signedJson)
        if (!signed.pubkey.equals(pubkeyHex, ignoreCase = true)) {
            throw BunkerSignerException("Bunker signed event with unexpected pubkey")
        }
        if (signed.kind != kind) {
            throw BunkerSignerException("Bunker signed event with unexpected kind ${signed.kind}")
        }
        return signed
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String {
        return sendRpc("nip44_encrypt", listOf(peerPubkeyHex.lowercase(), plaintext))
    }

    override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
        return sendRpc("nip44_decrypt", listOf(peerPubkeyHex.lowercase(), ciphertext))
    }

    suspend fun ping(timeoutMs: Long = 10_000L): Boolean {
        return try {
            val res = sendRpc("ping", emptyList(), timeoutMs = timeoutMs)
            res.equals("pong", ignoreCase = true) || res.isNotBlank()
        } catch (e: Exception) {
            Log.w(TAG, "Bunker ping failed: ${e.message}")
            false
        }
    }

    fun isConnected(): Boolean = relays.any { it.isConnected }

    @Synchronized
    fun disconnect() {
        if (disposed) return
        disposed = true
        pending.values.forEach { it.cancel() }
        pending.clear()
        authTriggeredIds.clear()
        relays.forEach { it.forceDisconnect() }
        relays.clear()
        clientPrivkey.fill(0)
        scope.cancel()
    }
}

/**
 * One-shot pairing flows for initial NIP-46 login:
 * 1. `connectWithBunkerUri` — signer-initiated (`bunker://...`)
 * 2. `awaitNostrConnect` — client-initiated (`nostrconnect://...`)
 */
object Nip46Connector {

    private fun clientProviderFor(url: String): () -> OkHttpClient =
        if (RelayConfig.isLocalRelayUrl(url)) {
            { HttpClientFactory.getLocalRelayClient() }
        } else {
            { HttpClientFactory.getRelayClient() }
        }

    suspend fun connectWithBunkerUri(
        rawUri: String,
        onStatus: (String) -> Unit = {}
    ): Nip46.BunkerSession {
        val parsed = Nip46.parseBunkerUri(rawUri)
            ?: throw BunkerSignerException("Invalid bunker:// URI — check pubkey and relay URL")

        val clientKeypair = Keys.generate()
        val clientPriv = clientKeypair.privkey.copyOf()
        val clientPubHex = clientKeypair.pubkey.toHex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relays = mutableListOf<Relay>()
        val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Nip46.RpcResponse, Boolean>>>()
        val authTriggeredIds = ConcurrentHashMap.newKeySet<String>()
        val subId = "nip46-init-${clientPubHex.take(8)}"

        try {
            if (parsed.relays.none { RelayConfig.isLocalRelayUrl(it) } && TorPreferences.isEnabled()) {
                onStatus("Waiting for Tor to connect…")
                if (!TorManager.awaitOnIfEnabled()) {
                    throw BunkerSignerException("Tor failed to bootstrap — disable Tor or retry")
                }
            }
            val connectTimeoutMs = if (TorPreferences.isEnabled()) 30_000L else 15_000L
            onStatus("Connecting to bunker relay…")
            val connectedDeferred = CompletableDeferred<Boolean>()
            for (url in parsed.relays) {
                val relay = Relay(RelayConfig(url, read = true, write = true), clientProviderFor(url), scope)
                relay.autoReconnect = true
                relays.add(relay)
                scope.launch {
                    relay.messages.collect { msg ->
                        if (msg is RelayMessage.EventMsg && msg.event.kind == Nip46.KIND_NOSTR_CONNECT) {
                            val respPair = Nip46.parseResponseEvent(
                                event = msg.event,
                                clientPrivkey = clientPriv,
                                expectedSignerPubkeyHex = parsed.remoteSignerPubkey
                            ) ?: return@collect
                            val authUrl = Nip46.extractSafeAuthUrl(respPair.first)
                            if (authUrl != null) {
                                authTriggeredIds.add(respPair.first.id)
                                onStatus("Approve connection in signer…")
                                Nip46AuthBridge.requestAuthUrl(authUrl)
                                return@collect
                            }
                            pending.remove(respPair.first.id)?.complete(respPair)
                        }
                    }
                }
                scope.launch {
                    if (relay.awaitConnected(connectTimeoutMs)) {
                        val filter = Filter(
                            kinds = listOf(Nip46.KIND_NOSTR_CONNECT),
                            pTags = listOf(clientPubHex),
                            since = (System.currentTimeMillis() / 1000) - 10
                        )
                        relay.send(ClientMessage.req(subId, filter))
                        connectedDeferred.complete(true)
                    }
                }
                relay.connect()
            }

            if (withTimeoutOrNull(connectTimeoutMs) { connectedDeferred.await() } != true) {
                throw BunkerSignerException("Could not connect to bunker relay(s)")
            }

            // Brief pause so the subscription registers on the relay before sending connect
            kotlinx.coroutines.delay(300)

            var useNip04 = false
            suspend fun call(method: String, params: List<String>, timeoutMs: Long): Nip46.RpcResponse {
                val id = Nip46.generateRequestId()
                val deferred = CompletableDeferred<Pair<Nip46.RpcResponse, Boolean>>()
                pending[id] = deferred
                val event = Nip46.buildRequestEvent(
                    clientPrivkey = clientPriv,
                    remoteSignerPubkeyHex = parsed.remoteSignerPubkey,
                    request = Nip46.RpcRequest(id = id, method = method, params = params),
                    useNip04 = useNip04
                )
                val msg = ClientMessage.event(event)
                relays.forEach { it.send(msg) }
                try {
                    val (resp, respondedNip04) = try {
                        withTimeout(timeoutMs) { deferred.await() }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        if (authTriggeredIds.contains(id)) {
                            withTimeout(90_000L) { deferred.await() }
                        } else {
                            throw BunkerSignerException("Bunker timed out on $method")
                        }
                    }
                    if (respondedNip04) useNip04 = true
                    if (!resp.error.isNullOrBlank()) {
                        throw BunkerSignerException(resp.error)
                    }
                    return resp
                } finally {
                    pending.remove(id)
                    authTriggeredIds.remove(id)
                }
            }

            onStatus("Authorizing with remote bunker…")
            val connectParams = listOf(
                parsed.remoteSignerPubkey,
                parsed.secret ?: "",
                Nip46.DEFAULT_PERMISSIONS
            )
            val connectResp = call("connect", connectParams, timeoutMs = 45_000L)
            val connectResult = connectResp.result.orEmpty()
            if (connectResult != "ack" && (parsed.secret == null || connectResult != parsed.secret)) {
                throw BunkerSignerException("Unexpected bunker connect response: $connectResult")
            }

            onStatus("Fetching account public key…")
            val pkResp = call("get_public_key", emptyList(), timeoutMs = 20_000L)
            val userPubkeyHex = pkResp.result?.trim()?.lowercase()
                ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
                ?: throw BunkerSignerException("Bunker returned invalid public key")

            return Nip46.BunkerSession(
                userPubkeyHex = userPubkeyHex,
                remoteSignerPubkeyHex = parsed.remoteSignerPubkey,
                clientPrivkeyHex = clientPriv.toHex(),
                relays = parsed.relays,
                useNip04 = useNip04
            )
        } finally {
            Nip46AuthBridge.dismissAuthUrl()
            relays.forEach { it.forceDisconnect() }
            clientPriv.fill(0)
            clientKeypair.wipe()
            scope.cancel()
        }
    }

    /**
     * Waits for a remote signer to accept a client-initiated `nostrconnect://` URI.
     * Validates that the incoming `result` strictly matches [secret] (per NIP-46 spec),
     * captures the remote signer's pubkey, and calls `get_public_key`.
     */
    suspend fun awaitNostrConnect(
        clientKeypair: Keys.Keypair,
        relaysList: List<String>,
        secret: String,
        timeoutMs: Long = 180_000L,
        onRelaysReady: () -> Unit = {},
        onStatus: (String) -> Unit = {}
    ): Nip46.BunkerSession {
        val clientPriv = clientKeypair.privkey.copyOf()
        val clientPubHex = clientKeypair.pubkey.toHex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relays = mutableListOf<Relay>()
        val handshakeDeferred = CompletableDeferred<Nip46.NostrConnectHandshake>()
        val pairedRemotePubkey = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Nip46.RpcResponse, Boolean>>>()
        val subId = "nip46-nc-${clientPubHex.take(8)}"
        // Fixed lookback anchor so foreground-resume reconnects after app-switching
        // to Amethyst/Amber still catch the signer's published response event.
        val sessionStartSec = (System.currentTimeMillis() / 1000) - 15

        try {
            if (relaysList.none { RelayConfig.isLocalRelayUrl(it) } && TorPreferences.isEnabled()) {
                onStatus("Waiting for Tor to connect…")
                if (!TorManager.awaitOnIfEnabled()) {
                    throw BunkerSignerException("Tor failed to bootstrap — disable Tor or retry")
                }
            }
            val connectTimeoutMs = if (TorPreferences.isEnabled()) 30_000L else 15_000L
            onStatus("Connecting to Nostr Connect relays…")
            val anyConnected = CompletableDeferred<Boolean>()
            for (url in relaysList) {
                val relay = Relay(RelayConfig(url, read = true, write = true), clientProviderFor(url), scope)
                relay.autoReconnect = true
                relays.add(relay)
                scope.launch {
                    relay.messages.collect { msg ->
                        if (msg is RelayMessage.EventMsg && msg.event.kind == Nip46.KIND_NOSTR_CONNECT) {
                            val remotePk = pairedRemotePubkey.get()
                            if (remotePk == null) {
                                val hs = Nip46.parseNostrConnectHandshake(
                                    event = msg.event,
                                    clientPrivkey = clientPriv,
                                    expectedSecret = secret
                                ) ?: return@collect
                                if (pairedRemotePubkey.compareAndSet(null, hs.remoteSignerPubkeyHex)) {
                                    handshakeDeferred.complete(hs)
                                }
                                return@collect
                            } else if (msg.event.pubkey.equals(remotePk, ignoreCase = true)) {
                                val respPair = Nip46.parseResponseEvent(
                                    event = msg.event,
                                    clientPrivkey = clientPriv,
                                    expectedSignerPubkeyHex = remotePk
                                ) ?: return@collect
                                pending.remove(respPair.first.id)?.complete(respPair)
                            }
                        }
                    }
                }
                scope.launch {
                    relay.connectionState.collect { connected ->
                        if (connected) {
                            val filter = Filter(
                                kinds = listOf(Nip46.KIND_NOSTR_CONNECT),
                                pTags = listOf(clientPubHex),
                                since = sessionStartSec
                            )
                            relay.send(ClientMessage.req(subId, filter))
                            anyConnected.complete(true)
                        }
                    }
                }
                relay.connect()
            }

            if (withTimeoutOrNull(connectTimeoutMs) { anyConnected.await() } != true) {
                throw BunkerSignerException("Could not connect to Nostr Connect relay(s)")
            }

            // Brief settle so additional relays finish subscribing before launching the signer app
            kotlinx.coroutines.delay(500)
            onRelaysReady()
            onStatus("Waiting for signer approval…")
            val handshake = withTimeoutOrNull(timeoutMs) {
                handshakeDeferred.await()
            } ?: throw BunkerSignerException("Timed out waiting for remote signer")

            val remoteSignerPubkey = handshake.remoteSignerPubkeyHex
            val usedNip04 = handshake.usedNip04

            // If a legacy signer initiated with a `connect` request, reply with `ack`
            handshake.requestIdToAck?.let { reqId ->
                val ackEvent = Nip46.buildAckResponseEvent(
                    clientPrivkey = clientPriv,
                    remoteSignerPubkeyHex = remoteSignerPubkey,
                    requestId = reqId,
                    useNip04 = usedNip04
                )
                val ackMsg = ClientMessage.event(ackEvent)
                relays.forEach { it.send(ackMsg) }
            }

            onStatus("Fetching account public key…")
            val pkReqId = Nip46.generateRequestId()
            val pkDeferred = CompletableDeferred<Pair<Nip46.RpcResponse, Boolean>>()
            pending[pkReqId] = pkDeferred
            val pkEvent = Nip46.buildRequestEvent(
                clientPrivkey = clientPriv,
                remoteSignerPubkeyHex = remoteSignerPubkey,
                request = Nip46.RpcRequest(id = pkReqId, method = "get_public_key", params = emptyList()),
                useNip04 = usedNip04
            )
            val pkMsg = ClientMessage.event(pkEvent)
            relays.forEach { relay ->
                if (!relay.isConnected) relay.connect()
                relay.send(pkMsg)
            }

            val (pkResp, finalNip04) = (withTimeoutOrNull(8_000L) { pkDeferred.await() }
                ?: run {
                    // Retry once in case sockets reconnected after returning from the signer app
                    relays.forEach { relay ->
                        if (!relay.isConnected) relay.connect()
                        relay.send(pkMsg)
                    }
                    withTimeoutOrNull(20_000L) { pkDeferred.await() }
                })
                ?: throw BunkerSignerException("Timed out fetching public key from signer")
            if (!pkResp.error.isNullOrBlank()) {
                throw BunkerSignerException(pkResp.error)
            }
            val userPubkeyHex = pkResp.result?.trim()?.lowercase()
                ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
                ?: throw BunkerSignerException("Remote signer returned invalid public key")

            return Nip46.BunkerSession(
                userPubkeyHex = userPubkeyHex,
                remoteSignerPubkeyHex = remoteSignerPubkey,
                clientPrivkeyHex = clientPriv.toHex(),
                relays = relaysList,
                useNip04 = usedNip04 || finalNip04
            )
        } finally {
            relays.forEach { it.forceDisconnect() }
            clientPriv.fill(0)
            scope.cancel()
        }
    }
}
