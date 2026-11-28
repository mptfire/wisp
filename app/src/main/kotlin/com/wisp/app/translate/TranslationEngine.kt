package com.wisp.app.translate

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File

interface TranslationEngine {
    suspend fun translate(pairDir: File, pairId: String, text: String): String
    suspend fun pivotTranslate(
        firstPairDir: File,
        firstPairId: String,
        secondPairDir: File,
        secondPairId: String,
        text: String
    ): String
    fun evictPair(pairId: String)
    fun close()
}

@OptIn(ExperimentalCoroutinesApi::class)
class SlimtTranslationEngine(
    private val maxCachedModels: Int = 2,
    private val workers: Int = 2
) : TranslationEngine {

    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var servicePtr: Long = 0L

    private val modelCache = object : LinkedHashMap<String, Long>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            if (size > maxCachedModels) {
                SlimtNative.nativeDestroyModel(eldest.value)
                return true
            }
            return false
        }
    }

    override suspend fun translate(pairDir: File, pairId: String, text: String): String =
        withContext(dispatcher) {
            if (text.isBlank()) return@withContext text
            val svc = ensureService()
            val model = getOrLoadModel(pairDir, pairId)
            val outBytes = SlimtNative.nativeTranslate(svc, model, text.toByteArray(Charsets.UTF_8))
            String(outBytes, Charsets.UTF_8)
        }

    override suspend fun pivotTranslate(
        firstPairDir: File,
        firstPairId: String,
        secondPairDir: File,
        secondPairId: String,
        text: String
    ): String = withContext(dispatcher) {
        if (text.isBlank()) return@withContext text
        val svc = ensureService()
        val first = getOrLoadModel(firstPairDir, firstPairId)
        val second = getOrLoadModel(secondPairDir, secondPairId)
        val outBytes = SlimtNative.nativePivotTranslate(svc, first, second, text.toByteArray(Charsets.UTF_8))
        String(outBytes, Charsets.UTF_8)
    }

    override fun evictPair(pairId: String) {
        val ptr = synchronized(modelCache) { modelCache.remove(pairId) }
        if (ptr != null && ptr != 0L) {
            SlimtNative.nativeDestroyModel(ptr)
        }
    }

    override fun close() {
        val modelsToDestroy: List<Long>
        val svcToDestroy: Long
        synchronized(modelCache) {
            modelsToDestroy = modelCache.values.toList()
            modelCache.clear()
            svcToDestroy = servicePtr
            servicePtr = 0L
        }
        modelsToDestroy.forEach { if (it != 0L) SlimtNative.nativeDestroyModel(it) }
        if (svcToDestroy != 0L) SlimtNative.nativeDestroyService(svcToDestroy)
    }

    private fun ensureService(): Long {
        SlimtNative.ensureLoaded()
        if (servicePtr == 0L) {
            servicePtr = SlimtNative.nativeCreateService(workers)
            check(servicePtr != 0L) { "Failed to initialize native translation service" }
        }
        return servicePtr
    }

    private fun getOrLoadModel(pairDir: File, pairId: String): Long {
        synchronized(modelCache) {
            modelCache[pairId]?.let { return it }
        }
        val files = pairDir.listFiles()?.filter { it.isFile }.orEmpty()
        val modelFile = files.firstOrNull { it.name.startsWith("model.") && it.name.endsWith(".bin") }
            ?: error("Missing model binary in ${pairDir.name}")
        val shortlistFile = files.firstOrNull { it.name.startsWith("lex.") && it.name.endsWith(".bin") }
            ?: error("Missing shortlist binary in ${pairDir.name}")
        val sharedVocab = files.firstOrNull { it.name.startsWith("vocab.") && it.name.endsWith(".spm") }
        val srcVocab = files.firstOrNull { it.name.startsWith("srcvocab.") && it.name.endsWith(".spm") }
        val trgVocab = files.firstOrNull { it.name.startsWith("trgvocab.") && it.name.endsWith(".spm") }

        val vocabPath = (sharedVocab ?: srcVocab)?.absolutePath
            ?: error("Missing SentencePiece vocabulary in ${pairDir.name}")
        val targetVocabPath = if (sharedVocab != null) "" else (trgVocab?.absolutePath ?: "")

        val ptr = SlimtNative.nativeLoadModel(
            modelPath = modelFile.absolutePath,
            vocabPath = vocabPath,
            shortlistPath = shortlistFile.absolutePath,
            targetVocabPath = targetVocabPath
        )
        check(ptr != 0L) { "Failed to load translation model for $pairId" }
        synchronized(modelCache) {
            modelCache[pairId] = ptr
        }
        return ptr
    }
}
