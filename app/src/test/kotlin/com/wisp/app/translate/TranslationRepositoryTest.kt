package com.wisp.app.translate

import android.app.Application
import android.content.Context
import com.wisp.app.repo.InterfacePreferences
import com.wisp.app.repo.TranslationRepository
import com.wisp.app.repo.TranslationStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TranslationRepositoryTest {

    private fun gzipBytes(raw: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw) }
        return bos.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun makeTestSetup(
        rootDir: File,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): Pair<TranslationModelManager, FakeEngine> {
        val mGz = gzipBytes("m".toByteArray())
        val vGz = gzipBytes("v".toByteArray())
        val lGz = gzipBytes("l".toByteArray())
        val blobs = mapOf(
            "model.deen.intgemm.alphas.bin.gz" to mGz,
            "vocab.deen.spm.gz" to vGz,
            "lex.50.50.deen.s2t.bin.gz" to lGz
        )
        val manifest = TranslationModelManifest(
            commitSha = "deadbeef",
            baseUrl = "https://models.local/models",
            pairs = listOf(
                ModelPairEntry(
                    pair = "deen",
                    source = "de",
                    target = "en",
                    tier = "tiny",
                    totalCompressedBytes = (mGz.size + vGz.size + lGz.size).toLong(),
                    files = listOf(
                        ModelFileEntry("model.deen.intgemm.alphas.bin.gz", "model", sha256(mGz), mGz.size.toLong()),
                        ModelFileEntry("vocab.deen.spm.gz", "vocab", sha256(vGz), vGz.size.toLong()),
                        ModelFileEntry("lex.50.50.deen.s2t.bin.gz", "shortlist", sha256(lGz), lGz.size.toLong())
                    )
                )
            )
        )
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val name = chain.request().url.toString().substringAfterLast('/')
                val bytes = blobs[name] ?: return@addInterceptor Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not found")
                    .body(ByteArray(0).toResponseBody())
                    .build()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes.toResponseBody())
                    .build()
            }
            .build()
        val manager = TranslationModelManager(
            manifest = manifest,
            modelsRootDir = rootDir,
            httpClientProvider = { client },
            ioDispatcher = ioDispatcher
        )
        return manager to FakeEngine()
    }

    private class FakeEngine : TranslationEngine {
        var calls = 0
        override suspend fun translate(pairDir: File, pairId: String, text: String): String {
            calls++
            return "EN[$pairId]: $text"
        }

        override suspend fun pivotTranslate(
            firstPairDir: File,
            firstPairId: String,
            secondPairDir: File,
            secondPairId: String,
            text: String
        ): String {
            calls++
            return "PIVOT[$firstPairId->$secondPairId]: $text"
        }

        override fun evictPair(pairId: String) {}
        override fun close() {}
    }

    @Test
    fun manualTapPromptsForDownloadBeforeDownloadingThenTranslatesAfterConfirm() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = InterfacePreferences(context)
        prefs.reset()
        prefs.setTranslationEnabled(true)
        prefs.setTranslationTargetLanguage("en")

        val rootDir = Files.createTempDirectory("wisp-repo-test").toFile()
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val (manager, engine) = makeTestSetup(rootDir, dispatcher)
            val repo = TranslationRepository(
                interfacePrefs = prefs,
                modelManager = manager,
                engine = engine,
                defaultLanguageProvider = { "en" },
                scope = kotlinx.coroutines.CoroutineScope(dispatcher)
            )

            val note = "Endlich läuft mein eigener Nostr Relay über Tor! https://firewisp.de"
            repo.translate("ev1", note, allowDownloadPrompt = true)
            advanceUntilIdle()

            val promptState = repo.getState("ev1")
            assertEquals(TranslationStatus.NEEDS_MODEL_DOWNLOAD, promptState.status)
            assertEquals(listOf("deen"), promptState.requiredPairs)
            assertFalse("Model must not download before confirmation", manager.isPairInstalled("deen"))

            // Cancel resets cleanly to IDLE
            repo.cancelDownloadPrompt("ev1")
            assertEquals(TranslationStatus.IDLE, repo.getState("ev1").status)
            assertFalse(manager.isPairInstalled("deen"))

            // Tap again and confirm download
            repo.translate("ev1", note, allowDownloadPrompt = true)
            advanceUntilIdle()
            assertEquals(TranslationStatus.NEEDS_MODEL_DOWNLOAD, repo.getState("ev1").status)

            repo.confirmDownloadAndTranslate("ev1", note)
            advanceUntilIdle()

            val doneState = repo.getState("ev1")
            assertEquals(TranslationStatus.DONE, doneState.status)
            assertTrue(doneState.translatedText.contains("https://firewisp.de"))
            assertEquals(1, engine.calls)
            assertTrue(manager.isPairInstalled("deen"))
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun autoTranslateSkipsMissingModelSilentlyAndTranslatesWhenInstalled() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = InterfacePreferences(context)
        prefs.reset()
        prefs.setTranslationEnabled(true)
        prefs.setAutoTranslate(true)
        prefs.setTranslationTargetLanguage("en")

        val rootDir = Files.createTempDirectory("wisp-repo-auto-test").toFile()
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val (manager, engine) = makeTestSetup(rootDir, dispatcher)
            val repo = TranslationRepository(
                interfacePrefs = prefs,
                modelManager = manager,
                engine = engine,
                defaultLanguageProvider = { "en" },
                scope = kotlinx.coroutines.CoroutineScope(dispatcher)
            )

            val note = "Heute funktioniert unser Relay richtig gut und stabil."
            // Model missing -> silent IDLE, zero network
            repo.translate("ev_auto_1", note, allowDownloadPrompt = false)
            advanceUntilIdle()
            assertEquals(TranslationStatus.IDLE, repo.getState("ev_auto_1").status)
            assertEquals(0, engine.calls)

            // Same language -> SAME_LANGUAGE
            repo.translate("ev_same", "This English note stays in the same language today.", allowDownloadPrompt = false)
            advanceUntilIdle()
            assertEquals(TranslationStatus.SAME_LANGUAGE, repo.getState("ev_same").status)

            // Install model, then auto-translate runs offline
            manager.downloadPair("deen")
            repo.translate("ev_auto_1", note, allowDownloadPrompt = false)
            advanceUntilIdle()
            assertEquals(TranslationStatus.DONE, repo.getState("ev_auto_1").status)
            assertEquals(1, engine.calls)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun undetectableNotePromptsManualSourceLanguageThenTranslates() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = InterfacePreferences(context)
        prefs.reset()
        prefs.setTranslationEnabled(true)
        prefs.setTranslationTargetLanguage("en")

        val rootDir = Files.createTempDirectory("wisp-repo-manual-src-test").toFile()
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val (manager, engine) = makeTestSetup(rootDir, dispatcher)
            val repo = TranslationRepository(
                interfacePrefs = prefs,
                modelManager = manager,
                engine = engine,
                defaultLanguageProvider = { "en" },
                scope = kotlinx.coroutines.CoroutineScope(dispatcher)
            )

            val note = "xyz qqq"
            // Auto-translate stays IDLE on undetectable text
            repo.translate("ev_auto_unknown", note, allowDownloadPrompt = false)
            advanceUntilIdle()
            assertEquals(TranslationStatus.IDLE, repo.getState("ev_auto_unknown").status)

            // Manual tap asks user to select source language instead of failing with ERROR
            repo.translate("ev_manual", note, allowDownloadPrompt = true)
            advanceUntilIdle()
            assertEquals(TranslationStatus.NEEDS_SOURCE_LANGUAGE, repo.getState("ev_manual").status)

            // Cancel clears NEEDS_SOURCE_LANGUAGE cleanly
            repo.cancelDownloadPrompt("ev_manual")
            assertEquals(TranslationStatus.IDLE, repo.getState("ev_manual").status)

            // requestSourceLanguageSelection also transitions to NEEDS_SOURCE_LANGUAGE
            repo.requestSourceLanguageSelection("ev_manual")
            assertEquals(TranslationStatus.NEEDS_SOURCE_LANGUAGE, repo.getState("ev_manual").status)

            // Picking "de" prompts model download, then confirms & translates
            repo.translateWithSource("ev_manual", note, "de", allowDownloadPrompt = true)
            advanceUntilIdle()
            assertEquals(TranslationStatus.NEEDS_MODEL_DOWNLOAD, repo.getState("ev_manual").status)
            assertEquals("de", repo.getState("ev_manual").sourceCode)

            repo.confirmDownloadAndTranslate("ev_manual", note)
            advanceUntilIdle()
            assertEquals(TranslationStatus.DONE, repo.getState("ev_manual").status)
            assertEquals(1, engine.calls)
        } finally {
            rootDir.deleteRecursively()
        }
    }
}
