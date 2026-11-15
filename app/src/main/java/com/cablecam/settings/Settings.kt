package com.cablecam.settings

import android.content.Context
import android.content.SharedPreferences

enum class Mode(val label: String) { CABLE("Cable (USB)"), WIRELESS("Wireless (Wi-Fi / Bluetooth)") }

/** User settings, persisted in SharedPreferences. Unknown stored values fall back to the defaults. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mode: Mode
        get() = Mode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: Mode.CABLE
        set(v) = prefs.edit().putString("mode", v.name).apply()

    var frontCamera: Boolean
        get() = prefs.getBoolean("frontCamera", false)
        set(v) = prefs.edit().putBoolean("frontCamera", v).apply()

    var height: Int
        get() = prefs.getInt("height", 720).takeIf { it in Presets.HEIGHTS } ?: 720
        set(v) = prefs.edit().putInt("height", v).apply()

    var fps: Int
        get() = Presets.fpsFor(height, prefs.getInt("fps", 30).takeIf { it in Presets.FPS } ?: 30)
        set(v) = prefs.edit().putInt("fps", v).apply()

    /** 0 = automatic. */
    var bitrateKbps: Int
        get() = prefs.getInt("bitrateKbps", 0).takeIf { it in Presets.BITRATES_KBPS } ?: 0
        set(v) = prefs.edit().putInt("bitrateKbps", v).apply()

    var stabilise: Boolean
        get() = prefs.getBoolean("stabilise", false)
        set(v) = prefs.edit().putBoolean("stabilise", v).apply()
}
