package com.wisp.app.repo

import android.content.Context
import com.wisp.app.translate.InstalledModelInfo
import com.wisp.app.translate.NostrTextPreprocessor
import com.wisp.app.translate.OfflineLanguageDetector
import com.wisp.app.translate.SlimtTranslationEngine
import com.wisp.app.translate.TranslationEngine
import com.wisp.app.translate.TranslationModelManager
import com.wisp.app.translate.TranslationModelManifest
import com.wisp.app.translate.TranslationPairPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class TranslationStatus {
    IDLE,
    IDENTIFYING_LANGUAGE,
    NEEDS_SOURCE_LANGUAGE,
    NEEDS_MODEL_DOWNLOAD,
    DOWNLOADING_MODEL,
    TRANSLATING,
    DONE,
    ERROR,
    SAME_LANGUAGE
}

data class TranslationState(
    val status: TranslationStatus = TranslationStatus.IDLE,
    val translatedText: String = "",
    val sourceLanguage: String = "",
    val targetLanguage: String = "",
    val sourceCode: String = "",
    val targetCode: String = "",
    val requiredPairs: List<String> = emptyList(),
    val downloadSizeBytes: Long = 0L,
    val downloadProgress: Float = 0f,
    val errorMessage: String = ""
)

/**
 * Offline, zero-Google on-device translation repository powered by slimt +
 * SHA-256 verified Mozilla Firefox Translations models routed via HttpClientFactory.
 */
