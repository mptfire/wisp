package com.wisp.app.translate

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NostrTextPreprocessorTest {

    @Test
    fun preservesLeadingTrailingAndInlineNostrTokensAndNewlines() = runTest {
        val original = buildString {
            appendLine("https://firewisp.de/photo.jpg")
            appendLine("Hallo nostr:npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsutp0r3 schau dir https://example.org/a an #bitcoin")
            append("nostr:nevent1qqsqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
        }

        val capturedProse = mutableListOf<String>()
        val result = NostrTextPreprocessor.translatePreservingNostrTokens(original) { chunk ->
            capturedProse.add(chunk)
            chunk
                .replace("Hallo", "Hello")
                .replace("schau dir", "look at")
                .replace("an", "it")
        }

        // Line 1 (pure URL) and Line 3 (pure nevent) must not even be sent to NMT
        assertEquals(1, capturedProse.size)

        val expected = buildString {
            appendLine("https://firewisp.de/photo.jpg")
            appendLine("Hello nostr:npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsutp0r3 look at https://example.org/a it #bitcoin")
            append("nostr:nevent1qqsqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
        }
        assertEquals(expected, result)
    }

    @Test
    fun recoversDroppedInlineTokenAsFallback() = runTest {
        val original = "Siehe https://example.com bitte jetzt!"
        val result = NostrTextPreprocessor.translatePreservingNostrTokens(original) {
            // Simulate an NMT model that drops the placeholder
            "See please now!"
        }
        assertEquals("See please now! https://example.com", result)
    }
}
