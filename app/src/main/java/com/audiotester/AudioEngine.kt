package com.audiotester

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class AudioEngine {
    private val sampleRate = 48_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var config = AudioConfig()

    @Volatile
    private var isPlaying = false

    private var renderJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var phase = 0.0
    private var pinkState = PinkNoiseState()

    fun updateConfig(newConfig: AudioConfig) {
        config = newConfig
        pinkState.ensureBand(newConfig.band, sampleRate.toFloat())
    }

    fun start() {
        if (isPlaying) return
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuffer <= 0) return

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * 4)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack = track
        phase = 0.0
        pinkState = PinkNoiseState().also { it.ensureBand(config.band, sampleRate.toFloat()) }
        isPlaying = true
        track.play()
        renderJob = scope.launch {
            val frames = 1024
            val buffer = FloatArray(frames)
            while (isActive && isPlaying) {
                render(buffer)
                track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    fun stop() {
        isPlaying = false
        renderJob?.cancel()
        renderJob = null
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun render(buffer: FloatArray) {
        val current = config
        val linearGain = dbToLinear(current.levelDb)
        when (current.mode) {
            SignalMode.TONE -> renderTone(buffer, current.frequencyHz.toDouble(), linearGain)
            SignalMode.PINK_NOISE -> renderPinkNoise(buffer, linearGain, current.band)
        }
    }

    private fun renderTone(buffer: FloatArray, frequencyHz: Double, gain: Float) {
        val phaseStep = 2.0 * PI * frequencyHz / sampleRate.toDouble()
        for (index in buffer.indices) {
            buffer[index] = (sin(phase) * gain).toFloat()
            phase += phaseStep
            if (phase >= 2.0 * PI) {
                phase -= 2.0 * PI
            }
        }
    }

    private fun renderPinkNoise(buffer: FloatArray, gain: Float, band: NoiseBand) {
        pinkState.ensureBand(band, sampleRate.toFloat())
        for (index in buffer.indices) {
            buffer[index] = (pinkState.nextSample() * gain).coerceIn(-0.99f, 0.99f)
        }
    }

    private fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
}

data class AudioConfig(
    val mode: SignalMode = SignalMode.PINK_NOISE,
    val band: NoiseBand = NoiseBand.LowBass,
    val frequencyHz: Float = 1000f,
    val levelDb: Float = -12f,
)

enum class SignalMode {
    PINK_NOISE,
    TONE,
}

enum class NoiseBand(val label: String, val lowHz: Float, val highHz: Float) {
    Full("full", 20f, 18_000f),
    LowBass("bass", 20f, 250f),
    MidPresence("mids", 250f, 2_000f),
    Presence("pres", 2_000f, 6_000f),
    Treble("treble", 6_000f, 18_000f),
}

private class PinkNoiseState {
    private val random = Random.Default
    private var rows = FloatArray(16) { randomUnit() }
    private var runningSum = rows.sum()
    private var counter = 0
    private var activeBand: NoiseBand? = null
    private var highPass = BiquadFilter.identity()
    private var lowPass = BiquadFilter.identity()

    fun ensureBand(band: NoiseBand, sampleRate: Float) {
        if (band == activeBand) return
        activeBand = band
        highPass = BiquadFilter.highPass(
            cutoffHz = band.lowHz,
            q = 0.707f,
            sampleRate = sampleRate,
        )
        lowPass = BiquadFilter.lowPass(
            cutoffHz = band.highHz,
            q = 0.707f,
            sampleRate = sampleRate,
        )
    }

    fun nextSample(): Float {
        counter++
        var changed = counter
        var row = 0
        while ((changed and 1) == 0 && row < rows.size) {
            runningSum -= rows[row]
            rows[row] = randomUnit()
            runningSum += rows[row]
            changed = changed shr 1
            row++
        }

        val white = randomUnit()
        val pink = ((runningSum + white) / (rows.size + 1)) * 1.6f
        val shaped = lowPass.process(highPass.process(pink))
        return shaped.coerceIn(-1f, 1f)
    }

    private fun randomUnit(): Float = (random.nextFloat() * 2f) - 1f
}

private class BiquadFilter(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float,
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(input: Float): Float {
        val output = (b0 * input) + (b1 * x1) + (b2 * x2) - (a1 * y1) - (a2 * y2)
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }

    companion object {
        fun identity() = BiquadFilter(1f, 0f, 0f, 0f, 0f)

        fun highPass(cutoffHz: Float, q: Float, sampleRate: Float): BiquadFilter {
            val omega = (2.0 * PI * cutoffHz / sampleRate).toFloat()
            val alpha = (sin(omega) / (2f * q))
            val cosOmega = cos(omega)
            val b0 = (1f + cosOmega) / 2f
            val b1 = -(1f + cosOmega)
            val b2 = (1f + cosOmega) / 2f
            val a0 = 1f + alpha
            val a1 = -2f * cosOmega
            val a2 = 1f - alpha
            return normalize(b0, b1, b2, a0, a1, a2)
        }

        fun lowPass(cutoffHz: Float, q: Float, sampleRate: Float): BiquadFilter {
            val omega = (2.0 * PI * cutoffHz / sampleRate).toFloat()
            val alpha = sin(omega) / (2f * q)
            val cosOmega = cos(omega)
            val b0 = (1f - cosOmega) / 2f
            val b1 = 1f - cosOmega
            val b2 = (1f - cosOmega) / 2f
            val a0 = 1f + alpha
            val a1 = -2f * cosOmega
            val a2 = 1f - alpha
            return normalize(b0, b1, b2, a0, a1, a2)
        }

        private fun normalize(
            b0: Float,
            b1: Float,
            b2: Float,
            a0: Float,
            a1: Float,
            a2: Float,
        ): BiquadFilter = BiquadFilter(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = a1 / a0,
            a2 = a2 / a0,
        )
    }
}

fun sliderToFrequency(position: Float): Float {
    val minHz = 20.0
    val maxHz = 20_000.0
    return (minHz * (maxHz / minHz).pow(position.toDouble())).toFloat()
}

fun frequencyToSlider(frequencyHz: Float): Float {
    val minHz = 20.0
    val maxHz = 20_000.0
    return (ln(frequencyHz / minHz) / ln(maxHz / minHz)).toFloat()
}