class TranslationRepository(
    private val interfacePrefs: InterfacePreferences,
    private val modelManager: TranslationModelManager,
    private val engine: TranslationEngine = SlimtTranslationEngine(),
    private val detectLanguage: (String) -> String? = OfflineLanguageDetector::detect,
    private val defaultLanguageProvider: () -> String = { Locale.getDefault().language },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        const val IS_AVAILABLE = true

        fun displayName(languageTag: String): String {
            if (languageTag.isBlank()) return ""
            return Locale(languageTag).getDisplayLanguage(Locale.ENGLISH)
                .ifBlank { languageTag.uppercase(Locale.ROOT) }
        }
    }

    constructor(context: Context, interfacePrefs: InterfacePreferences) : this(
        interfacePrefs = interfacePrefs,
        engine = SlimtTranslationEngine(),
        appContext = context.applicationContext
    )

    private constructor(
        interfacePrefs: InterfacePreferences,
        engine: TranslationEngine,
        modelManagerFactory: (TranslationEngine) -> TranslationModelManager
    ) : this(
        interfacePrefs = interfacePrefs,
        modelManager = modelManagerFactory(engine),
        engine = engine
    )

    private constructor(
        interfacePrefs: InterfacePreferences,
        engine: TranslationEngine,
        appContext: Context
    ) : this(
        interfacePrefs = interfacePrefs,
        engine = engine,
        modelManagerFactory = { eng ->
            TranslationModelManager(appContext) { pairId -> eng.evictPair(pairId) }
        }
    )

    private val stateMap = ConcurrentHashMap<String, MutableStateFlow<TranslationState>>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    val manifest: TranslationModelManifest get() = modelManager.manifest
    val installedModels: StateFlow<List<InstalledModelInfo>> get() = modelManager.installedPairs
    val activeDownloads: StateFlow<Map<String, Float>> get() = modelManager.activeDownloads

    fun totalModelBytesUsed(): Long = modelManager.totalInstalledBytes()

    fun stateFor(eventId: String): Flow<TranslationState> =
        stateMap.computeIfAbsent(eventId) { MutableStateFlow(TranslationState()) }

    fun getState(eventId: String): TranslationState =
        stateMap[eventId]?.value ?: TranslationState()

    private fun publish(eventId: String, state: TranslationState) {
        stateMap.computeIfAbsent(eventId) { MutableStateFlow(state) }.value = state
        _version.value++
    }

    fun resolvedTargetLanguage(): String {
        val pref = interfacePrefs.getTranslationTargetLanguage().trim().lowercase(Locale.ROOT)
        val lang = if (pref == "system" || pref.isEmpty()) {
            defaultLanguageProvider().lowercase(Locale.ROOT)
        } else {
            pref
        }
        return when (lang) {
            "in" -> "id"
            "iw" -> "he"
            "no" -> "nb"
            else -> lang
        }
    }

    fun translate(eventId: String, content: String, allowDownloadPrompt: Boolean = true) {
        if (!interfacePrefs.isTranslationEnabled()) return
        val current = getState(eventId)
        if (current.status == TranslationStatus.IDENTIFYING_LANGUAGE ||
            current.status == TranslationStatus.DOWNLOADING_MODEL ||
            current.status == TranslationStatus.TRANSLATING ||
            current.status == TranslationStatus.DONE
        ) {
            return
        }

        publish(eventId, TranslationState(status = TranslationStatus.IDENTIFYING_LANGUAGE))

        scope.launch {
            try {
                val targetCode = resolvedTargetLanguage()
                val sourceCode = detectLanguage(content)
                if (sourceCode == null) {
                    if (allowDownloadPrompt) {
                        publish(
                            eventId,
                            TranslationState(
                                status = TranslationStatus.NEEDS_SOURCE_LANGUAGE,
                                targetCode = targetCode,
                                targetLanguage = displayName(targetCode)
                            )
                        )
                    } else {
                        publish(eventId, TranslationState())
                    }
                    return@launch
                }

                proceedWithSource(eventId, content, sourceCode, targetCode, allowDownloadPrompt)
            } catch (e: CancellationException) {
                publish(eventId, TranslationState())
                throw e
            } catch (e: Exception) {
                publish(
                    eventId,
                    TranslationState(
                        status = TranslationStatus.ERROR,
                        errorMessage = e.message ?: "Translation failed"
                    )
                )
            }
        }
    }

    fun translateWithSource(
        eventId: String,
        content: String,
        sourceCode: String,
        allowDownloadPrompt: Boolean = true
    ) {
        if (!interfacePrefs.isTranslationEnabled()) return
        val src = sourceCode.trim().lowercase(Locale.ROOT)
        if (src.isBlank()) return

        scope.launch {
            try {
                val targetCode = resolvedTargetLanguage()
                proceedWithSource(eventId, content, src, targetCode, allowDownloadPrompt)
            } catch (e: CancellationException) {
                publish(eventId, TranslationState())
                throw e
            } catch (e: Exception) {
                publish(
                    eventId,
                    TranslationState(
                        status = TranslationStatus.ERROR,
                        errorMessage = e.message ?: "Translation failed"
                    )
                )
            }
        }
    }

    fun requestSourceLanguageSelection(eventId: String) {
        val targetCode = resolvedTargetLanguage()
        publish(
            eventId,
            TranslationState(
                status = TranslationStatus.NEEDS_SOURCE_LANGUAGE,
                targetCode = targetCode,
                targetLanguage = displayName(targetCode)
            )
        )
    }

    private suspend fun proceedWithSource(
        eventId: String,
        content: String,
        sourceCode: String,
        targetCode: String,
        allowDownloadPrompt: Boolean
    ) {
        if (sourceCode == targetCode) {
            publish(
                eventId,
                TranslationState(
                    status = TranslationStatus.SAME_LANGUAGE,
                    sourceCode = sourceCode,
                    targetCode = targetCode,
                    sourceLanguage = displayName(sourceCode),
                    targetLanguage = displayName(targetCode)
                )
            )
            return
        }

        val plan = modelManager.manifest.resolvePlan(sourceCode, targetCode)
        if (plan == null) {
            if (allowDownloadPrompt) {
                publish(
                    eventId,
                    TranslationState(
                        status = TranslationStatus.ERROR,
                        sourceCode = sourceCode,
                        targetCode = targetCode,
                        errorMessage = "Unsupported pair: ${displayName(sourceCode)} → ${displayName(targetCode)}"
                    )
                )
            } else {
                publish(eventId, TranslationState())
            }
            return
        }

        val missing = modelManager.missingPairs(plan)
        if (missing.isNotEmpty()) {
            if (!allowDownloadPrompt) {
                // Auto-translate only uses already-installed offline models.
                publish(eventId, TranslationState())
                return
            }
            publish(
                eventId,
                TranslationState(
                    status = TranslationStatus.NEEDS_MODEL_DOWNLOAD,
                    sourceCode = sourceCode,
                    targetCode = targetCode,
                    sourceLanguage = displayName(sourceCode),
                    targetLanguage = displayName(targetCode),
                    requiredPairs = missing.map { it.pair },
                    downloadSizeBytes = missing.sumOf { it.totalCompressedBytes }
                )
            )
            return
        }

        runTranslation(eventId, content, plan, sourceCode, targetCode)
    }

    fun confirmDownloadAndTranslate(eventId: String, content: String) {
        val current = getState(eventId)
        val sourceCode = current.sourceCode.ifBlank { detectLanguage(content) ?: "" }
        val targetCode = current.targetCode.ifBlank { resolvedTargetLanguage() }
        val plan = modelManager.manifest.resolvePlan(sourceCode, targetCode)
        if (plan == null) {
            publish(
                eventId,
                TranslationState(
                    status = TranslationStatus.ERROR,
                    errorMessage = "Unsupported language pair"
                )
            )
            return
        }

        val missing = modelManager.missingPairs(plan)
        val totalBytes = missing.sumOf { it.totalCompressedBytes }.coerceAtLeast(1L)

        scope.launch {
            try {
                var completedBytes = 0L
                for (entry in missing) {
                    val baseOffset = completedBytes
                    publish(
                        eventId,
                        TranslationState(
                            status = TranslationStatus.DOWNLOADING_MODEL,
                            sourceCode = sourceCode,
                            targetCode = targetCode,
                            sourceLanguage = displayName(sourceCode),
                            targetLanguage = displayName(targetCode),
                            requiredPairs = missing.map { it.pair },
                            downloadSizeBytes = totalBytes,
                            downloadProgress = (baseOffset.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        )
                    )
                    modelManager.downloadPair(entry.pair) { fraction ->
                        val agg = ((baseOffset + (fraction * entry.totalCompressedBytes)) / totalBytes.toFloat())
                            .coerceIn(0f, 1f)
                        publish(
                            eventId,
                            TranslationState(
                                status = TranslationStatus.DOWNLOADING_MODEL,
                                sourceCode = sourceCode,
                                targetCode = targetCode,
                                sourceLanguage = displayName(sourceCode),
                                targetLanguage = displayName(targetCode),
                                requiredPairs = missing.map { it.pair },
                                downloadSizeBytes = totalBytes,
                                downloadProgress = agg
                            )
                        )
                    }
                    completedBytes += entry.totalCompressedBytes
                }
                runTranslation(eventId, content, plan, sourceCode, targetCode)
            } catch (e: CancellationException) {
                publish(eventId, TranslationState())
                throw e
            } catch (e: Exception) {
                publish(
                    eventId,
                    TranslationState(
                        status = TranslationStatus.ERROR,
                        errorMessage = e.message ?: "Model download failed"
                    )
                )
            }
        }
    }

    fun cancelDownloadPrompt(eventId: String) {
        val current = getState(eventId)
        if (current.status == TranslationStatus.NEEDS_MODEL_DOWNLOAD ||
            current.status == TranslationStatus.NEEDS_SOURCE_LANGUAGE ||
            current.status == TranslationStatus.ERROR
        ) {
            publish(eventId, TranslationState())
        }
    }

    fun downloadModel(pairId: String, onError: (String) -> Unit = {}) {
        scope.launch {
            try {
                modelManager.downloadPair(pairId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Download failed")
            }
        }
    }

    fun deleteModel(pairId: String) {
        modelManager.deletePair(pairId)
    }

    private suspend fun runTranslation(
        eventId: String,
        content: String,
        plan: TranslationPairPlan,
        sourceCode: String,
        targetCode: String
    ) {
        publish(
            eventId,
            TranslationState(
                status = TranslationStatus.TRANSLATING,
                sourceCode = sourceCode,
                targetCode = targetCode,
                sourceLanguage = displayName(sourceCode),
                targetLanguage = displayName(targetCode)
            )
        )

        val translated = NostrTextPreprocessor.translatePreservingNostrTokens(content) { chunk ->
            when (plan) {
                is TranslationPairPlan.Direct ->
                    engine.translate(modelManager.pairDir(plan.entry.pair), plan.entry.pair, chunk)
                is TranslationPairPlan.Pivot ->
                    engine.pivotTranslate(
                        modelManager.pairDir(plan.first.pair),
                        plan.first.pair,
                        modelManager.pairDir(plan.second.pair),
                        plan.second.pair,
                        chunk
                    )
            }
        }

        publish(
            eventId,
            TranslationState(
                status = TranslationStatus.DONE,
                translatedText = translated,
                sourceCode = sourceCode,
                targetCode = targetCode,
                sourceLanguage = displayName(sourceCode),
                targetLanguage = displayName(targetCode)
            )
        )
    }

    fun close() {
        scope.cancel()
        engine.close()
    }
}
