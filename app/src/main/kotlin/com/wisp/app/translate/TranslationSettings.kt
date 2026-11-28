package com.wisp.app.translate

import androidx.compose.runtime.staticCompositionLocalOf
import com.wisp.app.repo.TranslationRepository

data class TranslationSettings(
    val enabled: Boolean = true,
    val autoTranslate: Boolean = false,
    val targetLanguage: String = "system"
)

val LocalTranslationSettings = staticCompositionLocalOf { TranslationSettings() }
val LocalTranslationRepository = staticCompositionLocalOf<TranslationRepository?> { null }
