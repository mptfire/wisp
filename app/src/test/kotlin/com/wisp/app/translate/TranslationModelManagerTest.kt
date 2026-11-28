package com.wisp.app.translate

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

class TranslationModelManagerTest {

    private fun gzipBytes(raw: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw) }
        return bos.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun buildManifest(fileBlobs: Map<String, ByteArray>): TranslationModelManifest {
        val modelGz = fileBlobs.getValue("model.deen.intgemm.alphas.bin.gz")
        val vocabGz = fileBlobs.getValue("vocab.deen.spm.gz")
        val lexGz = fileBlobs.getValue("lex.50.50.deen.s2t.bin.gz")
        val total = (modelGz.size + vocabGz.size + lexGz.size).toLong()
        return TranslationModelManifest(
            commitSha = "deadbeef",
            baseUrl = "https://models.local/models",
            pairs = listOf(
                ModelPairEntry(
                    pair = "deen",
                    source = "de",
                    target = "en",
                    tier = "tiny",
                    totalCompressedBytes = total,
                    files = listOf(
                        ModelFileEntry("model.deen.intgemm.alphas.bin.gz", "model", sha256(modelGz), modelGz.size.toLong()),
                        ModelFileEntry("vocab.deen.spm.gz", "vocab", sha256(vocabGz), vocabGz.size.toLong()),
                        ModelFileEntry("lex.50.50.deen.s2t.bin.gz", "shortlist", sha256(lexGz), lexGz.size.toLong())
                    )
                ),
                ModelPairEntry(
                    pair = "enes",
                    source = "en",
                    target = "es",
                    tier = "tiny",
                    totalCompressedBytes = 100L,
                    files = emptyList()
                )
            )
        )
    }

    private fun clientServing(blobsByFilename: Map<String, ByteArray>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val fileName = url.substringAfterLast('/')
                val bytes = blobsByFilename[fileName]
                if (bytes != null) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bytes.toResponseBody())
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body(ByteArray(0).toResponseBody())
                        .build()
                }
            }
            .build()

    @Test
    fun resolvesDirectAndPivotPlans() {
        val mGz = gzipBytes("m".toByteArray())
        val vGz = gzipBytes("v".toByteArray())
        val lGz = gzipBytes("l".toByteArray())
        val manifest = buildManifest(
            mapOf(
                "model.deen.intgemm.alphas.bin.gz" to mGz,
                "vocab.deen.spm.gz" to vGz,
                "lex.50.50.deen.s2t.bin.gz" to lGz
            )
        )

        assertTrue(manifest.resolvePlan("de", "en") is TranslationPairPlan.Direct)
        val pivot = manifest.resolvePlan("de", "es")
        assertTrue(pivot is TranslationPairPlan.Pivot)
        assertEquals(2, pivot?.entries?.size)
        assertNull(manifest.resolvePlan("de", "de"))
        assertNull(manifest.resolvePlan("ja", "en"))
    }

    @Test
    fun downloadsVerifiesSha256DecompressesAndDeletesCleanly() = runTest {
        val rawModel = "fake-marian-weights".toByteArray()
        val rawVocab = "fake-spm-vocab".toByteArray()
        val rawLex = "fake-shortlist".toByteArray()
        val blobs = mapOf(
            "model.deen.intgemm.alphas.bin.gz" to gzipBytes(rawModel),
            "vocab.deen.spm.gz" to gzipBytes(rawVocab),
            "lex.50.50.deen.s2t.bin.gz" to gzipBytes(rawLex)
        )
        val manifest = buildManifest(blobs)
        val rootDir = Files.createTempDirectory("wisp-model-test").toFile()
        val evicted = mutableListOf<String>()

        try {
            val manager = TranslationModelManager(
                manifest = manifest,
                modelsRootDir = rootDir,
                httpClientProvider = { clientServing(blobs) },
                onPairDeleted = { evicted.add(it) }
            )
            assertFalse(manager.isPairInstalled("deen"))

            manager.downloadPair("deen")
            assertTrue(manager.isPairInstalled("deen"))
            assertEquals(1, manager.installedPairs.value.size)

            val pairDir = manager.pairDir("deen")
            assertEquals("fake-marian-weights", File(pairDir, "model.deen.intgemm.alphas.bin").readText())
            assertEquals("fake-spm-vocab", File(pairDir, "vocab.deen.spm").readText())
            assertEquals("fake-shortlist", File(pairDir, "lex.50.50.deen.s2t.bin").readText())

            manager.deletePair("deen")
            assertFalse(manager.isPairInstalled("deen"))
            assertEquals(listOf("deen"), evicted)
            assertTrue(manager.installedPairs.value.isEmpty())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsCorruptedSha256AndLeavesNoPartialInstall() = runTest {
        val goodModel = gzipBytes("valid-weights".toByteArray())
        val goodVocab = gzipBytes("valid-vocab".toByteArray())
        val goodLex = gzipBytes("valid-lex".toByteArray())
        val manifest = buildManifest(
            mapOf(
                "model.deen.intgemm.alphas.bin.gz" to goodModel,
                "vocab.deen.spm.gz" to goodVocab,
                "lex.50.50.deen.s2t.bin.gz" to goodLex
            )
        )
        val tamperedModel = goodModel.copyOf().also {
            val idx = it.size / 2
            it[idx] = (it[idx].toInt() xor 0xFF).toByte()
        }
        val tamperedBlobs = mapOf(
            "model.deen.intgemm.alphas.bin.gz" to tamperedModel,
            "vocab.deen.spm.gz" to goodVocab,
            "lex.50.50.deen.s2t.bin.gz" to goodLex
        )
        val rootDir = Files.createTempDirectory("wisp-model-bad-test").toFile()
        try {
            val manager = TranslationModelManager(
                manifest = manifest,
                modelsRootDir = rootDir,
                httpClientProvider = { clientServing(tamperedBlobs) }
            )
            var failed = false
            try {
                manager.downloadPair("deen")
            } catch (e: IllegalStateException) {
                failed = e.message?.contains("SHA-256") == true || e.message?.contains("size", ignoreCase = true) == true
            }
            assertTrue("Expected checksum failure", failed)
            assertFalse(manager.isPairInstalled("deen"))
            assertTrue(rootDir.listFiles().orEmpty().none { it.name.startsWith(".tmp-") })
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun downloadsUncompressedRemoteSettingsAttachmentsUsingLocationPath() = runTest {
        val rawModel = "remote-settings-marian-weights".toByteArray()
        val rawVocab = "remote-settings-spm-vocab".toByteArray()
        val rawLex = "remote-settings-shortlist".toByteArray()

        val locModel = "main-workspace/translations-models/test-model.bin"
        val locVocab = "main-workspace/translations-models/test-vocab.spm"
        val locLex = "main-workspace/translations-models/test-lex.bin"

        val blobsByPath = mapOf(
            "https://cdn.example.invalid/$locModel" to rawModel,
            "https://cdn.example.invalid/$locVocab" to rawVocab,
            "https://cdn.example.invalid/$locLex" to rawLex
        )

        val manifest = TranslationModelManifest(
            schemaVersion = 2,
            commitSha = "remote-settings-123",
            baseUrl = "https://cdn.example.invalid",
            pairs = listOf(
                ModelPairEntry(
                    pair = "esen",
                    source = "es",
                    target = "en",
                    tier = "v2.0",
                    totalCompressedBytes = (rawModel.size + rawVocab.size + rawLex.size).toLong(),
                    files = listOf(
                        ModelFileEntry("model.esen.intgemm.alphas.bin", "model", sha256(rawModel), rawModel.size.toLong(), locModel),
                        ModelFileEntry("vocab.esen.spm", "vocab", sha256(rawVocab), rawVocab.size.toLong(), locVocab),
                        ModelFileEntry("lex.50.50.esen.s2t.bin", "shortlist", sha256(rawLex), rawLex.size.toLong(), locLex)
                    )
                )
            )
        )

        val seenUrls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                seenUrls.add(url)
                val bytes = blobsByPath[url]
                if (bytes != null) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bytes.toResponseBody())
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body(ByteArray(0).toResponseBody())
                        .build()
                }
            }
            .build()

        val rootDir = Files.createTempDirectory("wisp-model-rs-test").toFile()
        try {
            val manager = TranslationModelManager(
                manifest = manifest,
                modelsRootDir = rootDir,
                httpClientProvider = { client }
            )
            manager.downloadPair("esen")
            assertTrue(manager.isPairInstalled("esen"))
            assertEquals(blobsByPath.keys.toSet(), seenUrls.toSet())

            val pairDir = manager.pairDir("esen")
            assertEquals("remote-settings-marian-weights", File(pairDir, "model.esen.intgemm.alphas.bin").readText())
            assertEquals("remote-settings-spm-vocab", File(pairDir, "vocab.esen.spm").readText())
            assertEquals("remote-settings-shortlist", File(pairDir, "lex.50.50.esen.s2t.bin").readText())
            assertTrue(pairDir.listFiles().orEmpty().none { it.name.endsWith(".part") })

            manager.deletePair("esen")
            assertFalse(manager.isPairInstalled("esen"))
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun bundledManifestParsesAndContainsTop20FirewispModels() {
        val manifestFile = listOf(
            File("src/main/assets/translation_models_manifest.json"),
            File("app/src/main/assets/translation_models_manifest.json")
        ).firstOrNull { it.isFile }
        assertNotNull("Bundled translation_models_manifest.json not found", manifestFile)

        val parsed = TranslationModelManifest.parse(manifestFile!!.readText())
        assertEquals(40, parsed.pairs.size)
        assertEquals("https://firewisp.de/models", parsed.baseUrl)
        assertNotNull(parsed.findPair("esen"))
        assertNotNull(parsed.findPair("hren"))
        assertNotNull(parsed.findPair("enhr"))
        assertNotNull(parsed.findPair("jaen"))
        assertNotNull(parsed.findPair("zhen"))
        assertTrue(parsed.pairs.all { pair ->
            pair.files.all { f ->
                f.name.endsWith(".gz") &&
                    f.location.isNotBlank() &&
                    f.compressedSha256.length == 64 &&
                    f.compressedBytes > 0L
            }
        })
    }

    @Test
    fun retriesTransientIoExceptionAndSucceeds() = runTest {
        val rawModel = "retry-weights".toByteArray()
        val rawVocab = "retry-vocab".toByteArray()
        val rawLex = "retry-lex".toByteArray()
        val blobs = mapOf(
            "model.deen.intgemm.alphas.bin.gz" to gzipBytes(rawModel),
            "vocab.deen.spm.gz" to gzipBytes(rawVocab),
            "lex.50.50.deen.s2t.bin.gz" to gzipBytes(rawLex)
        )
        val manifest = buildManifest(blobs)
        val modelAttempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val fileName = chain.request().url.toString().substringAfterLast('/')
                if (fileName.startsWith("model.") && modelAttempts.incrementAndGet() == 1) {
                    throw IOException("stream was reset: CANCEL")
                }
                val bytes = blobs.getValue(fileName)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes.toResponseBody())
                    .build()
            }
            .build()

        val rootDir = Files.createTempDirectory("wisp-model-retry-test").toFile()
        try {
            val manager = TranslationModelManager(
                manifest = manifest,
                modelsRootDir = rootDir,
                httpClientProvider = { client }
            )
            manager.downloadPair("deen")
            assertTrue(manager.isPairInstalled("deen"))
            assertEquals(2, modelAttempts.get())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonHttpsModelUrl() = runTest {
        val mGz = gzipBytes("m".toByteArray())
        val vGz = gzipBytes("v".toByteArray())
        val lGz = gzipBytes("l".toByteArray())
        val manifest = buildManifest(
            mapOf(
                "model.deen.intgemm.alphas.bin.gz" to mGz,
                "vocab.deen.spm.gz" to vGz,
                "lex.50.50.deen.s2t.bin.gz" to lGz
            )
        ).copy(baseUrl = "http://insecure.example/models")

        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody())
                    .build()
            }
            .build()

        val rootDir = Files.createTempDirectory("wisp-model-http-test").toFile()
        try {
            val manager = TranslationModelManager(
                manifest = manifest,
                modelsRootDir = rootDir,
                httpClientProvider = { client }
            )
            var rejected = false
            try {
                manager.downloadPair("deen")
            } catch (e: IllegalArgumentException) {
                rejected = e.message?.contains("HTTPS") == true
            }
            assertTrue("Expected HTTPS enforcement error", rejected)
            assertEquals(0, calls.get())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun abortsMidStreamWhenPayloadExceedsDeclaredSize() = runTest {
        val goodModel = gzipBytes("m".toByteArray())
        val goodVocab = gzipBytes("v".toByteArray())
        val goodLex = gzipBytes("l".toByteArray())
        val manifest = buildManifest(
            mapOf(
                "model.deen.intgemm.alphas.bin.gz" to goodModel,
                "vocab.deen.spm.gz" to goodVocab,
                "lex.50.50.deen.s2t.bin.gz" to goodLex
            )
        )
        val oversizedBlobs = mapOf(
            "model.deen.intgemm.alphas.bin.gz" to goodModel + ByteArray(256),
            "vocab.deen.spm.gz" to goodVocab,
            "lex.50.50.deen.s2t.bin.gz" to goodLex
        )
        val rootDir = Files.createTempDirectory("wisp-model-sizecap-test").toFile()
        try {
            val manager = TranslationModelManager(
                manifest = manifest,
                modelsRootDir = rootDir,
                httpClientProvider = { clientServing(oversizedBlobs) }
            )
            var failed = false
            try {
                manager.downloadPair("deen")
            } catch (e: IllegalStateException) {
                failed = e.message?.contains("exceeded expected size") == true
            }
            assertTrue("Expected mid-stream size limit error", failed)
            assertFalse(manager.isPairInstalled("deen"))
            assertTrue(rootDir.listFiles().orEmpty().none { it.name.startsWith(".tmp-") })
        } finally {
            rootDir.deleteRecursively()
        }
    }
}
