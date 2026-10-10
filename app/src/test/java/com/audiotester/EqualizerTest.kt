package com.audiotester

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class EqualizerTest {
    @Test fun initialConfigurationHasFiveFlatBands() {
        val settings = EqSettings()
        assertEquals(5, settings.bands.size)
        assertEquals(5, settings.bands.map { it.id }.distinct().size)
        val plan = EqPlan(settings)
        for (hz in listOf(20.0, 100.0, 1000.0, 20_000.0)) assertEquals(0.0, plan.responseDb(hz), 1e-9)
    }

    @Test fun peakingFilterHasRequestedCenterGain() {
        for (gain in listOf(-15f, -3f, 6f, 15f)) {
            for (hz in listOf(20f, 1000f, 20_000f)) {
                for (q in listOf(0.1f, 1f, 10f)) {
                    val coefficients = EqCoefficients.from(EqBand(1, frequencyHz = hz, gainDb = gain, q = q))
                    assertEquals(gain.toDouble(), coefficients.responseDb(hz.toDouble()), 1e-6)
                }
            }
        }
    }

    @Test fun shelvesHaveCorrectEndpointAndMidpointGains() {
        for (gain in listOf(-15f, 6f, 15f)) {
            val low = EqCoefficients.from(EqBand(1, EqFilterType.LOW_SHELF, 1000f, gain, 0.707f))
            val high = EqCoefficients.from(EqBand(1, EqFilterType.HIGH_SHELF, 1000f, gain, 0.707f))
            assertEquals(gain.toDouble(), low.responseDb(0.0), 1e-8)
            assertEquals(0.0, low.responseDb(24_000.0), 1e-8)
            assertEquals(0.0, high.responseDb(0.0), 1e-8)
            assertEquals(gain.toDouble(), high.responseDb(24_000.0), 1e-8)
            assertEquals(gain / 2.0, low.responseDb(1000.0), 1e-8)
            assertEquals(gain / 2.0, high.responseDb(1000.0), 1e-8)
        }
    }

    @Test fun matchingBoostAndCutCancel() {
        for (type in EqFilterType.entries) {
            val plan = EqPlan(EqSettings(bands = listOf(EqBand(1, type, 1234f, 12f, 2.3f), EqBand(2, type, 1234f, -12f, 2.3f))))
            for (i in 0..100) assertEquals(0.0, plan.responseDb(20.0 * 1000.0.pow(i / 100.0)), 1e-7)
        }
    }

    @Test fun rendererPreservesCombinedBoostGain() {
        val plan = EqPlan(EqSettings(bands = listOf(EqBand(1, gainDb = 12f), EqBand(2, gainDb = 12f))))
        assertEquals(24.0, plan.responseDb(1000.0), 1e-8)
        val renderer = EqRenderer()
        renderer.setPlan(plan)
        var inputPower = 0.0
        var outputPower = 0.0
        for (i in 0 until 96_000) {
            val input = 0.01 * sin(2 * PI * 1000 * i / 48_000)
            val output = renderer.process(input)
            if (i >= 48_000) { inputPower += input * input; outputPower += output * output }
        }
        assertEquals(24.0, 10 * log10(outputPower / inputPower), 1e-8)
    }

    @Test fun disabledBandsDoNotAffectCurve() {
        val plan = EqPlan(EqSettings(bands = listOf(EqBand(1, gainDb = 15f, enabled = false))))
        assertEquals(0.0, plan.responseDb(1000.0), 0.0)
    }

    @Test fun globalBypassRemovesFiltersAfterTransition() {
        val settings = EqSettings(bands = listOf(EqBand(1, gainDb = 15f)))
        val renderer = EqRenderer()
        renderer.setPlan(EqPlan(settings))
        repeat(4096) { renderer.process(sin(it * 0.13)) }
        val bypass = EqPlan(settings.copy(enabled = false))
        renderer.setPlan(bypass)
        repeat(2048) { renderer.process(0.25) }
        assertEquals(0.25, renderer.process(0.25), 0.0)
    }

    @Test fun emptyEqualizerIsValid() {
        val plan = EqPlan(EqSettings(bands = emptyList()))
        val renderer = EqRenderer()
        renderer.setPlan(plan)
        repeat(4096) { assertEquals(0.42, renderer.process(0.42), 1e-12) }
    }

    @Test fun rapidEditsFinishCurrentFadeAndApplyLatestConfiguration() {
        val renderer = EqRenderer()
        renderer.setPlan(EqPlan(EqSettings(bands = listOf(EqBand(1, gainDb = 12f)))))
        repeat(512) { renderer.process(0.25) }
        renderer.setPlan(EqPlan(EqSettings(bands = listOf(EqBand(1, gainDb = -12f)))))
        renderer.setPlan(EqPlan.BYPASS)
        repeat(4096) { assertTrue(renderer.process(0.25).isFinite()) }
        assertEquals(0.25, renderer.process(0.25), 0.0)
    }

    @Test fun processedSineMatchesAnalyticalResponse() {
        val band = EqBand(1, EqFilterType.PEAK_DIP, 1000f, 9f, 1.4f)
        val coefficients = EqCoefficients.from(band)
        val filter = EqFilter(coefficients)
        var inputPower = 0.0
        var outputPower = 0.0
        for (i in 0 until 96_000) {
            val input = 0.1 * sin(2 * PI * 1000 * i / 48_000)
            val output = filter.process(input)
            if (i >= 48_000) { inputPower += input * input; outputPower += output * output }
        }
        assertEquals(coefficients.responseDb(1000.0), 10 * log10(outputPower / inputPower), 1e-8)
    }

    @Test fun extremeParametersRemainFiniteAndDecay() {
        for (type in EqFilterType.entries) for (hz in listOf(20f, 20_000f)) {
            for (q in listOf(0.1f, 10f)) for (gain in listOf(-15f, 15f)) {
                val filter = EqFilter(EqCoefficients.from(EqBand(1, type, hz, gain, q)))
                var last = 0.0
                repeat(240_000) { i ->
                    last = filter.process(if (i == 0) 1.0 else 0.0)
                    assertTrue(last.isFinite())
                }
                assertEquals(0.0, last, 1e-5)
            }
        }
    }

    @Test fun sheetIncludesDisabledBandsAndGlobalStateInPortableFormat() {
        val settings = EqSettings(enabled = false, bands = listOf(EqBand(1, gainDb = -3.5f, enabled = false)))
        val text = eqParameterSheet(settings, "Headphones")
        assertTrue(text.contains("Preset\tHeadphones"))
        assertTrue(text.contains("EQ\tOff"))
        assertTrue(text.contains("1\tNo\tPeak/dip\t1000\t-3.5\t1.00"))
    }
}
