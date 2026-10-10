package com.audiotester

import java.util.Locale
import kotlin.math.*

enum class EqFilterType(val label: String) {
    PEAK_DIP("Peak/dip"), LOW_SHELF("Low shelf"), HIGH_SHELF("High shelf"),
}

data class EqBand(
    val id: Long,
    val type: EqFilterType = EqFilterType.PEAK_DIP,
    val frequencyHz: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val enabled: Boolean = true,
) {
    fun validated() = copy(
        frequencyHz = frequencyHz.takeIf { it.isFinite() }?.coerceIn(20f, 20_000f) ?: 1000f,
        gainDb = gainDb.takeIf { it.isFinite() }?.coerceIn(-15f, 15f) ?: 0f,
        q = q.takeIf { it.isFinite() }?.coerceIn(0.1f, 10f) ?: 1f,
    )
}

data class EqSettings(val enabled: Boolean = true, val bands: List<EqBand> = defaultEqBands())

fun defaultEqBands() = listOf(
    EqBand(1, EqFilterType.LOW_SHELF, 100f),
    EqBand(2, frequencyHz = 250f),
    EqBand(3, frequencyHz = 1000f),
    EqBand(4, frequencyHz = 2000f),
    EqBand(5, EqFilterType.HIGH_SHELF, 6000f),
)

/** RBJ / W3C Audio EQ Cookbook, with alpha = sin(w0)/(2Q), also for shelves.
 * Shelf Q controls resonance; Q > 1/sqrt(2) can overshoot the shelf gain.
 * Double precision is important for low-frequency, high-Q filters at 48 kHz.
 */
data class EqCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun responseDb(frequencyHz: Double, sampleRate: Double = 48_000.0): Double {
        val w = 2 * PI * frequencyHz / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = -b1 * s1 - b2 * s2
        val dr = 1 + a1 * c1 + a2 * c2
        val di = -a1 * s1 - a2 * s2
        return 10 * log10(((nr * nr + ni * ni) / (dr * dr + di * di)).coerceAtLeast(1e-30))
    }

    companion object {
        val IDENTITY = EqCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        fun from(rawBand: EqBand, sampleRate: Double = 48_000.0): EqCoefficients {
            val band = rawBand.validated()
            if (!band.enabled || band.gainDb == 0f) return IDENTITY
            val a = 10.0.pow(band.gainDb / 40.0)
            val w = 2 * PI * band.frequencyHz.coerceAtMost((sampleRate * 0.499).toFloat()) / sampleRate
            val c = cos(w)
            val alpha = sin(w) / (2 * band.q)
            val beta = 2 * sqrt(a) * alpha
            val values = when (band.type) {
                EqFilterType.PEAK_DIP -> doubleArrayOf(
                    1 + alpha * a, -2 * c, 1 - alpha * a,
                    1 + alpha / a, -2 * c, 1 - alpha / a,
                )
                EqFilterType.LOW_SHELF -> doubleArrayOf(
                    a * ((a + 1) - (a - 1) * c + beta),
                    2 * a * ((a - 1) - (a + 1) * c),
                    a * ((a + 1) - (a - 1) * c - beta),
                    (a + 1) + (a - 1) * c + beta,
                    -2 * ((a - 1) + (a + 1) * c),
                    (a + 1) + (a - 1) * c - beta,
                )
                EqFilterType.HIGH_SHELF -> doubleArrayOf(
                    a * ((a + 1) + (a - 1) * c + beta),
                    -2 * a * ((a - 1) + (a + 1) * c),
                    a * ((a + 1) + (a - 1) * c - beta),
                    (a + 1) - (a - 1) * c + beta,
                    2 * ((a - 1) - (a + 1) * c),
                    (a + 1) - (a - 1) * c - beta,
                )
            }
            val a0 = values[3]
            return EqCoefficients(values[0] / a0, values[1] / a0, values[2] / a0, values[4] / a0, values[5] / a0)
        }
    }
}

class EqFilter(private val c: EqCoefficients) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(input: Double): Double {
        val output = c.b0 * input + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1; x1 = input
        y2 = y1; y1 = output
        return output
    }
}

