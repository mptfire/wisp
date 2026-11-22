package com.wisp.app.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip49Test {
    // Official NIP-49 test vector (log_n = 16, password "nostr").
    private val officialVector =
        "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"
    private val officialPrivkeyHex =
        "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683"

    @Test
    fun decryptsOfficialSpecVector() = runBlocking<Unit> {
        val privkey = Nip49.decrypt(officialVector, "nostr")
        assertEquals(officialPrivkeyHex, privkey.toHex())
    }

    @Test
    fun encryptDecryptRoundTrip() = runBlocking<Unit> {
        val privkey = ByteArray(32) { it.toByte() }
        val ncryptsec = Nip49.encrypt(privkey, "correct horse battery staple", logN = Nip49.MIN_LOG_N)
        assertTrue(ncryptsec.startsWith("ncryptsec1"))
        val back = Nip49.decrypt(ncryptsec, "correct horse battery staple")
        assertArrayEquals(privkey, back)
    }

    @Test
    fun rejectsWrongPassphrase() = runBlocking<Unit> {
        val ncryptsec = Nip49.encrypt(ByteArray(32) { 7 }, "hunter2", logN = Nip49.MIN_LOG_N)
        try {
            Nip49.decrypt(ncryptsec, "hunter3")
            throw AssertionError("expected wrong passphrase to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("wrong passphrase"))
        }
    }

    @Test
    fun rejectsTamperedCiphertext() = runBlocking<Unit> {
        val ncryptsec = Nip49.encrypt(ByteArray(32) { 1 }, "pw", logN = Nip49.MIN_LOG_N)
        val payload = Nip49.ncryptsecDecode(ncryptsec)
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0x01).toByte()
        val tampered = Nip49.ncryptsecEncode(payload)
        try {
            Nip49.decrypt(tampered, "pw")
            throw AssertionError("expected tampered ciphertext to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("wrong passphrase"))
        }
    }

    @Test
    fun rejectsTamperedVersionByte() = runBlocking<Unit> {
        val ncryptsec = Nip49.encrypt(ByteArray(32) { 2 }, "pw", logN = Nip49.MIN_LOG_N)
        val payload = Nip49.ncryptsecDecode(ncryptsec)
        payload[0] = 0x01
        assertThrows(IllegalArgumentException::class.java) {
            Nip49.decryptPrivkey(payload, "pw")
        }
    }

    @Test
    fun unicodeNfkcPasswordEquivalence() = runBlocking<Unit> {
        // U+FB01 LATIN SMALL LIGATURE FI normalizes (NFKC) to "fi".
        val ligature = "\uFB01le"
        val ascii = "file"
        val ncryptsec = Nip49.encrypt(ByteArray(32) { 9 }, ligature, logN = Nip49.MIN_LOG_N)
        val back = Nip49.decrypt(ncryptsec, ascii)
        assertArrayEquals(ByteArray(32) { 9 }, back)
    }

    @Test
    fun rejectsLogNOutOfRangeOnEncrypt() = runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java) {
            Nip49.encryptPrivkey(ByteArray(32), "pw", logN = Nip49.MAX_LOG_N + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Nip49.encryptPrivkey(ByteArray(32), "pw", logN = Nip49.MIN_LOG_N - 1)
        }
    }

    @Test
    fun rejectsHostileLogNByteOnDecrypt() = runBlocking<Unit> {
        // Crafted payload claims log_n = 24 (OOM/CPU DoS attempt) - must be
        // rejected before scrypt ever runs.
        val ncryptsec = Nip49.encrypt(ByteArray(32) { 3 }, "pw", logN = Nip49.MIN_LOG_N)
        val payload = Nip49.ncryptsecDecode(ncryptsec)
        payload[1] = 24
        val e = assertThrows(IllegalArgumentException::class.java) {
            Nip49.decryptPrivkey(payload, "pw")
        }
        assertTrue(e.message!!.contains("log_n"))
    }

    @Test
    fun rejectsWrongBech32Hrp() = runBlocking<Unit> {
        val nsec = Nip19.nsecEncode(ByteArray(32))
        assertThrows(IllegalArgumentException::class.java) {
            Nip49.ncryptsecDecode(nsec)
        }
    }
}
