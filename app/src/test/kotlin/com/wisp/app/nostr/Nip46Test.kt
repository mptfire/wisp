package com.wisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip46Test {

    private val signerPubkeyHex = "a".repeat(64)

    @Test
    fun parsesBunkerUriWithMultipleRelaysAndSecret() {
        val uri = "bunker://$signerPubkeyHex?relay=wss%3A%2F%2Frelay.nsec.app&relay=wss://relay.damus.io&secret=mysecret123"
        val parsed = Nip46.parseBunkerUri(uri)
        assertNotNull(parsed)
        assertEquals(signerPubkeyHex, parsed!!.remoteSignerPubkey)
        assertEquals(listOf("wss://relay.nsec.app", "wss://relay.damus.io"), parsed.relays)
        assertEquals("mysecret123", parsed.secret)
    }

    @Test
    fun parsesBunkerUriWithNostrPrefixAndLocalRelay() {
        val uri = "nostr:bunker://${signerPubkeyHex.uppercase()}?relay=ws://127.0.0.1:4869"
        val parsed = Nip46.parseBunkerUri(uri)
        assertNotNull(parsed)
        assertEquals(signerPubkeyHex, parsed!!.remoteSignerPubkey)
        assertEquals(listOf("ws://127.0.0.1:4869"), parsed.relays)
        assertNull(parsed.secret)
    }

    @Test
    fun rejectsInvalidBunkerUris() {
        assertNull(Nip46.parseBunkerUri("bunker://short?relay=wss://relay.nsec.app"))
        assertNull(Nip46.parseBunkerUri("bunker://$signerPubkeyHex"))
        assertNull(Nip46.parseBunkerUri("bunker://$signerPubkeyHex?relay=http://evil.com"))
        assertNull(Nip46.parseBunkerUri("bunker://$signerPubkeyHex?relay=ws://evil.com:80"))
        assertNull(Nip46.parseBunkerUri("nsec1something"))
    }

    @Test
    fun buildsValidNostrConnectUri() {
        val clientPub = "b".repeat(64)
        val uri = Nip46.buildNostrConnectUri(
            clientPubkeyHex = clientPub,
            relays = listOf("wss://relay.nsec.app", "wss://relay.damus.io"),
            secret = "sec1234",
            appName = "Fire Wisp",
            appUrl = "https://firewisp.de"
        )
        assertTrue(uri.startsWith("nostrconnect://$clientPub?"))
        assertTrue(uri.contains("relay=wss%3A%2F%2Frelay.nsec.app"))
        assertTrue(uri.contains("relay=wss%3A%2F%2Frelay.damus.io"))
        assertTrue(uri.contains("secret=sec1234"))
        assertTrue(uri.contains("name=Fire"))
        assertTrue(uri.contains("perms="))
    }

    @Test
    fun nip44RequestAndResponseRoundTripVerifiesSender() {
        val clientKeys = Keys.generate()
        val remoteKeys = Keys.generate()
        val remotePubHex = remoteKeys.pubkey.toHex()
        val clientPubHex = clientKeys.pubkey.toHex()

        val req = Nip46.RpcRequest(
            id = "req-1",
            method = "connect",
            params = listOf(remotePubHex, "topsecret", "sign_event:1")
        )
        val reqEvent = Nip46.buildRequestEvent(clientKeys.privkey, remotePubHex, req, useNip04 = false)
        assertEquals(Nip46.KIND_NOSTR_CONNECT, reqEvent.kind)
        assertEquals(clientPubHex, reqEvent.pubkey)
        assertTrue(reqEvent.tags.any { it.size >= 2 && it[0] == "p" && it[1] == remotePubHex })

        // Remote signer decrypts the request using NIP-44
        val (decryptedReq, usedNip04) = Nip46.decryptPayload(reqEvent.content, remoteKeys.privkey, clientPubHex)
        assertFalse(usedNip04)
        val reqJson = Json.parseToJsonElement(decryptedReq).jsonObject
        assertEquals("req-1", reqJson["id"]?.jsonPrimitive?.content)
        assertEquals("connect", reqJson["method"]?.jsonPrimitive?.content)
        assertEquals("topsecret", reqJson["params"]?.jsonArray?.get(1)?.jsonPrimitive?.content)

        // Remote signer sends back an encrypted response
        val respJson = """{"id":"req-1","result":"ack"}"""
        val respCipher = Nip46.encryptPayload(respJson, remoteKeys.privkey, clientPubHex, useNip04 = false)
        val respEvent = NostrEvent.create(
            privkey = remoteKeys.privkey,
            pubkey = remoteKeys.pubkey,
            kind = Nip46.KIND_NOSTR_CONNECT,
            content = respCipher,
            tags = listOf(listOf("p", clientPubHex))
        )

        val parsed = Nip46.parseResponseEvent(respEvent, clientKeys.privkey, expectedSignerPubkeyHex = remotePubHex)
        assertNotNull(parsed)
        assertEquals("req-1", parsed!!.first.id)
        assertEquals("ack", parsed.first.result)
        assertFalse(parsed.second)

        // Spoofed response from a different pubkey MUST be rejected
        val attackerKeys = Keys.generate()
        val spoofCipher = Nip46.encryptPayload(respJson, attackerKeys.privkey, clientPubHex, useNip04 = false)
        val spoofEvent = NostrEvent.create(
            privkey = attackerKeys.privkey,
            pubkey = attackerKeys.pubkey,
            kind = Nip46.KIND_NOSTR_CONNECT,
            content = spoofCipher,
            tags = listOf(listOf("p", clientPubHex))
        )
        assertNull(Nip46.parseResponseEvent(spoofEvent, clientKeys.privkey, expectedSignerPubkeyHex = remotePubHex))
    }

    @Test
    fun supportsLegacyNip04BunkerResponseFallback() {
        val clientKeys = Keys.generate()
        val remoteKeys = Keys.generate()
        val clientPubHex = clientKeys.pubkey.toHex()
        val remotePubHex = remoteKeys.pubkey.toHex()

        val respJson = """{"id":"req-04","result":"$remotePubHex"}"""
        val nip04Cipher = Nip46.encryptPayload(respJson, remoteKeys.privkey, clientPubHex, useNip04 = true)
        assertTrue(nip04Cipher.contains("?iv="))
        val respEvent = NostrEvent.create(
            privkey = remoteKeys.privkey,
            pubkey = remoteKeys.pubkey,
            kind = Nip46.KIND_NOSTR_CONNECT,
            content = nip04Cipher,
            tags = listOf(listOf("p", clientPubHex))
        )

        val parsed = Nip46.parseResponseEvent(respEvent, clientKeys.privkey, expectedSignerPubkeyHex = remotePubHex)
        assertNotNull(parsed)
        assertEquals("req-04", parsed!!.first.id)
        assertEquals(remotePubHex, parsed.first.result)
        assertTrue(parsed.second)
    }

    @Test
    fun authChallengeValidationAllowsOnlySafeHttpsUrls() {
        assertEquals(
            "https://nsec.app/confirm/abc123",
            Nip46.extractSafeAuthUrl(Nip46.RpcResponse(id = "1", result = "auth_url", error = "https://nsec.app/confirm/abc123"))
        )
        assertNull(Nip46.extractSafeAuthUrl(Nip46.RpcResponse(id = "1", result = "auth_url", error = "http://nsec.app/confirm")))
        assertNull(Nip46.extractSafeAuthUrl(Nip46.RpcResponse(id = "1", result = "auth_url", error = "javascript:alert(1)")))
        assertNull(Nip46.extractSafeAuthUrl(Nip46.RpcResponse(id = "1", result = "auth_url", error = "https://127.0.0.1/steal")))
        assertNull(Nip46.extractSafeAuthUrl(Nip46.RpcResponse(id = "1", result = "ack", error = "https://nsec.app/confirm")))
    }

    @Test
    fun parsesBothModernResponseAndLegacyRequestNostrConnectHandshakes() {
        val clientKeys = Keys.generate()
        val remoteKeys = Keys.generate()
        val clientPubHex = clientKeys.pubkey.toHex()
        val remotePubHex = remoteKeys.pubkey.toHex()
        val secret = "pair-secret-42"

        // Modern NIP-46 (Amethyst / Amber): BunkerResponse with result == secret
        val modernJson = """{"id":"ack1","result":"$secret"}"""
        val modernEvent = NostrEvent.create(
            privkey = remoteKeys.privkey,
            pubkey = remoteKeys.pubkey,
            kind = Nip46.KIND_NOSTR_CONNECT,
            content = Nip46.encryptPayload(modernJson, remoteKeys.privkey, clientPubHex),
            tags = listOf(listOf("p", clientPubHex))
        )
        val modernHs = Nip46.parseNostrConnectHandshake(modernEvent, clientKeys.privkey, secret)
        assertNotNull(modernHs)
        assertEquals(remotePubHex, modernHs!!.remoteSignerPubkeyHex)
        assertNull(modernHs.requestIdToAck)
        assertFalse(modernHs.usedNip04)

        // Wrong secret MUST be rejected
        assertNull(Nip46.parseNostrConnectHandshake(modernEvent, clientKeys.privkey, "wrong-secret"))

        // Legacy NIP-46: BunkerRequest(method="connect", params=[pubkey, secret])
        val legacyJson = """{"id":"req99","method":"connect","params":["$remotePubHex","$secret"]}"""
        val legacyEvent = NostrEvent.create(
            privkey = remoteKeys.privkey,
            pubkey = remoteKeys.pubkey,
            kind = Nip46.KIND_NOSTR_CONNECT,
            content = Nip46.encryptPayload(legacyJson, remoteKeys.privkey, clientPubHex),
            tags = listOf(listOf("p", clientPubHex))
        )
        val legacyHs = Nip46.parseNostrConnectHandshake(legacyEvent, clientKeys.privkey, secret)
        assertNotNull(legacyHs)
        assertEquals(remotePubHex, legacyHs!!.remoteSignerPubkeyHex)
        assertEquals("req99", legacyHs.requestIdToAck)
    }
}
