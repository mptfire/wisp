package com.wisp.app

import android.content.Intent
import android.net.Uri
import android.os.Build

data class PendingShare(
    val text: String? = null,
    val mediaUris: List<Uri> = emptyList()
)

/**
 * Parses incoming Android ACTION_SEND / ACTION_SEND_MULTIPLE share intents safely.
 * Only accepts `content://` media URIs (rejects `file://` to block local file exfiltration).
 */
object ShareIntentParser {
    private const val MAX_TEXT_CHARS = 10_000
    private const val MAX_MEDIA_URIS = 10

    fun parse(intent: Intent?): PendingShare? {
        if (intent == null) return null
        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null

        val rawSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val rawText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()

        val mergedText = buildString {
            if (rawSubject.isNotEmpty() && rawSubject != rawText && !rawText.contains(rawSubject)) {
                append(rawSubject)
                if (rawText.isNotEmpty()) append('\n')
            }
            if (rawText.isNotEmpty()) {
                append(rawText)
            }
        }.trim().take(MAX_TEXT_CHARS).ifEmpty { null }

        val uris = mutableListOf<Uri>()
        when (action) {
            Intent.ACTION_SEND -> {
                getSingleStreamUri(intent)?.let { uri ->
                    if (isSafeSharedUri(uri)) uris.add(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                for (uri in getMultipleStreamUris(intent)) {
                    if (isSafeSharedUri(uri) && uris.size < MAX_MEDIA_URIS) {
                        uris.add(uri)
                    }
                }
            }
        }

        if (mergedText == null && uris.isEmpty()) return null
        return PendingShare(text = mergedText, mediaUris = uris)
    }

    private fun isSafeSharedUri(uri: Uri): Boolean {
        return uri.scheme.equals("content", ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun getSingleStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    @Suppress("DEPRECATION")
    private fun getMultipleStreamUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }
    }
}
