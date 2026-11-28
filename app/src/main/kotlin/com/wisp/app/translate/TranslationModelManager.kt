package com.wisp.app.translate

import android.content.Context
import com.wisp.app.relay.HttpClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class TranslationModelManager(
    val manifest: TranslationModelManifest,
    private val modelsRootDir: File,
    private val httpClientProvider: () -> OkHttpClient = { HttpClientFactory.getDownloadClient() },
    private val onPairDeleted: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context, onPairDeleted: (String) -> Unit = {}) : this(
        manifest = context.assets.open(MANIFEST_ASSET).bufferedReader().use {
            TranslationModelManifest.parse(it.readText())
        },
        modelsRootDir = File(context.filesDir, "translation-models"),
        httpClientProvider = { HttpClientFactory.getDownloadClient() },
        onPairDeleted = onPairDeleted
    )

    private val downloadLock = Any()
    private val activeJobs = ConcurrentHashMap<String, Deferred<Unit>>()

    private val _installedPairs = MutableStateFlow<List<InstalledModelInfo>>(emptyList())
    val installedPairs: StateFlow<List<InstalledModelInfo>> = _installedPairs

    private val _activeDownloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, Float>> = _activeDownloads

    init {
        refreshInstalled()
    }

    fun pairDir(pairId: String): File {
        require(pairId.matches(SAFE_PAIR_REGEX)) { "Invalid pair id: $pairId" }
        return File(modelsRootDir, pairId)
    }

    fun isPairInstalled(pairId: String): Boolean {
        val entry = manifest.findPair(pairId) ?: return false
        val dir = pairDir(pairId)
        val ready = File(dir, READY_MARKER)
        if (!ready.isFile) return false
        return entry.files.all { file ->
            val out = File(dir, entry.uncompressedFileName(file))
            out.isFile && out.length() > 0L
        }
    }

    fun missingPairs(plan: TranslationPairPlan): List<ModelPairEntry> =
        plan.entries.filter { !isPairInstalled(it.pair) }

    fun refreshInstalled() {
        val installed = manifest.pairs.mapNotNull { entry ->
            if (!isPairInstalled(entry.pair)) return@mapNotNull null
            val dir = pairDir(entry.pair)
            val bytes = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
            InstalledModelInfo(entry, bytes)
        }
        _installedPairs.value = installed
    }

    fun totalInstalledBytes(): Long = _installedPairs.value.sumOf { it.sizeOnDiskBytes }

    suspend fun downloadPair(pairId: String, onProgress: (Float) -> Unit = {}) {
        val entry = manifest.findPair(pairId)
            ?: throw IllegalArgumentException("Unsupported translation model: $pairId")
        if (isPairInstalled(pairId)) {
            onProgress(1f)
            return
        }

        var owner = false
        val deferred: CompletableDeferred<Unit>
        val existing: Deferred<Unit>?
        synchronized(downloadLock) {
            val current = activeJobs[pairId]
            if (current != null && !current.isCompleted) {
                existing = current
                deferred = CompletableDeferred()
            } else {
                existing = null
                deferred = CompletableDeferred()
                activeJobs[pairId] = deferred
                owner = true
            }
        }

        if (existing != null) {
            existing.await()
            onProgress(1f)
            return
        }

        // Publish immediate 0f state before TCP/Tor setup so UI shows feedback instantly.
        _activeDownloads.value = _activeDownloads.value + (pairId to 0f)
        onProgress(0f)

        try {
            performDownload(entry) { p ->
                _activeDownloads.value = _activeDownloads.value + (pairId to p)
                onProgress(p)
            }
            refreshInstalled()
            deferred.complete(Unit)
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            if (owner) {
                synchronized(downloadLock) {
                    activeJobs.remove(pairId, deferred)
                }
                _activeDownloads.value = _activeDownloads.value - pairId
            }
        }
    }

    private suspend fun performDownload(entry: ModelPairEntry, onProgress: (Float) -> Unit) =
        withContext(ioDispatcher) {
            modelsRootDir.mkdirs()
            val targetDir = pairDir(entry.pair)
            val tmpDir = File(modelsRootDir, ".tmp-${entry.pair}-${System.nanoTime()}")
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            check(tmpDir.mkdirs()) { "Unable to create temporary model directory" }

            try {
                val totalBytes = entry.files.sumOf { it.compressedBytes }.coerceAtLeast(1L)
                var completedBeforeFile = 0L

                val baseClient = httpClientProvider()
                val client = baseClient.newBuilder()
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                    .retryOnConnectionFailure(true)
                    .build()

                for (fileEntry in entry.files) {
                    val outName = entry.uncompressedFileName(fileEntry)
                    require(!outName.contains('/') && !outName.contains("..") && outName.isNotEmpty()) {
                        "Unsafe filename in manifest: $outName"
                    }

                    val url = entry.fileUrl(manifest.baseUrl, fileEntry)
                    require(url.startsWith("https://")) {
                        "Refusing non-HTTPS model URL: $url"
                    }

                    val downloadFile = File(tmpDir, "${fileEntry.name}.part")
                    val digest = downloadFileWithRetry(
                        client = client,
                        url = url,
                        fileEntry = fileEntry,
                        downloadFile = downloadFile,
                        completedBeforeFile = completedBeforeFile,
                        totalBytes = totalBytes,
                        onProgress = onProgress
                    )

                    val actualSha = digest.joinToString("") { "%02x".format(it) }
                    if (!actualSha.equals(fileEntry.compressedSha256, ignoreCase = true)) {
                        throw IllegalStateException("SHA-256 verification failed for ${fileEntry.name}")
                    }
                    val actualSize = downloadFile.length()
                    if (fileEntry.compressedBytes > 0L && actualSize != fileEntry.compressedBytes) {
                        throw IllegalStateException("Size mismatch for ${fileEntry.name}")
                    }

                    val finalFile = File(tmpDir, outName)
                    if (fileEntry.name.endsWith(".gz")) {
                        decompressGzipChecked(downloadFile, finalFile)
                        downloadFile.delete()
                    } else {
                        if (!downloadFile.renameTo(finalFile)) {
                            downloadFile.copyTo(finalFile, overwrite = true)
                            downloadFile.delete()
                        }
                    }

                    completedBeforeFile += if (fileEntry.compressedBytes > 0L) fileEntry.compressedBytes else actualSize
                }

                File(tmpDir, READY_MARKER).writeText("${manifest.commitSha}\n")
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }
                if (!tmpDir.renameTo(targetDir)) {
                    tmpDir.copyRecursively(targetDir, overwrite = true)
                    tmpDir.deleteRecursively()
                }
                onProgress(1f)
            } catch (e: CancellationException) {
                tmpDir.deleteRecursively()
                throw e
            } catch (t: Throwable) {
                tmpDir.deleteRecursively()
                throw t
            }
        }

    private suspend fun downloadFileWithRetry(
        client: OkHttpClient,
        url: String,
        fileEntry: ModelFileEntry,
        downloadFile: File,
        completedBeforeFile: Long,
        totalBytes: Long,
        onProgress: (Float) -> Unit
    ): ByteArray {
        var lastError: IOException? = null
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            if (downloadFile.exists()) downloadFile.delete()
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var fileBytes = 0L

                val req = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed (${response.code}) for ${fileEntry.name}")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response for ${fileEntry.name}")
                    body.byteStream().use { input ->
                        BufferedOutputStream(FileOutputStream(downloadFile), IO_BUF_SIZE).use { output ->
                            val buf = ByteArray(IO_BUF_SIZE)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                fileBytes += n
                                if ((fileEntry.compressedBytes > 0L && fileBytes > fileEntry.compressedBytes) ||
                                    fileBytes > MAX_COMPRESSED_BYTES
                                ) {
                                    throw IllegalStateException("Download exceeded expected size for ${fileEntry.name}")
                                }
                                output.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                val frac = ((completedBeforeFile + fileBytes).toFloat() / totalBytes.toFloat())
                                    .coerceIn(0.01f, 0.99f)
                                onProgress(frac)
                            }
                        }
                    }
                }
                return digest.digest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                if (downloadFile.exists()) downloadFile.delete()
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    delay(1000L * attempt)
                }
            }
        }
        throw lastError ?: IOException("Download failed for ${fileEntry.name}")
    }

    private fun decompressGzipChecked(gzFile: File, outFile: File) {
        GZIPInputStream(BufferedInputStream(FileInputStream(gzFile), IO_BUF_SIZE)).use { gzIn ->
            BufferedOutputStream(FileOutputStream(outFile), IO_BUF_SIZE).use { out ->
                val buf = ByteArray(IO_BUF_SIZE)
                var totalOut = 0L
                while (true) {
                    val n = gzIn.read(buf)
                    if (n < 0) break
                    totalOut += n
                    if (totalOut > MAX_DECOMPRESSED_BYTES) {
                        throw IllegalStateException("Decompressed file exceeded safety limit: ${outFile.name}")
                    }
                    out.write(buf, 0, n)
                }
            }
        }
    }

    fun deletePair(pairId: String) {
        onPairDeleted(pairId)
        val dir = pairDir(pairId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        refreshInstalled()
    }

    companion object {
        const val MANIFEST_ASSET = "translation_models_manifest.json"
        private const val READY_MARKER = ".ready"
        private const val IO_BUF_SIZE = 32 * 1024
        private const val MAX_COMPRESSED_BYTES = 100L * 1024L * 1024L
        private const val MAX_DECOMPRESSED_BYTES = 160L * 1024L * 1024L
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private val SAFE_PAIR_REGEX = Regex("^[a-z]{4}$")
    }
}
