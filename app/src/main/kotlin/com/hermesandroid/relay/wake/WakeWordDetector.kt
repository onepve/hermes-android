package com.hermesandroid.relay.wake

interface WakeWordDetector : AutoCloseable {
    /**
     * Accept a 16 kHz mono PCM16 frame. Returns true when sherpa emits a
     * completed match for the configured phrase.
     */
    fun accept(samples: ShortArray, count: Int): Boolean
}

fun interface WakeWordDetectorFactory {
    fun create(
        files: WakeWordModelFiles,
        sensitivity: Float,
        confirmationFrames: Int,
    ): WakeWordDetector
}

object WakeWordTuning {
    /** sherpa threshold is 0..1 and higher is harder to trigger. */
    fun threshold(sensitivity: Float): Float = sensitivity.coerceIn(0.2f, 0.9f)

    /**
     * sherpa confirms a decoded keyword after this many trailing blank frames.
     * This is the native KWS confirmation control; a completed keyword result
     * must not be counted again in application code.
     */
    fun trailingBlanks(confirmationFrames: Int): Int = confirmationFrames.coerceIn(1, 5)

    fun matchesConfiguredPhrase(keyword: String): Boolean =
        keyword
            .replace('_', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
            .equals(DEFAULT_WAKE_PHRASE, ignoreCase = true)
}

/**
 * Lightweight stub implementation for SherpaWakeWordDetector.
 * Strips onnxruntime and sherpa-onnx native libraries to significantly reduce APK size.
 */
class SherpaWakeWordDetector(
    files: WakeWordModelFiles,
    sensitivity: Float,
    confirmationFrames: Int,
) : WakeWordDetector {
    override fun accept(samples: ShortArray, count: Int): Boolean {
        return false
    }

    override fun close() {
    }
}
