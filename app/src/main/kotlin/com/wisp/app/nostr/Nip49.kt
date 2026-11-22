package com.wisp.app.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import java.text.Normalizer

/******************************************************************
 * NIP-49: Private Key Encryption (ncryptsec1...), version 0x02.
 *
 * payload = [0x02][logN][salt(16)][nonce(24)][keySecurity][ct(48)] (91 B)
 * key     = scrypt(NFKC(password), salt, N=2^logN, r=8, p=1, 32)
 * ct      = XChaCha20-Poly1305(key, nonce, privkey, aad=[keySecurity])
 * XChaCha20-Poly1305 = HChaCha20 subkey + IETF AEAD (00000000||nonce[16..24]).
 * BC 1.78 has no XChaCha engine, so HChaCha20 lives here.
 ******************************************************************/
object Nip49 {
    const val VERSION: Int = 0x02
    const val KEY_SECURITY_XCHACHA: Int = 0x02
    const val MIN_LOG_N = 12
    const val MAX_LOG_N = 20
    const val DEFAULT_LOG_N = 16

    private const val SALT_LEN = 16
    private const val NONCE_LEN = 24
    private const val PAYLOAD_LEN = 91 // 1 + 1 + 16 + 24 + 1 + 48

    private val random = SecureRandom()

    fun encryptPrivkey(privkey: ByteArray, password: String, logN: Int = DEFAULT_LOG_N): ByteArray {
        require(privkey.size == 32) { "private key must be 32 bytes" }
        require(logN in MIN_LOG_N..MAX_LOG_N) { "log_n must be between $MIN_LOG_N and $MAX_LOG_N (got $logN)" }
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val pw = nfkcPassword(password)
        val key = SCrypt.generate(pw, salt, 1 shl logN, 8, 1, 32)
        pw.fill(0)
        val ciphertext = xchacha20poly1305(key, nonce, privkey, byteArrayOf(KEY_SECURITY_XCHACHA.toByte()), true)
        key.fill(0)
        val out = ByteArray(PAYLOAD_LEN)
        out[0] = VERSION.toByte()
        out[1] = logN.toByte()
        salt.copyInto(out, 2)
        nonce.copyInto(out, 2 + SALT_LEN)
        out[42] = KEY_SECURITY_XCHACHA.toByte()
        ciphertext.copyInto(out, 43)
        ciphertext.fill(0)
        return out
    }

    fun decryptPrivkey(payload: ByteArray, password: String): ByteArray {
        require(payload.size == PAYLOAD_LEN) { "invalid ncryptsec payload length ${payload.size} (expected $PAYLOAD_LEN)" }
        val version = payload[0].toInt() and 0xFF
        require(version == VERSION) { "unsupported ncryptsec version $version" }
        val logN = payload[1].toInt() and 0xFF
        require(logN in MIN_LOG_N..MAX_LOG_N) { "log_n $logN out of allowed range $MIN_LOG_N..$MAX_LOG_N" }
        val salt = payload.copyOfRange(2, 2 + SALT_LEN)
        val nonce = payload.copyOfRange(2 + SALT_LEN, 2 + SALT_LEN + NONCE_LEN)
        // The stored key security byte IS the AEAD associated data. The spec
        // allows 0x00, 0x01 and 0x02 - the official test vector uses 0x00.
        val keySecurity = payload[42].toInt() and 0xFF
        require(keySecurity in 0..2) { "unsupported key security byte $keySecurity" }
        val ciphertext = payload.copyOfRange(43, PAYLOAD_LEN)
        val pw = nfkcPassword(password)
        val key = SCrypt.generate(pw, salt, 1 shl logN, 8, 1, 32)
        pw.fill(0)
        try {
            return xchacha20poly1305(key, nonce, ciphertext, byteArrayOf(keySecurity.toByte()), false)
        } catch (e: Exception) {
            throw IllegalArgumentException("wrong passphrase or corrupted ncryptsec payload", e)
        } finally {
            key.fill(0)
        }
    }

    fun ncryptsecEncode(payload: ByteArray): String = Nip19.bech32Encode("ncryptsec", payload)

    fun ncryptsecDecode(ncryptsec: String): ByteArray {
        val (hrp, data) = Nip19.bech32Decode(ncryptsec)
        require(hrp == "ncryptsec") { "expected ncryptsec bech32 hrp, got $hrp" }
        return data
    }

    suspend fun encrypt(privkey: ByteArray, password: String, logN: Int = DEFAULT_LOG_N): String =
        withContext(Dispatchers.Default) { ncryptsecEncode(encryptPrivkey(privkey, password, logN)) }

    suspend fun decrypt(ncryptsec: String, password: String): ByteArray =
        withContext(Dispatchers.Default) { decryptPrivkey(ncryptsecDecode(ncryptsec), password) }

    private fun nfkcPassword(password: String): ByteArray =
        Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)

    private fun xchacha20poly1305(key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray, encrypt: Boolean): ByteArray {
        require(key.size == 32 && nonce.size == 24)
        val subkey = hchacha20(key, nonce.copyOfRange(0, 16))
        try {
            val ietfNonce = ByteArray(12)
            nonce.copyInto(ietfNonce, 4, 16, 24)
            val aead = ChaCha20Poly1305()
            aead.init(encrypt, AEADParameters(KeyParameter(subkey), 128, ietfNonce, aad))
            val out = ByteArray(aead.getOutputSize(data.size))
            val written = aead.processBytes(data, 0, data.size, out, 0)
            aead.doFinal(out, written)
            return out
        } finally {
            subkey.fill(0)
        }
    }

    /** HChaCha20: 32-byte key + 16-byte nonce -> 32-byte subkey (post-round words 0..3 and 12..15). */
    private fun hchacha20(key: ByteArray, nonce: ByteArray): ByteArray {
        val st = IntArray(16)
        st[0] = 0x61707865
        st[1] = 0x3320646e
        st[2] = 0x79622d32
        st[3] = 0x6b206574
        for (i in 0 until 8) st[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) st[12 + i] = le32(nonce, i * 4)
        repeat(10) {
            quarter(st, 0, 4, 8, 12)
            quarter(st, 1, 5, 9, 13)
            quarter(st, 2, 6, 10, 14)
            quarter(st, 3, 7, 11, 15)
            quarter(st, 0, 5, 10, 15)
            quarter(st, 1, 6, 11, 12)
            quarter(st, 2, 7, 8, 13)
            quarter(st, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        for (i in 0 until 4) putLe(out, i * 4, st[i])
        for (i in 0 until 4) putLe(out, 16 + i * 4, st[12 + i])
        st.fill(0)
        return out
    }

    private fun quarter(st: IntArray, a: Int, b: Int, c: Int, d: Int) {
        st[a] += st[b]
        st[d] = Integer.rotateLeft(st[d] xor st[a], 16)
        st[c] += st[d]
        st[b] = Integer.rotateLeft(st[b] xor st[c], 12)
        st[a] += st[b]
        st[d] = Integer.rotateLeft(st[d] xor st[a], 8)
        st[c] += st[d]
        st[b] = Integer.rotateLeft(st[b] xor st[c], 7)
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putLe(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }
}