/** Immutable coefficients prepared outside the audio thread. Headroom covers the entire
 * 0..Nyquist response, including shelf resonance and boosts from overlapping bands.
 */
class EqPlan(val settings: EqSettings, val sampleRate: Double = 48_000.0) {
    val coefficients = settings.bands.filter { it.enabled && it.gainDb != 0f }.map { EqCoefficients.from(it, sampleRate) }
    fun responseDb(hz: Double): Double = coefficients.sumOf { it.responseDb(hz, sampleRate) }
    val headroomDb: Double = findHeadroom()
    val appliedHeadroomDb: Double get() = if (settings.enabled) headroomDb else 0.0

    private fun findHeadroom(): Double {
        if (coefficients.isEmpty()) return 0.0
        val nyquist = sampleRate / 2
        val frequencies = (0..2048).map { (nyquist - 0.001).pow(it / 2048.0) } +
            settings.bands.filter { it.enabled }.map { it.frequencyHz.toDouble() }
        val points = (listOf(0.0, nyquist) + frequencies).sorted()
        val values = points.map(::responseDb)
        var peak = max(0.0, values.max())
        // Refine every local maximum so even a narrow resonance between grid points is covered.
        for (i in 1 until points.lastIndex) {
            if (values[i] < values[i - 1] || values[i] < values[i + 1]) continue
            var low = points[i - 1]
            var high = points[i + 1]
            repeat(24) {
                val left = low + (high - low) / 3
                val right = high - (high - low) / 3
                if (responseDb(left) < responseDb(right)) low = left else high = right
            }
            peak = max(peak, responseDb((low + high) / 2))
        }
        // Round upward to 0.1 dB rather than understating required attenuation.
        return if (peak < 1e-6) 0.0 else ceil(peak * 10) / 10
    }

    companion object { val BYPASS = EqPlan(EqSettings(enabled = false, bands = emptyList())) }
}

private class EqProcessor(plan: EqPlan) {
    private val filters = if (plan.settings.enabled) plan.coefficients.map(::EqFilter) else emptyList()
    private val gain = 10.0.pow(-plan.appliedHeadroomDb / 20)
    fun process(input: Double): Double {
        var output = input * gain
        for (filter in filters) output = filter.process(output)
        return output
    }
}

/** Crossfade filter changes over ~43 ms. Only the render thread owns filter delay state. */
class EqRenderer {
    private var plan = EqPlan.BYPASS
    private var current = EqProcessor(plan)
    private var previous: EqProcessor? = null
    private var pending: EqPlan? = null
    private var remaining = 0

    fun setPlan(next: EqPlan) {
        if (remaining > 0) {
            // Keep the running fade intact during a fast drag; apply the latest edit next.
            pending = next.takeUnless { it === plan }
            return
        }
        if (next === plan) return
        plan = next
        previous = current
        current = EqProcessor(next)
        remaining = 2048
    }

    fun process(input: Double): Double {
        val output = current.process(input)
        val old = previous ?: return output
        val mix = 1.0 - remaining / 2048.0
        val result = old.process(input) * (1 - mix) + output * mix
        remaining--
        if (remaining == 0) {
            previous = null
            val next = pending
            pending = null
            if (next != null) setPlan(next)
        }
        return result
    }
}

fun eqParameterSheet(settings: EqSettings, presetName: String?, plan: EqPlan): String = buildString {
    appendLine("Pink noise parametric EQ")
    appendLine("Preset\t${presetName ?: "Unsaved"}")
    appendLine("EQ\t${if (settings.enabled) "On" else "Off"}")
    appendLine("Headroom attenuation (dB)\t${String.format(Locale.US, "%.1f", plan.appliedHeadroomDb)}")
    appendLine("Band\tEnabled\tType\tFrequency (Hz)\tGain (dB)\tQ")
    settings.bands.forEachIndexed { index, band ->
        appendLine(String.format(Locale.US, "%d\t%s\t%s\t%.0f\t%.1f\t%.2f",
            index + 1, if (band.enabled) "Yes" else "No", band.type.label, band.frequencyHz, band.gainDb, band.q))
    }
}
