package com.taotao.music.data

import android.content.Context
import android.content.SharedPreferences
import com.taotao.music.model.SoundEffectPreset
import com.taotao.music.model.SoundEffectFrequenciesHz
import com.taotao.music.model.SoundEffectsSettings
import com.taotao.music.model.migrateLegacySoundEffectGains
import org.json.JSONArray
import org.json.JSONObject

/** 小体积设备偏好一次写入一个键，服务不会读到半套预设或频段。 */
class SoundEffectsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("sound_effects", Context.MODE_PRIVATE)

    fun read(): SoundEffectsSettings = decode(preferences.getString(KEY, null))

    fun write(settings: SoundEffectsSettings) {
        preferences.edit().putString(KEY, encode(settings)).apply()
    }

    fun observe(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val KEY = "settings_v1"

        internal fun encode(settings: SoundEffectsSettings): String {
            val safe = settings.normalized()
            return JSONObject().apply {
                put("schema", 2)
                put("enabled", safe.enabled)
                put("preset", safe.preset.name)
                put("gains", JSONArray(safe.customGains))
                put("bass", safe.bassStrength)
                put("spatial", safe.spatialStrength)
                put("reverb", safe.reverbStrength)
            }.toString()
        }

        internal fun decode(value: String?): SoundEffectsSettings {
            if (value == null) return SoundEffectsSettings()
            return runCatching {
                val json = JSONObject(value)
                val gains = json.optJSONArray("gains")
                val customGains = if (json.optInt("schema", 1) < 2) {
                    migrateLegacySoundEffectGains(List(5) { gains?.optInt(it, 0) ?: 0 })
                } else {
                    List(SoundEffectFrequenciesHz.size) { gains?.optInt(it, 0) ?: 0 }
                }
                SoundEffectsSettings(
                    enabled = json.optBoolean("enabled", false),
                    preset = SoundEffectPreset.of(json.optString("preset")),
                    customGains = customGains,
                    bassStrength = json.optInt("bass", 0),
                    spatialStrength = json.optInt("spatial", 0),
                    reverbStrength = json.optInt("reverb", 0),
                ).normalized()
            }.getOrDefault(SoundEffectsSettings())
        }
    }
}
