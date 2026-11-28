package com.wisp.app.translate

/**
 * Strips or protects Nostr-specific tokens (URLs, nostr: bech32 mentions/quotes,
 * hashtags, and custom emoji shortcodes) around offline language detection and NMT.
 */
object NostrTextPreprocessor {
    private val NOSTR_URI_REGEX =
        Regex("""nostr:(?:npub|nprofile|note|nevent|naddr|nsec)1[0-9a-zA-Z]+""")
    private val BARE_BECH32_REGEX =
        Regex("""\b(?:npub|nprofile|note|nevent|naddr)1[0-9a-z]{20,}\b""")
    private val URL_REGEX = Regex("""https?://\S+""")
    private val CUSTOM_EMOJI_REGEX = Regex("""(?<!\w):[a-zA-Z0-9_+-]{2,40}:(?!\w)""")
    private val HASHTAG_REGEX = Regex("""(?:^|(?<=\s))#[\p{L}\p{N}_-]+""")

    private val NON_TRANSLATABLE_TOKEN_REGEX = Regex(
        """${NOSTR_URI_REGEX.pattern}|${URL_REGEX.pattern}|${BARE_BECH32_REGEX.pattern}|${CUSTOM_EMOJI_REGEX.pattern}|#[\p{L}\p{N}_-]+"""
    )

    private val LEADING_TOKEN_REGEX = Regex(
        """^\s*(?:(?:${NOSTR_URI_REGEX.pattern}|${URL_REGEX.pattern}|${BARE_BECH32_REGEX.pattern}|${CUSTOM_EMOJI_REGEX.pattern}|#[\p{L}\p{N}_-]+)\s*)+"""
    )
    private val TRAILING_TOKEN_REGEX = Regex(
        """(?:\s*(?:${NOSTR_URI_REGEX.pattern}|${URL_REGEX.pattern}|${BARE_BECH32_REGEX.pattern}|${CUSTOM_EMOJI_REGEX.pattern}|#[\p{L}\p{N}_-]+))+\s*$"""
    )

    fun stripForDetection(text: String): String {
        if (text.isBlank()) return ""
        return text
            .replace(NOSTR_URI_REGEX, " ")
            .replace(URL_REGEX, " ")
            .replace(BARE_BECH32_REGEX, " ")
            .replace(CUSTOM_EMOJI_REGEX, " ")
            .replace(HASHTAG_REGEX, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    suspend fun translatePreservingNostrTokens(
        text: String,
        translateProse: suspend (String) -> String
    ): String {
        if (text.isBlank()) return text
        val lines = text.split('\n')
        val outLines = ArrayList<String>(lines.size)
        for (line in lines) {
            outLines.add(translateLinePreservingTokens(line, translateProse))
        }
        return outLines.joinToString("\n")
    }

    private suspend fun translateLinePreservingTokens(
        line: String,
        translateProse: suspend (String) -> String
    ): String {
        if (line.isBlank()) return line

        val leadingWhitespace = line.takeWhile { it == ' ' || it == '\t' }
        val trailingWhitespace = line.takeLastWhile { it == ' ' || it == '\t' }
        val body = line.substring(leadingWhitespace.length, line.length - trailingWhitespace.length)
        if (body.isEmpty()) return line

        val leadMatch = LEADING_TOKEN_REGEX.find(body)
        val leadingPrefix = leadMatch?.value ?: ""
        var core = body.substring(leadingPrefix.length)

        val trailMatch = TRAILING_TOKEN_REGEX.find(core)
        val trailingSuffix = trailMatch?.value ?: ""
        if (trailMatch != null) {
            core = core.substring(0, trailMatch.range.first)
        }

        if (core.isBlank() || core.none { it.isLetter() }) {
            return line
        }

        val tokens = ArrayList<String>()
        val maskedCore = NON_TRANSLATABLE_TOKEN_REGEX.replace(core) { m ->
            val idx = tokens.size
            tokens.add(m.value)
            "WTOK${idx}X"
        }

        val translatedCore = translateProse(maskedCore).trim()
        val restored = restoreInlineTokens(translatedCore, tokens)
        return "$leadingWhitespace$leadingPrefix$restored$trailingSuffix$trailingWhitespace"
    }

    private fun restoreInlineTokens(text: String, tokens: List<String>): String {
        if (tokens.isEmpty()) return text
        var out = text
        val missing = ArrayList<String>()
        for ((idx, tok) in tokens.withIndex()) {
            val regex = Regex("""\bW\s*T\s*O\s*K\s*$idx\s*X\b""", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(out)) {
                out = regex.replaceFirst(out, Regex.escapeReplacement(tok))
            } else {
                missing.add(tok)
            }
        }
        if (missing.isNotEmpty()) {
            out = "$out ${missing.joinToString(" ")}".trim()
        }
        return out
    }
}
