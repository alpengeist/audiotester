package com.audiotester

import org.junit.Assert.*
import org.junit.Test

class EqPersistenceTest {
    @Test fun sessionAndMultipleNamedPresetsSurviveRoundTrip() {
        val one = EqSettings(bands = listOf(EqBand(2, gainDb = -4.5f), EqBand(8, EqFilterType.HIGH_SHELF, 7500f, 2f, 0.7f, false)))
        val two = EqSettings(enabled = false, bands = emptyList())
        val session = EqSession(one, mapOf("Headphones A" to one, "耳机 B" to two), "Headphones A")
        val loaded = EqSessionCodec.decode(EqSessionCodec.encode(session))
        assertEquals(session, loaded)
        assertEquals("Headphones A", loaded.presetLabel)
    }

    @Test fun unsavedChangesDoNotOverwriteNamedPreset() {
        val saved = EqSettings()
        val edited = saved.copy(bands = saved.bands.drop(1))
        val session = EqSession(edited, mapOf("Reference" to saved), "Reference")
        val loaded = EqSessionCodec.decode(EqSessionCodec.encode(session))
        assertEquals(edited, loaded.settings)
        assertEquals(saved, loaded.presets["Reference"])
        assertEquals("Reference (modified)", loaded.presetLabel)
    }

    @Test fun deletedPresetStaysDeletedAndKeepsCurrentEq() {
        val settings = EqSettings(bands = listOf(EqBand(7, gainDb = 3f)))
        val original = EqSession(settings, mapOf("A" to settings, "B" to EqSettings()), "A")
        val deleted = original.deletePreset("A")
        val loaded = EqSessionCodec.decode(EqSessionCodec.encode(deleted))
        assertEquals(settings, loaded.settings)
        assertEquals(setOf("B"), loaded.presets.keys)
        assertNull(loaded.activePreset)
    }

    @Test fun deletingAnotherPresetPreservesCurrentPresetAndUnsavedEdits() {
        val saved = EqSettings()
        val edited = saved.copy(enabled = false)
        val session = EqSession(edited, mapOf("A" to saved, "B" to EqSettings(bands = emptyList())), "A")
        val loaded = EqSessionCodec.decode(EqSessionCodec.encode(session.deletePreset("B")))
        assertEquals("A", loaded.activePreset)
        assertEquals(edited, loaded.settings)
        assertEquals(setOf("A"), loaded.presets.keys)
    }

    @Test fun resetRestoresFiveFlatBandsWithoutDeletingSavedPresets() {
        val settings = EqSettings(enabled = false, bands = listOf(EqBand(7, gainDb = 12f)))
        val original = EqSession(settings, mapOf("Reference" to settings), "Reference")
        val loaded = EqSessionCodec.decode(EqSessionCodec.encode(original.resetToDefault()))
        assertEquals(EqSettings(), loaded.settings)
        assertEquals(original.presets, loaded.presets)
        assertNull(loaded.activePreset)
    }

    @Test fun loadingClampsInvalidParameterValues() {
        val loaded = EqSessionCodec.decode("""{"settings":{"bands":[{"frequencyHz":30000,"gainDb":40,"q":0}]}}""")
        val band = loaded.settings.bands.single()
        assertEquals(20_000f, band.frequencyHz, 0f)
        assertEquals(15f, band.gainDb, 0f)
        assertEquals(0.1f, band.q, 0f)
    }

    @Test fun presetNamedNullDoesNotBecomeActiveWhenNoPresetIsSelected() {
        val session = EqSession(presets = mapOf("null" to EqSettings()))
        val loaded = EqSessionCodec.decode(EqSessionCodec.encode(session))
        assertNull(loaded.activePreset)
    }
}
