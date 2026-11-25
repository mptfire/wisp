package com.wisp.app.relay

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wisp.app.repo.KeyRepository
import com.wisp.app.viewmodel.RelayViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalRelayConfigTest {

    @Test
    fun acceptsCitrineLoopbackAndPrivateLanRelays() {
        val valid = listOf(
            "ws://127.0.0.1:4869",
            "ws://localhost:4869",
            "ws://localhost:7777",
            "ws://127.0.0.1:4869/",
            "ws://[::1]:4869",
            "ws://192.168.1.50:4848",
            "ws://10.0.0.5:7777",
            "ws://172.16.0.10:8080",
            "ws://172.31.255.254:8080",
            "ws://umbrel.local:4848",
            "wss://192.168.1.50:4848"
        )
        for (url in valid) {
            assertTrue("Expected local relay URL to be accepted: $url", RelayConfig.isLocalRelayUrl(url))
        }
    }

    @Test
    fun rejectsPublicClearnetAndInvalidLocalUrls() {
        val invalid = listOf(
            "ws://relay.damus.io",
            "ws://relay.damus.io:443",
            "ws://evil.com:80",
            "ws://169.254.169.254:80",
            "ws://172.15.0.1:8080",
            "ws://172.32.0.1:8080",
            "ws://192.168.999.1:8080",
            "ws://user:pass@127.0.0.1:4869",
            "http://127.0.0.1:4869",
            "https://127.0.0.1:4869",
            "ws://127.0.0.1:99999",
            "ws://8.8.8.8:4869"
        )
        for (url in invalid) {
            assertFalse("Expected URL to be rejected as local relay: $url", RelayConfig.isLocalRelayUrl(url))
        }
    }

    @Test
    fun generalRelayValidationStillRejectsWsAndLoopback() {
        assertFalse(RelayConfig.isValidUrl("ws://127.0.0.1:4869"))
        assertFalse(RelayConfig.isValidUrl("ws://192.168.1.10:4848"))
        assertFalse(RelayConfig.isValidUrl("wss://127.0.0.1:4869"))
        assertFalse(RelayConfig.isValidUrl("wss://localhost"))
        assertTrue(RelayConfig.isValidUrl("wss://relay.firewisp.de"))
    }

    @Test
    fun localRelayClientRefusesPublicHostsEvenWhenCalledDirectly() {
        val client = HttpClientFactory.getLocalRelayClient()
        try {
            client.newCall(Request.Builder().url("https://relay.damus.io").build()).execute()
            fail("Local relay client must reject public host")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("local", ignoreCase = true))
        }
    }

    @Test
    fun localRelayPersistsAcrossReloadAndAccountSwitch() {
        val app = RuntimeEnvironment.getApplication() as Application
        Mockito.mockConstruction(MasterKey.Builder::class.java) { builder, _ ->
            Mockito.`when`(builder.setKeyScheme(Mockito.any())).thenReturn(builder)
            Mockito.`when`(builder.build()).thenReturn(Mockito.mock(MasterKey::class.java))
        }.use {
            Mockito.mockStatic(EncryptedSharedPreferences::class.java).use { enc ->
                enc.`when`<SharedPreferences> {
                    EncryptedSharedPreferences.create(
                        Mockito.any<Context>(), Mockito.anyString(), Mockito.any<MasterKey>(),
                        Mockito.any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                        Mockito.any<EncryptedSharedPreferences.PrefValueEncryptionScheme>()
                    )
                }.thenReturn(app.getSharedPreferences("stub_enc_prefs_local_relay", Context.MODE_PRIVATE))

                val pubkey = "d".repeat(64)
                val repo = KeyRepository(app)
                repo.savePubkeyReadOnly(pubkey)
                repo.reloadPrefs(pubkey)

                val cfg = LocalRelayConfig(
                    url = RelayConfig.CITRINE_DEFAULT_URL,
                    enabled = true,
                    writePolicy = LocalRelayWritePolicy.TAGGED,
                    kinds = setOf(1, 1059, 9735)
                )
                repo.saveLocalRelay(cfg)
                assertEquals(cfg, repo.getLocalRelay())

                // Reloading prefs must preserve local_relay (not wipe it!)
                repo.reloadPrefs(pubkey)
                assertNotNull(repo.getLocalRelay())
                assertEquals(cfg, repo.getLocalRelay())

                // Removing clears it cleanly
                repo.saveLocalRelay(null)
                assertNull(repo.getLocalRelay())
            }
        }
    }

    /** Minimal RFC 6455 loopback WebSocket server for testing real local relay sockets. */
    private class LoopbackWsServer(port: Int = 0) : AutoCloseable {
        private val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val url: String get() = "ws://127.0.0.1:$port"
        @Volatile private var running = true
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())

        private val acceptThread = thread(isDaemon = true, name = "test-ws-server") {
            while (running) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                sockets.add(client)
                thread(isDaemon = true, name = "test-ws-client") {
                    try {
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        var wsKey: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                                wsKey = line.substringAfter(":").trim()
                            }
                        }
                        if (wsKey != null) {
                            val magic = wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                            val accept = Base64.getEncoder().encodeToString(
                                MessageDigest.getInstance("SHA-1").digest(magic.toByteArray(Charsets.UTF_8))
                            )
                            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: $accept\r\n\r\n"
                            client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                            client.getOutputStream().flush()
                            // Keep socket open until closed
                            while (running && client.getInputStream().read() != -1) {
                                // drain frames
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }

        override fun close() {
            running = false
            runCatching { server.close() }
            synchronized(sockets) {
                sockets.forEach { runCatching { it.close() } }
                sockets.clear()
            }
            acceptThread.join(1000)
        }
    }

    @Test
    fun localRelayStatusTransitionsReactivelyToConnectedWithTorOnAndOff(): Unit = runBlocking {
        LoopbackWsServer().use { server ->
            val pool = RelayPool()
            try {
                // Start with Tor Pending (fail-closed) — local relay must still connect directly
                HttpClientFactory.setTorSocks(HttpClientFactory.TorSocks.Pending)
                assertEquals(LocalRelayStatus.Disabled, pool.localRelayStatus.value)

                pool.updateLocalRelay(LocalRelayConfig(url = server.url, enabled = true), "a".repeat(64))
                val connected = withTimeout(5_000) {
                    pool.localRelayStatus.first { it is LocalRelayStatus.Connected }
                }
                assertEquals(LocalRelayStatus.Connected, connected)
                assertTrue(pool.isRelayConnected(server.url))

                // Now toggle Tor OFF (suspendForTorSwitch + resumeAfterTorSwitch)
                HttpClientFactory.setTorSocks(HttpClientFactory.TorSocks.Off)
                pool.suspendForTorSwitch()
                pool.resumeAfterTorSwitch()

                val afterTorOff = withTimeout(5_000) {
                    pool.localRelayStatus.first { it is LocalRelayStatus.Connected }
                }
                assertEquals(LocalRelayStatus.Connected, afterTorOff)
                assertTrue(pool.isRelayConnected(server.url))
            } finally {
                pool.disconnectAll()
                HttpClientFactory.setTorSocks(HttpClientFactory.TorSocks.Off)
            }
        }
    }

    @Test
    fun localRelaySurvivesPriorEphemeralEntryAcrossTorToggle(): Unit = runBlocking {
        LoopbackWsServer().use { server ->
            val pool = RelayPool()
            try {
                // Simulate selecting local relay from feed picker before enabling it in settings
                pool.preConnectEphemeral(server.url)
                pool.updateLocalRelay(LocalRelayConfig(url = server.url, enabled = true), "b".repeat(64))

                withTimeout(5_000) {
                    pool.localRelayStatus.first { it is LocalRelayStatus.Connected }
                }

                // Toggling Tor off invokes reconnectAll(), which evicts ephemeralRelays.
                // Local relay must NOT be evicted from relayIndex or disconnected.
                pool.suspendForTorSwitch()
                pool.resumeAfterTorSwitch()

                assertEquals(LocalRelayStatus.Connected, pool.localRelayStatus.value)
                assertTrue(pool.isRelayConnected(server.url))
            } finally {
                pool.disconnectAll()
            }
        }
    }

    @Test
    fun localRelaySurfacesErrorWhenUnreachableAndRecoversOnReconnect(): Unit = runBlocking {
        // Reserve a free port then immediately close it so connection is refused
        val freePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val url = "ws://127.0.0.1:$freePort"
        val pool = RelayPool()
        try {
            pool.updateLocalRelay(LocalRelayConfig(url = url, enabled = true), "c".repeat(64))
            val error = withTimeout(5_000) {
                pool.localRelayStatus.first { it is LocalRelayStatus.Error }
            }
            assertTrue(error is LocalRelayStatus.Error)
            assertFalse(pool.isRelayConnected(url))

            // Now bring up the local relay on the same port and trigger manual reconnect
            LoopbackWsServer(port = freePort).use {
                pool.reconnectLocalRelay()
                val connected = withTimeout(5_000) {
                    pool.localRelayStatus.first { it is LocalRelayStatus.Connected }
                }
                assertEquals(LocalRelayStatus.Connected, connected)
                assertTrue(pool.isRelayConnected(url))
            }
        } finally {
            pool.disconnectAll()
        }
    }

    @Test
    fun relayViewModelUpdatesRelayPoolDirectlyWithoutStartupCoordinator(): Unit = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Application
        LoopbackWsServer().use { server ->
            Mockito.mockConstruction(MasterKey.Builder::class.java) { builder, _ ->
                Mockito.`when`(builder.setKeyScheme(Mockito.any())).thenReturn(builder)
                Mockito.`when`(builder.build()).thenReturn(Mockito.mock(MasterKey::class.java))
            }.use {
                Mockito.mockStatic(EncryptedSharedPreferences::class.java).use { enc ->
                    enc.`when`<SharedPreferences> {
                        EncryptedSharedPreferences.create(
                            Mockito.any<Context>(), Mockito.anyString(), Mockito.any<MasterKey>(),
                            Mockito.any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                            Mockito.any<EncryptedSharedPreferences.PrefValueEncryptionScheme>()
                        )
                    }.thenReturn(app.getSharedPreferences("stub_enc_prefs_vm_local", Context.MODE_PRIVATE))

                    val pool = RelayPool()
                    try {
                        val vm = RelayViewModel(app)
                        vm.relayPool = pool

                        assertTrue(vm.setLocalRelayUrl(server.url))
                        val connected = withTimeout(5_000) {
                            pool.localRelayStatus.first { it is LocalRelayStatus.Connected }
                        }
                        assertEquals(LocalRelayStatus.Connected, connected)

                        vm.toggleLocalRelayEnabled()
                        assertEquals(LocalRelayStatus.Disabled, pool.localRelayStatus.value)

                        vm.toggleLocalRelayEnabled()
                        val reconnected = withTimeout(5_000) {
                            pool.localRelayStatus.first { it is LocalRelayStatus.Connected }
                        }
                        assertEquals(LocalRelayStatus.Connected, reconnected)

                        vm.selectTab(RelaySetType.LOCAL)
                        vm.removeRelay(server.url)
                        assertEquals(LocalRelayStatus.Disabled, pool.localRelayStatus.value)
                    } finally {
                        pool.disconnectAll()
                    }
                }
            }
        }
    }

    @Test
    fun localRelayClientConfiguresBoundedHandshakeReadTimeoutAndCleartext() {
        val client = HttpClientFactory.getLocalRelayClient()
        assertTrue("Local relay client must have bounded connectTimeout", client.connectTimeoutMillis in 1..10_000)
        assertTrue("Local relay client must have bounded readTimeout so hung handshakes cannot stall forever", client.readTimeoutMillis in 1..15_000)
        assertTrue("Local relay client must explicitly allow CLEARTEXT", client.connectionSpecs.any { !it.isTls })
    }

    @Test
    fun localRelayRecoversAfterServerClosesWithCode1000(): Unit = runBlocking {
        // Simulates Citrine restarting its Ktor server (sends WebSocket Close 1000)
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val url = "ws://127.0.0.1:$port"
        val acceptCount = java.util.concurrent.atomic.AtomicInteger(0)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sockets = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())

        val acceptThread = thread(isDaemon = true, name = "test-close1000-server") {
            while (running.get()) {
                val client = try { server.accept() } catch (_: Exception) { break }
                sockets.add(client)
                val attempt = acceptCount.incrementAndGet()
                thread(isDaemon = true) {
                    try {
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        var wsKey: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                                wsKey = line.substringAfter(":").trim()
                            }
                        }
                        if (wsKey != null) {
                            val magic = wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                            val accept = Base64.getEncoder().encodeToString(
                                MessageDigest.getInstance("SHA-1").digest(magic.toByteArray(Charsets.UTF_8))
                            )
                            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: $accept\r\n\r\n"
                            val out = client.getOutputStream()
                            out.write(response.toByteArray(Charsets.UTF_8))
                            out.flush()
                            if (attempt == 1) {
                                // Send RFC 6455 Close frame with code 1000 ("server restart")
                                Thread.sleep(100)
                                val reason = "restart".toByteArray(Charsets.UTF_8)
                                val frame = ByteArray(2 + 2 + reason.size)
                                frame[0] = 0x88.toByte() // FIN + Close opcode
                                frame[1] = (2 + reason.size).toByte()
                                frame[2] = 0x03.toByte() // 1000 >> 8
                                frame[3] = 0xE8.toByte() // 1000 & 0xFF
                                System.arraycopy(reason, 0, frame, 4, reason.size)
                                out.write(frame)
                                out.flush()
                                Thread.sleep(150)
                                client.close()
                            } else {
                                // Second connection stays open
                                while (running.get() && client.getInputStream().read() != -1) {}
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }

        val pool = RelayPool()
        try {
            pool.updateLocalRelay(LocalRelayConfig(url = url, enabled = true), "e".repeat(64))
            // First connect succeeds
            withTimeout(5_000) {
                pool.localRelayStatus.first { it is LocalRelayStatus.Connected }
            }
            // Server sends Close 1000 — status must leave Connected (surface error)
            val afterClose = withTimeout(5_000) {
                pool.localRelayStatus.first { it !is LocalRelayStatus.Connected }
            }
            assertTrue("Expected error status on server close, got $afterClose", afterClose is LocalRelayStatus.Error)

            // Local relay must automatically reconnect even after Close 1000 (within ~3s retry)
            val reconnected = withTimeout(8_000) {
                pool.localRelayStatus.first { it is LocalRelayStatus.Connected }
            }
            assertEquals(LocalRelayStatus.Connected, reconnected)
            assertTrue("Expected at least 2 connections, got ${acceptCount.get()}", acceptCount.get() >= 2)
        } finally {
            running.set(false)
            pool.disconnectAll()
            runCatching { server.close() }
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            acceptThread.join(1000)
        }
    }

    @Test
    fun relayConnectSurfacesSynchronousProviderExceptionInsteadOfHanging(): Unit = runBlocking {
        val relay = Relay(
            config = RelayConfig("ws://127.0.0.1:4869"),
            clientProvider = { throw IllegalStateException("simulated client failure") }
        )
        val receivedFailure = java.util.concurrent.CompletableFuture<RelayFailure>()
        relay.onFailureEvent = { receivedFailure.complete(it) }
        relay.connect()
        val failure = withTimeout(5_000) { receivedFailure.get() }
        assertTrue(failure.message.contains("simulated client failure"))
    }

    @Test
    fun localRelayResolvesLocalhostToIpv4LoopbackAndStaysConnectedWhenIdle(): Unit = runBlocking {
        LoopbackWsServer().use { server ->
            val localhostUrl = "ws://localhost:${server.port}"
            // Clone local client with 1s readTimeout to prove OkHttp only applies readTimeout
            // to the HTTP 101 handshake and never drops an idle open WebSocket.
            val shortTimeoutClient = HttpClientFactory.getLocalRelayClient().newBuilder()
                .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val opened = java.util.concurrent.CompletableFuture<Boolean>()
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            val relay = Relay(RelayConfig(localhostUrl), { shortTimeoutClient })
            relay.onConnectionStateChanged = { if (it) opened.complete(true) }
            relay.onFailureEvent = { failed.set(true) }
            try {
                relay.connect()
                assertTrue(withTimeout(5_000) { opened.get() })
                kotlinx.coroutines.delay(1_500)
                assertTrue("Idle WebSocket should stay connected past handshake readTimeout", relay.isConnected)
                assertFalse("Idle WebSocket should not fail on readTimeout", failed.get())
            } finally {
                relay.forceDisconnect()
            }
        }
    }
}
