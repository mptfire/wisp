package com.wisp.app.translate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
data class TranslationModelManifest(
    val schemaVersion: Int = 1,
    val commitSha: String,
    val baseUrl: String,
    val pairs: List<ModelPairEntry>
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(rawJson: String): TranslationModelManifest =
            json.decodeFromString(serializer(), rawJson)
    }

    fun findPair(pairId: String): ModelPairEntry? =
        pairs.firstOrNull { it.pair == pairId }

    fun resolvePlan(sourceCode: String, targetCode: String): TranslationPairPlan? {
        val src = sourceCode.lowercase(Locale.ROOT)
        val tgt = targetCode.lowercase(Locale.ROOT)
        if (src == tgt) return null

        findPair("$src$tgt")?.let { return TranslationPairPlan.Direct(it) }

        if (src != "en" && tgt != "en") {
            val first = findPair("${src}en")
            val second = findPair("en$tgt")
            if (first != null && second != null) {
                return TranslationPairPlan.Pivot(first, second)
            }
        }
        return null
    }

    fun supportedTargetLanguages(): List<String> {
        val targets = linkedSetOf("en")
        pairs.filter { it.source == "en" }.mapTo(targets) { it.target }
        return targets.toList()
    }

    fun supportedSourceLanguages(targetCode: String): List<String> {
        val tgt = targetCode.lowercase(Locale.ROOT)
        val sources = linkedSetOf<String>()
        for (p in pairs) {
            if (p.source != tgt && resolvePlan(p.source, tgt) != null) {
                sources.add(p.source)
            }
        }
        return sources.toList()
    }
}

@Serializable
data class ModelPairEntry(
    val pair: String,
    val source: String,
    val target: String,
    val tier: String,
    val totalCompressedBytes: Long,
    val files: List<ModelFileEntry>
) {
    fun fileUrl(baseUrl: String, file: ModelFileEntry): String =
        if (file.location.isNotBlank()) {
            "${baseUrl.trimEnd('/')}/${file.location.trimStart('/')}"
        } else {
            "${baseUrl.trimEnd('/')}/$tier/$pair/${file.name}"
        }

    fun uncompressedFileName(file: ModelFileEntry): String =
        file.name.removeSuffix(".gz")
}

@Serializable
data class ModelFileEntry(
    val name: String,
    val role: String,
    val compressedSha256: String,
    val compressedBytes: Long,
    val location: String = ""
)

sealed interface TranslationPairPlan {
    val entries: List<ModelPairEntry>

    data class Direct(val entry: ModelPairEntry) : TranslationPairPlan {
        override val entries: List<ModelPairEntry> = listOf(entry)
    }

    data class Pivot(val first: ModelPairEntry, val second: ModelPairEntry) : TranslationPairPlan {
        override val entries: List<ModelPairEntry> = listOf(first, second)
    }
}

data class InstalledModelInfo(
    val pair: ModelPairEntry,
    val sizeOnDiskBytes: Long
)
