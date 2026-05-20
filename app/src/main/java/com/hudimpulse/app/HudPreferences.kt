package com.hudimpulse.app

import android.content.Context
import android.content.SharedPreferences

/**
 * All user-customizable settings for HUD Impulse.
 */
class HudPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hud_prefs", Context.MODE_PRIVATE)

    // ── Text & Arrow Sizes ──
    var fontSize: Int
        get() = prefs.getInt("font_size", 24)
        set(v) { prefs.edit().putInt("font_size", v.coerceIn(8, 60)).apply() }

    var arrowSize: Int
        get() = prefs.getInt("arrow_size", 48)
        set(v) { prefs.edit().putInt("arrow_size", v.coerceIn(20, 120)).apply() }

    var distanceFontSize: Int
        get() = prefs.getInt("distance_font_size", 32)
        set(v) { prefs.edit().putInt("distance_font_size", v.coerceIn(8, 60)).apply() }

    // ── Colors ──
    var textColor: Int
        get() = prefs.getInt("text_color", 0xFFFFFFFF.toInt()) // White
        set(v) { prefs.edit().putInt("text_color", v).apply() }

    var arrowColor: Int
        get() = prefs.getInt("arrow_color", 0xFFFFFFFF.toInt()) // White
        set(v) { prefs.edit().putInt("arrow_color", v).apply() }

    // ── Background ──
    var backgroundTransparent: Boolean
        get() = prefs.getBoolean("bg_transparent", true)
        set(v) { prefs.edit().putBoolean("bg_transparent", v).apply() }

    // ── Alignment ──
    /** 0=Left, 1=Center, 2=Right */
    var horizontalAlign: Int
        get() = prefs.getInt("h_align", 1) // Center
        set(v) { prefs.edit().putInt("h_align", v.coerceIn(0, 2)).apply() }

    /** 0=Top, 1=Center, 2=Bottom */
    var verticalAlign: Int
        get() = prefs.getInt("v_align", 1) // Center
        set(v) { prefs.edit().putInt("v_align", v.coerceIn(0, 2)).apply() }

    // ── Padding ──
    var paddingH: Int
        get() = prefs.getInt("padding_h", 16)
        set(v) { prefs.edit().putInt("padding_h", v.coerceIn(0, 100)).apply() }

    var paddingV: Int
        get() = prefs.getInt("padding_v", 8)
        set(v) { prefs.edit().putInt("padding_v", v.coerceIn(0, 100)).apply() }

    // ── Display ──
    var displayWidth: Int
        get() = prefs.getInt("display_width", 480)
        set(v) { prefs.edit().putInt("display_width", v.coerceIn(320, 3840)).apply() }

    var displayHeight: Int
        get() = prefs.getInt("display_height", 240)
        set(v) { prefs.edit().putInt("display_height", v.coerceIn(120, 2160)).apply() }

    var displayDpi: Int
        get() = prefs.getInt("display_dpi", 160)
        set(v) { prefs.edit().putInt("display_dpi", v.coerceIn(72, 640)).apply() }

    // ── Visibility toggles ──
    var showArrow: Boolean
        get() = prefs.getBoolean("show_arrow", true)
        set(v) { prefs.edit().putBoolean("show_arrow", v).apply() }

    var showDistance: Boolean
        get() = prefs.getBoolean("show_distance", true)
        set(v) { prefs.edit().putBoolean("show_distance", v).apply() }

    var showStreetName: Boolean
        get() = prefs.getBoolean("show_street", true)
        set(v) { prefs.edit().putBoolean("show_street", v).apply() }

    // Dentro da sua classe HudPreferences
    // Versão corrigida para usar o nome correto da variável: prefs
    var autoStartOnConnect: Boolean
        get() = prefs.getBoolean("auto_start_on_connect", false)
        set(value) { prefs.edit().putBoolean("auto_start_on_connect", value).apply() }
}
