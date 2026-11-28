package com.wisp.app.translate

internal object SlimtNative {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("slimt_jni")
                loaded = true
            }
        }
    }

    external fun nativeCreateService(workers: Int): Long
    external fun nativeDestroyService(servicePtr: Long)
    external fun nativeLoadModel(
        modelPath: String,
        vocabPath: String,
        shortlistPath: String,
        targetVocabPath: String
    ): Long
    external fun nativeDestroyModel(modelPtr: Long)
    external fun nativeTranslate(servicePtr: Long, modelPtr: Long, textUtf8: ByteArray): ByteArray
    external fun nativePivotTranslate(
        servicePtr: Long,
        firstModelPtr: Long,
        secondModelPtr: Long,
        textUtf8: ByteArray
    ): ByteArray
}
