package com.hermesandroid.relay.audio

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.hermesandroid.relay.data.BargeInSensitivity
import kotlin.math.sqrt

/**
 * Voice activity detection engine for barge-in.
 * Pure Kotlin RMS energy-based detection with zero external native dependencies.
 */
class VadEngine @VisibleForTesting internal constructor(
    private val client: VadClient,
    sampleRate: Int,
) {

    constructor(context: Context, sampleRate: Int = 16_000) : this(
        client = EnergyVadClient(),
        sampleRate = sampleRate,
    )

    init {
        require(sampleRate == 16_000) {
            "VadEngine currently supports only 16 kHz sample rate; got $sampleRate"
        }
    }

    @Volatile
    private var sensitivity: BargeInSensitivity = BargeInSensitivity.Default

    @Volatile
    private var profile: SensitivityProfile = SENSITIVITY_PROFILES.getValue(BargeInSensitivity.Default)
        .also { client.applyProfile(it) }

    private var consecutiveSpeechCount: Int = 0
    private var debounced: Boolean = false

    fun analyze(frame: ShortArray): VadResult {
        if (sensitivity == BargeInSensitivity.Off) {
            return VadResult.NOT_SPEECH
        }

        val rawSpeech = client.isSpeech(frame)

        if (rawSpeech) {
            if (consecutiveSpeechCount < profile.consecutiveSpeechFrames) {
                consecutiveSpeechCount++
            }
            if (consecutiveSpeechCount >= profile.consecutiveSpeechFrames) {
                debounced = true
            }
        } else {
            consecutiveSpeechCount = 0
            debounced = false
        }

        return if (debounced) {
            VadResult(isSpeech = true, probability = 1f)
        } else {
            VadResult(isSpeech = false, probability = if (rawSpeech) 1f else 0f)
        }
    }

    fun setSensitivity(sensitivity: BargeInSensitivity) {
        this.sensitivity = sensitivity
        val newProfile = SENSITIVITY_PROFILES.getValue(sensitivity)
        profile = newProfile
        client.applyProfile(newProfile)
        consecutiveSpeechCount = 0
        debounced = false
    }

    fun close() {
        client.close()
    }

    enum class Mode {
        NORMAL,
        AGGRESSIVE,
        VERY_AGGRESSIVE
    }

    companion object {
        const val FRAME_SIZE_SAMPLES: Int = 512

        internal val SENSITIVITY_PROFILES: Map<BargeInSensitivity, SensitivityProfile> = mapOf(
            BargeInSensitivity.Off to SensitivityProfile(
                mode = Mode.VERY_AGGRESSIVE,
                attackMs = 0,
                releaseMs = 0,
                consecutiveSpeechFrames = Int.MAX_VALUE,
            ),
            BargeInSensitivity.Low to SensitivityProfile(
                mode = Mode.VERY_AGGRESSIVE,
                attackMs = 80,
                releaseMs = 300,
                consecutiveSpeechFrames = 3,
            ),
            BargeInSensitivity.Default to SensitivityProfile(
                mode = Mode.AGGRESSIVE,
                attackMs = 50,
                releaseMs = 250,
                consecutiveSpeechFrames = 2,
            ),
            BargeInSensitivity.High to SensitivityProfile(
                mode = Mode.NORMAL,
                attackMs = 30,
                releaseMs = 200,
                consecutiveSpeechFrames = 1,
            ),
        )
    }

    internal interface VadClient {
        fun isSpeech(frame: ShortArray): Boolean
        fun applyProfile(profile: SensitivityProfile)
        fun close()
    }

    internal data class SensitivityProfile(
        val mode: Mode,
        val attackMs: Int,
        val releaseMs: Int,
        val consecutiveSpeechFrames: Int,
    )

    private class EnergyVadClient : VadClient {
        private var threshold: Double = 1500.0

        override fun isSpeech(frame: ShortArray): Boolean {
            if (frame.isEmpty()) return false
            var sum = 0.0
            for (sample in frame) {
                sum += sample.toDouble() * sample.toDouble()
            }
            val rms = sqrt(sum / frame.size)
            return rms > threshold
        }

        override fun applyProfile(profile: SensitivityProfile) {
            threshold = when (profile.mode) {
                Mode.VERY_AGGRESSIVE -> 2500.0
                Mode.AGGRESSIVE -> 1500.0
                Mode.NORMAL -> 800.0
            }
        }

        override fun close() {}
    }
}

data class VadResult(
    val isSpeech: Boolean,
    val probability: Float,
) {
    companion object {
        internal val NOT_SPEECH = VadResult(isSpeech = false, probability = 0f)
    }
}
