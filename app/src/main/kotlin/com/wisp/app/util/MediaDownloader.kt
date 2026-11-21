package com.wisp.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.wisp.app.nostr.SafeHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object MediaDownloader {

    private val mimeTypes = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm"
    )

    private val httpClient
        get() = com.wisp.app.relay.HttpClientFactory.getDownloadClient()

    internal fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .substringBefore('?').substringBefore('#')
        val stripped = buildString {
            for (c in base) {
                if (c.code < 0x20 || c.code == 0x7f) continue
                if (c in setOf(':', '*', '?', '"', '<', '>', '|', '/', '\\')) continue
                append(c)
            }
        }
        var cleaned = stripped
        while (true) {
            val next = cleaned.trim().trimStart('.').trimEnd('.', ' ')
            if (next == cleaned) break
            cleaned = next
        }
        if (cleaned.length > 180) cleaned = cleaned.take(180).trimEnd('.', ' ')
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "download" else cleaned
    }

    suspend fun downloadMedia(context: Context, url: String) {
        try {
            withContext(Dispatchers.IO) {
                val response = SafeHttp.executeGuardedGet(httpClient, url)
                    ?: error("Blocked or invalid media URL")
                response.use { resp ->
                    if (!resp.isSuccessful) error("Download failed: ${resp.code}")
                    val responseMimeType = resp.header("Content-Type")?.substringBefore(';')?.trim()
                    val body = resp.body ?: error("Empty response")

                    val rawName = sanitizeFileName(url)
                    val ext = rawName.substringAfterLast('.', "").lowercase()
                    val mimeType = mimeTypes[ext] ?: responseMimeType ?: "application/octet-stream"
                    val fileName = if (ext.isEmpty() || ext == rawName) {
                        val guessedExt = mimeTypes.entries.firstOrNull { it.value == mimeType }?.key ?: "bin"
                        "$rawName.$guessedExt"
                    } else rawName

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(collection, values)
                            ?: error("Failed to create MediaStore entry")
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            body.byteStream().use { input -> input.copyTo(out) }
                        } ?: error("Failed to open output stream")
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        val file = File(dir, fileName)
                        FileOutputStream(file).use { out ->
                            body.byteStream().use { input -> input.copyTo(out) }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun saveImageBytes(context: Context, bytes: ByteArray, format: String) {
        try {
            val fileName = "wisp_image_${System.currentTimeMillis()}.$format"
            val mimeType = "image/$format"

            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(collection, values)
                        ?: error("Failed to create MediaStore entry")
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    dir.mkdirs()
                    val file = File(dir, fileName)
                    FileOutputStream(file).use { it.write(bytes) }
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Saved to Pictures", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
