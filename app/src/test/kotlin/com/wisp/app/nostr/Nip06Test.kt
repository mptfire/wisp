package com.wisp.app.nostr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Nip06Test {
    @Test
    fun officialVector12Words() {
        val key = Nip06.privkeyOrNull(
            "leader monkey parrot ring guide accident before fence cannon height naive bean")
        assertNotNull(key)
        assertEquals(
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
            key!!.toHex())
    }

    @Test
    fun officialVector24Words() {
        val key = Nip06.privkeyOrNull(
            "what bleak badge arrange retreat wolf trade produce cricket blur garlic valid proud rude strong choose busy staff weather area salt hollow arm fade")
        assertNotNull(key)
        assertEquals(
            "c15d739894c81a2fcfd3a2df85a0d2c0dbc47a280d092799f144d73d7ae78add",
            key!!.toHex())
    }

    @Test
    fun deterministicSamePhraseSameKey() {
        val m = "leader monkey parrot ring guide accident before fence cannon height naive bean"
        assertArrayEquals(Nip06.privkeyOrNull(m), Nip06.privkeyOrNull(m))
    }

    @Test
    fun unicodeSpaceNormalizationMatchesOfficial() {
        // NBSP-separated phrase must derive the same key as space-separated.
        val nbsp = "leader\u00A0monkey\u00A0parrot\u00A0ring\u00A0guide\u00A0accident\u00A0before\u00A0fence\u00A0cannon\u00A0height\u00A0naive\u00A0bean"
        assertEquals(
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
            Nip06.privkeyOrNull(nbsp)!!.toHex())
    }

    @Test
    fun rejectsInvalidChecksum() {
        val bad = "leader monkey parrot ring guide accident before fence cannon height naive beanx"
        assertNull(Nip06.privkeyOrNull(bad))
    }

    @Test
    fun rejectsWrongWordCount() {
        assertNull(Nip06.privkeyOrNull("leader monkey parrot"))
        assertNull(Nip06.privkeyOrNull(""))
    }

    @Test
    fun rejectsUnknownWords() {
        assertNull(
            Nip06.privkeyOrNull("zzz monkey parrot ring guide accident before fence cannon height naive bean"))
    }
}
