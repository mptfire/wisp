package com.wisp.app.nostr

import com.wisp.app.repo.SparkRepository
import fr.acinq.secp256k1.Secp256k1
import java.math.BigInteger
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/******************************************************************
 * NIP-06: Nostr key from a BIP-39 mnemonic, path m/44'/1237'/0'/0/0.
 *
 * seed = PBKDF2-HMAC-SHA512(NFKD(mnemonic), salt="mnemonic", 2048, 64)
 * BIP-32 master from "Bitcoin seed", then CKDpriv down the path.
 * Mnemonic itself is validated fail-closed via SparkRepository (#690).
 ******************************************************************/
object Nip06 {
    private val CURVE_ORDER = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
    private const val HARDENED_BIT = 0x80000000.toInt()

    /** Returns the 32-byte Nostr private key, or null when the phrase is invalid. */
    fun privkeyOrNull(mnemonic: String): ByteArray? {
        val normalized = SparkRepository.normalizeMnemonic(mnemonic)
        if (SparkRepository.validateMnemonic(normalized) != null) return null
        val seed = mnemonicSeed(normalized)
        return try {
            privkeyFromSeed(seed)
        } catch (e: IllegalArgumentException) {
            null
        } finally {
            seed.fill(0)
        }
    }

    fun mnemonicSeed(normalizedMnemonic: String): ByteArray {
        val nfkd = Normalizer.normalize(normalizedMnemonic, Normalizer.Form.NFKD)
        val chars = nfkd.toCharArray()
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(chars, "mnemonic".toByteArray(Charsets.UTF_8), 2048, 512)
        try {
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    fun privkeyFromSeed(seed: ByteArray): ByteArray {
        require(seed.size == 64) { "BIP-39 seed must be 64 bytes" }
        val master = hmacSha512("Bitcoin seed".toByteArray(Charsets.UTF_8), seed)
        var key = ByteArray(0)
        var chainCode = ByteArray(0)
        try {
            val il = BigInteger(1, master.copyOfRange(0, 32))
            require(il < CURVE_ORDER && il.signum() != 0) { "invalid BIP-32 master key" }
            key = master.copyOfRange(0, 32)
            chainCode = master.copyOfRange(32, 64)
            val path = intArrayOf(44, 1237, 0, 0, 0)
            for (i in path.indices) {
                val index = path[i]
                val hardened = i < 3
                val child = ckdPriv(key, chainCode, index + (if (hardened) HARDENED_BIT else 0))
                key.fill(0)
                key = child.first
                val oldChain = chainCode
                chainCode = child.second
                oldChain.fill(0)
            }
            return key.copyOf()
        } finally {
            master.fill(0)
            key.fill(0)
            chainCode.fill(0)
        }
    }

    private fun ckdPriv(key: ByteArray, chainCode: ByteArray, index: Int): Pair<ByteArray, ByteArray> {
        val data = ByteArray(37)
        if (index and HARDENED_BIT != 0) {
            data[0] = 0
            key.copyInto(data, 1)
        } else {
            val secp = Secp256k1.get()
            val compressed = secp.pubKeyCompress(secp.pubkeyCreate(key))
            require(compressed.size == 33)
            compressed.copyInto(data, 0)
        }
        data[33] = (index ushr 24).toByte()
        data[34] = (index ushr 16).toByte()
        data[35] = (index ushr 8).toByte()
        data[36] = index.toByte()
        val i = hmacSha512(chainCode, data)
        try {
            val il = BigInteger(1, i.copyOfRange(0, 32))
            require(il < CURVE_ORDER) { "invalid child key (IL >= n)" }
            val child = il.add(BigInteger(1, key)).mod(CURVE_ORDER)
            require(child.signum() != 0) { "invalid child key (zero)" }
            // toByteArray() may yield 33 bytes (sign-padded) when the top bit
            // is set; the unsigned magnitude is its last 32 bytes.
            val childBytes = child.toByteArray()
            val len = minOf(childBytes.size, 32)
            val padded = ByteArray(32)
            childBytes.copyInto(padded, 32 - len, childBytes.size - len, childBytes.size)
            return Pair(padded, i.copyOfRange(32, 64))
        } finally {
            i.fill(0)
            data.fill(0)
        }
    }

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }
}
