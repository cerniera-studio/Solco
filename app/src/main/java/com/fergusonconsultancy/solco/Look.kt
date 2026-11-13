package com.fergusonconsultancy.solco

import android.app.WallpaperColors
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * A finish: the ground, the fine grooves, three grain colours, and the disc by day and by night.
 * Colours are 0xRRGGBB. Visconti keeps the Fold family's palette; Carbonio is taken from the stipple prints
 * Solco began from; Notte, Tabacco, Grafite and Bronzo are its darker companions.
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
    // 11 Nov 2026: Avorio (too light) became Tabacco and Rosso (its tone) became Notte; Grafite and Bronzo added
    VISCONTI("Visconti", 0x0E1311, 0xD9BB86, 0.10f, intArrayOf(0xD9BB86, 0xF6F1E6, 0x96805C), 0.72f, 0xECE0C8, 0xC9A064, 0x0E1311, false),
    CARBONIO("Carbonio", 0x0B0B0C, 0xF6F1E6, 0.08f, intArrayOf(0xE2DED6, 0xB2382A, 0x76767A), 0.78f, 0xF0ECE4, 0xCEB28A, 0x0B0B0C, false),
    NOTTE("Notte", 0x0A0F1E, 0xD9BB86, 0.09f, intArrayOf(0xEEEBE4, 0xD9BB86, 0x5C6C8E), 0.74f, 0xEEF0F5, 0xD4D8E2, 0x0A0F1E, false),
    TABACCO("Tabacco", 0x1C140E, 0xE6D4B0, 0.09f, intArrayOf(0xE6D6B4, 0xC28C44, 0x7E5C3C), 0.76f, 0xF2E2C2, 0xCC9A58, 0x1C140E, false),
    GRAFITE("Grafite", 0x18191B, 0xC8CCD2, 0.08f, intArrayOf(0xD4D7DC, 0x8C9096, 0x55595F), 0.74f, 0xE8EAEC, 0xB4B8BE, 0x18191B, false),
    BRONZO("Bronzo", 0x0D0B09, 0xC8894A, 0.09f, intArrayOf(0xD69C5E, 0xB4683A, 0xEAD4A8), 0.78f, 0xF2CC96, 0xB97C46, 0x0D0B09, false),
}

/** A style is a different force acting on the grain. Each says what puts it where it is. */
enum class Style(val title: String, val note: String) {
    SOLCO("Solco", "Rings centred on the hinge. Folding stirs the grain along the swing; a fast fold leaves the bands emptier."),
    TRONCO("Tronco", "One ring a day, as wide as that day’s unfolded use: pale early grain, then a dense line where the day closed. The trunk begins as a core on the day you set it."),
    MAREA("Marea", "The grain fills to your battery's level, foam at the surface and sediment at the floor. On charge the surface breathes; otherwise it is still."),
    DUNA("Duna", "Wind ripples blowing out from the hinge, gentle windward and steep on the lee. Tilt past the angle of repose and the loosest grain slides downhill; level the phone and the wind blows it home."),
}

/**
 * Concentrato's three choices (13 Nov 2026), shown under its switch while it is on.
 * The Fold's hinge reads a swing in two steps (about 225 °/s or about 450 °/s, 13 Nov), so "fast"
 * is offered as two choices rather than a slider that would pretend to finer readings.
 */
enum class Trigger(val title: String, val above: Float) {
    BRISK("Brisk", 180f),
    FLICK("Only a flick", com.cerniera.kit.Hinge.FAST),
}

/** How much falls away: Solco's share of the grain, and the share of Sipario's ornament that stays. */
enum class Depth(val title: String, val grain: Float, val ornament: Float) {
    TOUCH("A touch", .35f, .75f),
    HALF("Half", .55f, .5f),
    MOST("Most", .75f, .25f),
}

/** How slowly Solco's grain fills back in: the share of the emptiness left after each frame at rest. */
enum class Linger(val title: String, val decay: Float) {
    BREATH("A breath", .95f),
    LONGER("Longer", .98f),
}

object Prefs {
    private const val NAME = "solco"
    /** The keys that change how the wallpaper looks. Anything else (growth, diagnostics) must not redraw it. */
    private val LOOK = setOf("style", "finish", "luna", "concentrato")

    @JvmStatic fun of(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    @JvmStatic fun isLook(key: String?) = key != null && key in LOOK

    fun style(c: Context) = runCatching { Style.valueOf(of(c).getString("style", null) ?: "SOLCO") }.getOrDefault(Style.SOLCO)
    fun finish(c: Context): Finish {
        val name = when (val n = of(c).getString("finish", null)) { "AVORIO" -> "TABACCO"; "ROSSO" -> "NOTTE"; null -> "VISCONTI"; else -> n }
        return runCatching { Finish.valueOf(name) }.getOrDefault(Finish.VISCONTI)
    }
    fun luna(c: Context) = of(c).getBoolean("luna", true)
    fun concentrato(c: Context) = of(c).getBoolean("concentrato", true)
    @JvmStatic fun trigger(c: Context) = runCatching { Trigger.valueOf(of(c).getString("conc_trigger", null) ?: "FLICK") }.getOrDefault(Trigger.FLICK)
    @JvmStatic fun depth(c: Context) = runCatching { Depth.valueOf(of(c).getString("conc_depth", null) ?: "MOST") }.getOrDefault(Depth.MOST)
    @JvmStatic fun linger(c: Context) = runCatching { Linger.valueOf(of(c).getString("conc_linger", null) ?: "BREATH") }.getOrDefault(Linger.BREATH)
    fun setTrigger(c: Context, v: Trigger) = of(c).edit().putString("conc_trigger", v.name).apply()
    fun setDepth(c: Context, v: Depth) = of(c).edit().putString("conc_depth", v.name).apply()
    fun setLinger(c: Context, v: Linger) = of(c).edit().putString("conc_linger", v.name).apply()

    fun setStyle(c: Context, s: Style) = of(c).edit().putString("style", s.name).apply()
    fun setFinish(c: Context, f: Finish) = of(c).edit().putString("finish", f.name).apply()
    fun setLuna(c: Context, on: Boolean) = of(c).edit().putBoolean("luna", on).apply()
    fun setConcentrato(c: Context, on: Boolean) = of(c).edit().putBoolean("concentrato", on).apply()

    /** Sipario's scene. Its engine listens for this key alone. */
    @JvmStatic fun act(c: Context) = runCatching { Act.valueOf(of(c).getString("sipario", null) ?: "CATENARIA") }.getOrDefault(Act.CATENARIA)
    fun setAct(c: Context, a: Act) = of(c).edit().putString("sipario", a.name).apply()
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
