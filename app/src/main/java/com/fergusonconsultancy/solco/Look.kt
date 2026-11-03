package com.fergusonconsultancy.solco

import android.app.WallpaperColors
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * A finish: the ground, the fine grooves, three grain colours, and the disc by day and by night.
 * Colours are 0xRRGGBB. Visconti keeps the Fold family's palette; Avorio is closest to the stipple
 * prints Solco began from; Carbonio and Rosso are taken from the same prints.
 */
enum class Finish(
    val title: String,
    val ground: Int,
    val groove: Int,
    val grooveA: Float,
    val grain: IntArray,
    val grainA: Float,
    val discDay: Int,
    val discNight: Int,
    val speck: Int,
    val light: Boolean,
) {
    VISCONTI("Visconti", 0x0E1311, 0xD9BB86, 0.10f, intArrayOf(0xD9BB86, 0xF6F1E6, 0x96805C), 0.72f, 0xECE0C8, 0xC9A064, 0x0E1311, false),
    AVORIO("Avorio", 0xECE6DA, 0x1E2430, 0.42f, intArrayOf(0x1E2A3A, 0xA63A2A, 0x5A6270), 0.90f, 0xB03426, 0x6E161A, 0xECE6DA, true),
    CARBONIO("Carbonio", 0x0B0B0C, 0xF6F1E6, 0.08f, intArrayOf(0xE2DED6, 0xB2382A, 0x76767A), 0.78f, 0xF0ECE4, 0xCEB28A, 0x0B0B0C, false),
    ROSSO("Rosso", 0x360D0F, 0xD9BB86, 0.10f, intArrayOf(0xD9BB86, 0xF6F1E6, 0x962A26), 0.76f, 0xF0E4CE, 0xD2AA6E, 0x360D0F, false),
}

/** A style is a different force acting on the grain. Each says what puts it where it is. */
enum class Style(val title: String, val note: String) {
    SOLCO("Solco", "Rings centred on the hinge. Folding stirs the grain along the swing; a fast fold leaves the bands emptier."),
    TRONCO("Tronco", "One ring a day, as wide as that day’s unfolded use: pale early grain, then a dense line where the day closed. The trunk begins as a core on the day you set it."),
}

object Prefs {
    private const val NAME = "solco"
    /** The keys that change how the wallpaper looks. Anything else (growth, diagnostics) must not redraw it. */
    private val LOOK = setOf("style", "finish", "luna", "concentrato")

    @JvmStatic fun of(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    @JvmStatic fun isLook(key: String?) = key != null && key in LOOK

    fun style(c: Context) = runCatching { Style.valueOf(of(c).getString("style", null) ?: "SOLCO") }.getOrDefault(Style.SOLCO)
    fun finish(c: Context) = runCatching { Finish.valueOf(of(c).getString("finish", null) ?: "VISCONTI") }.getOrDefault(Finish.VISCONTI)
    fun luna(c: Context) = of(c).getBoolean("luna", true)
    fun concentrato(c: Context) = of(c).getBoolean("concentrato", true)

    fun setStyle(c: Context, s: Style) = of(c).edit().putString("style", s.name).apply()
    fun setFinish(c: Context, f: Finish) = of(c).edit().putString("finish", f.name).apply()
    fun setLuna(c: Context, on: Boolean) = of(c).edit().putBoolean("luna", on).apply()
    fun setConcentrato(c: Context, on: Boolean) = of(c).edit().putBoolean("concentrato", on).apply()
}

object Look {
    private fun opaque(rgb: Int) = Color.valueOf(rgb or 0xFF000000.toInt())

    /** What the system reads for themed colours: the ground first, then the grain and the sun. */
    @JvmStatic fun colors(c: Context): WallpaperColors {
        val f = Prefs.finish(c)
        return WallpaperColors(opaque(f.ground), opaque(f.grain[0]), opaque(f.discDay))
    }
}

/** The same seeded generator as the browser preview, so the grain lies the same way on the phone. */
class Rng(seed: Int) {
    private var s = seed.toLong()
    fun next(): Float {
        s = (s * 16807L) % 2147483647L
        return ((s - 1).toDouble() / 2147483646.0).toFloat()
    }
}
