package com.wisp.app.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

enum class TranslationStatus {
    IDLE,
    IDENTIFYING_LANGUAGE,
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
    val errorMessage: String = ""
)

/**
 * In-app Google ML Kit translation has been removed in Fire Wisp so the build ships
 * with zero Google Play Services / ML Kit / Firebase / Clearcut telemetry SDKs and
 * never downloads models directly from Google servers.
 */
class TranslationRepository {
    companion object {
        const val IS_AVAILABLE = false
    }

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun stateFor(eventId: String): Flow<TranslationState> = flowOf(TranslationState())

    fun getState(eventId: String): TranslationState = TranslationState()

    fun translate(eventId: String, content: String) {
        // Intentionally disabled — Google ML Kit removed for privacy.
    }
}
