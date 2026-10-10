package com.audiotester

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class EqSession(
    val settings: EqSettings = EqSettings(),
    val presets: Map<String, EqSettings> = emptyMap(),
    val activePreset: String? = null,
) {
    fun deletePreset(name: String) = copy(presets = presets - name, activePreset = activePreset.takeUnless { it == name })
    fun resetToDefault() = copy(settings = EqSettings(), activePreset = null)

    val presetLabel: String? get() = activePreset?.let {
        if (presets[it] == settings) it else "$it (modified)"
    }
}

/** One versioned snapshot keeps the current EQ and named presets consistent. */
class EqRepository(context: Context) {
    private val preferences = context.getSharedPreferences("parametric_eq", Context.MODE_PRIVATE)

    fun load(): EqSession {
        val text = preferences.getString("session", null) ?: return EqSession()
        return try {
            EqSessionCodec.decode(text)
        } catch (_: JSONException) {
            EqSession()
        }
    }

    fun save(session: EqSession) {
        preferences.edit { putString("session", EqSessionCodec.encode(session)) }
    }
}

internal object EqSessionCodec {
    fun decode(text: String): EqSession {
        val root = JSONObject(text)
        val presets = root.optJSONObject("presets") ?: JSONObject()
        val loaded = presets.keys().asSequence().associateWith { readSettings(presets.getJSONObject(it)) }
        return EqSession(
            readSettings(root.getJSONObject("settings")), loaded,
            if (root.isNull("activePreset")) null else root.optString("activePreset").takeIf { it in loaded },
        )
    }

    fun encode(session: EqSession): String {
        val presets = JSONObject()
        session.presets.forEach { (name, settings) -> presets.put(name, writeSettings(settings)) }
        val root = JSONObject().put("version", 1)
            .put("settings", writeSettings(session.settings))
            .put("presets", presets)
            .put("activePreset", session.activePreset ?: JSONObject.NULL)
        return root.toString()
    }

    private fun writeSettings(settings: EqSettings): JSONObject {
        val bands = JSONArray()
        settings.bands.forEach { band ->
            bands.put(JSONObject().put("id", band.id).put("type", band.type.name).put("frequencyHz", band.frequencyHz)
                .put("gainDb", band.gainDb).put("q", band.q).put("enabled", band.enabled))
        }
        return JSONObject().put("enabled", settings.enabled).put("bands", bands)
    }

    private fun readSettings(root: JSONObject): EqSettings {
        val bands = root.getJSONArray("bands")
        return EqSettings(root.optBoolean("enabled", true), (0 until bands.length()).map { i ->
            val band = bands.getJSONObject(i)
            EqBand(
                id = band.optLong("id", i + 1L),
                type = EqFilterType.entries.find { it.name == band.optString("type") } ?: EqFilterType.PEAK_DIP,
                frequencyHz = band.optDouble("frequencyHz", 1000.0).toFloat(),
                gainDb = band.optDouble("gainDb", 0.0).toFloat(),
                q = band.optDouble("q", 1.0).toFloat(),
                enabled = band.optBoolean("enabled", true),
            ).validated()
        })
    }
}
