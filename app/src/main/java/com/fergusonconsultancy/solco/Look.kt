package com.fergusonconsultancy.solco

import android.app.WallpaperColors
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * A finish: the ground, the fine grooves, three grain colours, and the disc by day and by night.
 * Colours are 0xRRGGBB. Visconti keeps the Fold family's palette; Carbonio is taken from the stipple prints
 * Solco began from; Notte, Tabacco, Grafite and Bronzo are its darker companions. Verderame (21 Nov 2026)
 * is the only cool, saturated one: copper's own patina, the grain itself verdigris, so it sits apart from
 * the ivory-grained six while pairing with Bronzo's copper.
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
    // In alphabetical order (28 Nov 2026), as Settings shows them. Avorio (too light) became Tabacco and
    // Rosso (its tone) became Notte on 11 Nov; Verderame made seven on 21 Nov, one for each day of the week
    BRONZO("Bronzo", 0x0D0B09, 0xC8894A, 0.09f, intArrayOf(0xD69C5E, 0xB4683A, 0xEAD4A8), 0.78f, 0xF2CC96, 0xB97C46, 0x0D0B09, false),
    CARBONIO("Carbonio", 0x0B0B0C, 0xF6F1E6, 0.08f, intArrayOf(0xE2DED6, 0xB2382A, 0x76767A), 0.78f, 0xF0ECE4, 0xCEB28A, 0x0B0B0C, false),
    GRAFITE("Grafite", 0x18191B, 0xC8CCD2, 0.08f, intArrayOf(0xD4D7DC, 0x8C9096, 0x55595F), 0.74f, 0xE8EAEC, 0xB4B8BE, 0x18191B, false),
    NOTTE("Notte", 0x0A0F1E, 0xD9BB86, 0.09f, intArrayOf(0xEEEBE4, 0xD9BB86, 0x5C6C8E), 0.74f, 0xEEF0F5, 0xD4D8E2, 0x0A0F1E, false),
    TABACCO("Tabacco", 0x1C140E, 0xE6D4B0, 0.09f, intArrayOf(0xE6D6B4, 0xC28C44, 0x7E5C3C), 0.76f, 0xF2E2C2, 0xCC9A58, 0x1C140E, false),
    VERDERAME("Verderame", 0x0A1F20, 0x9CC9B8, 0.09f, intArrayOf(0xA9CFC0, 0xC98A5A, 0x4F8676), 0.76f, 0xE4EFE9, 0xC99A6A, 0x0A1F20, false),
    VISCONTI("Visconti", 0x0E1311, 0xD9BB86, 0.10f, intArrayOf(0xD9BB86, 0xF6F1E6, 0x96805C), 0.72f, 0xECE0C8, 0xC9A064, 0x0E1311, false),
}

/** A style is a different force acting on the grain. Each says what puts it where it is. */
enum class Style(val title: String, val note: String) {
    // In alphabetical order (28 Nov 2026), as Settings shows them. Nebulosa gave way to Pioggia, Stagione and
    // Sentiero on 24 Nov; Deserto came on 25 Nov and Orma on 27 Nov; Duna and Polvere were retired on 28 Nov,
    // and Deserto's lock-screen clock with them; the Solco style became Orbita.
    DELTA("Delta", "Each charge carves a channel out from the hinge, as deep as the charge was long. Over a week they braid a delta, the oldest silting up; the newest channel's banks wear the accent. While the phone charges, grain runs down the newest channel from the hinge."),
    DESERTO("Deserto", "One great dune across the crease, lit from the side the sun or moon is on and at its real height: a low light throws the shade long, a high one draws it in under the crest. When the real wind outside blows hard enough to move sand, a plume streams off the crest the way it blows. At night the sky holds a few stars."),
    MAREA("Marea", "The grain fills to your battery's level, foam at the surface and sediment at the floor. On charge the surface breathes; otherwise it is still."),
    ORBITA("Orbita", "Rings centred on the hinge. Folding stirs the grain along the swing; a fast fold leaves the bands emptier. Hold a finger on the glass and the nearby rings bow towards it, then flow back when you let go."),
    ORMA("Orma", "Today's steps, pressed into the sand as a trail of footprints from the bottom of the glass. Brisk walking lengthens the stride, a run digs in the toes, and where you stood still the feet rest side by side. The prints soften as much as today's real wind has blown over them; the last hour's wear the accent. At midnight the sand is smooth again."),
    PIOGGIA("Pioggia", "The weather outside now, as a long exposure: rain in streaks slanted by the real wind, snow in soft clumps, a dry sky a haze as heavy as its cloud. Puddles on the floor hold as much rain as has fallen here today, and stay after it stops. Read from Open-Meteo for your place, every 20 minutes while it is on screen."),
    SENTIERO("Sentiero", "Raked gravel worn by your hand: each touch on the home screen today presses a hollow, each swipe drags a path, and the last hour's banks wear the accent. At midnight it is raked again. Kept on the phone."),
    STAGIONE("Stagione", "One ray for each day of the year, fanned from the hinge, as long as that day's daylight here. Days gone are dense, days to come faint; today's ray fills in the accent as the daylight passes, from sunrise to sunset."),
    TRONCO("Tronco", "One ring a day, as wide as that day’s unfolded use. Across each ring runs its day from midnight to midnight: the grain is dense in the hours the phone was open and pale in the quiet ones, and a fine line closes the ring. The trunk begins as a core on the day you set it.");

    /** Styles that read the weather outside: Pioggia's rain, and the wind for Deserto's plume and Orma's prints. */
    val readsWeather: Boolean get() = this == PIOGGIA || this == DESERTO || this == ORMA
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
    private val LOOK = setOf("style", "finish", "luna", "notturno", "concentrato", "sky_at", "copertina")

    @JvmStatic fun of(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    @JvmStatic fun isLook(key: String?) = key != null && key in LOOK

    fun style(c: Context): Style {
        // Retired styles carry over: Nebulosa (24 Nov 2026) to Stagione; Duna to Deserto, the other sand,
        // and Polvere to Orbita (28 Nov 2026). The Solco style was renamed Orbita on 28 Nov 2026, so it is
        // no longer confused with the app.
        val name = when (val n = of(c).getString("style", null)) {
            "NEBULOSA" -> "STAGIONE"; "DUNA" -> "DESERTO"; "POLVERE", "SOLCO" -> "ORBITA"; null -> "ORBITA"; else -> n
        }
        return runCatching { Style.valueOf(name) }.getOrDefault(Style.ORBITA)
    }
    fun finish(c: Context): Finish {
        val name = when (val n = of(c).getString("finish", null)) { "AVORIO" -> "TABACCO"; "ROSSO" -> "NOTTE"; null -> "VISCONTI"; else -> n }
        return runCatching { Finish.valueOf(name) }.getOrDefault(Finish.VISCONTI)
    }
    fun luna(c: Context) = of(c).getBoolean("luna", true)
    /** Notturno (19 Nov 2026): the real night sky. */
    fun notturno(c: Context) = of(c).getBoolean("notturno", true)
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
    fun setNotturno(c: Context, on: Boolean) = of(c).edit().putBoolean("notturno", on).apply()
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
