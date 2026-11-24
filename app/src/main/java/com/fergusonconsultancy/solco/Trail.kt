package com.fergusonconsultancy.solco

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sentiero's record (24 Nov 2026): where today's touches landed on each screen, as fractions of the
 * upright glass. A tap is kept where it went down; a swipe as its start and a point every 16 dp of
 * its path. Only while Sentiero is chosen, only today (cleared at midnight), only on the phone, at
 * most [MOST] points a screen. Written to storage when the wallpaper goes out of sight, not on
 * every touch.
 */
object Trail {
    private const val MOST = 1500
    /** x, y, kind (0 tap, 1 stroke start, 2 stroke point), time (ms). One list per screen. */
    private val cover = ArrayList<DoubleArray>()
    private val main = ArrayList<DoubleArray>()
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    /** Bumped on every point, so the renderer can tell the trail has grown since it last laid the gravel. */
    @Volatile var version = 0
        private set

    private fun list(isCover: Boolean) = if (isCover) cover else main
    private fun startOfDay(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Synchronized private fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = Prefs.of(c)
        for ((key, l) in listOf("trail_c" to cover, "trail_m" to main)) {
            l.clear()
            (p.getString(key, "") ?: "").split(";").forEach { e ->
                val a = e.split(",").mapNotNull { it.toDoubleOrNull() }
                if (a.size == 4) l += a.toDoubleArray()
            }
        }
    }

    @Synchronized fun add(c: Context, isCover: Boolean, fx: Float, fy: Float, kind: Int) {
        load(c)
        val l = list(isCover)
        val sod = startOfDay()
        l.removeAll { it[3] < sod }
        l += doubleArrayOf(fx.toDouble(), fy.toDouble(), kind.toDouble(), System.currentTimeMillis().toDouble())
        while (l.size > MOST) l.removeAt(0)
        // A stroke cut off at its start by the cap begins again where it now begins
        if (l.isNotEmpty() && l[0][2] == 2.0) l[0][2] = 1.0
        dirty = true
        version++
    }

    /** The last point on [isCover] becomes the start of a stroke: the finger has moved on from a tap. */
    @Synchronized fun becomeStroke(isCover: Boolean) {
        val l = list(isCover)
        if (l.isNotEmpty() && l.last()[2] == 0.0) l.last()[2] = 1.0
    }

    /** Today's points on this screen for [Scene]: fx, fy, kind, age in hours. */
    @Synchronized fun points(c: Context, isCover: Boolean): FloatArray {
        load(c)
        val now = System.currentTimeMillis()
        val sod = startOfDay()
        val l = list(isCover).filter { it[3] >= sod }
        val out = FloatArray(l.size * 4)
        l.forEachIndexed { i, e ->
            out[4 * i] = e[0].toFloat(); out[4 * i + 1] = e[1].toFloat(); out[4 * i + 2] = e[2].toFloat()
            out[4 * i + 3] = ((now - e[3]) / 3_600_000.0).toFloat()
        }
        return out
    }

    @Synchronized fun save(c: Context) {
        if (!dirty) return
        dirty = false
        fun pack(l: List<DoubleArray>) = l.joinToString(";") { e -> "%.4f,%.4f,%d,%d".format(java.util.Locale.ROOT, e[0], e[1], e[2].toInt(), e[3].toLong()) }
        Prefs.of(c).edit().putString("trail_c", pack(cover)).putString("trail_m", pack(main)).apply()
    }

    /** A plausible day for the tile: a dock and a few favourites tapped often, swipes between pages, pulls from the top. */
    fun sample(): FloatArray {
        val rng = Rng(7)
        val out = ArrayList<Float>()
        fun put(x: Float, y: Float, kind: Int, age: Float = 5f) { out += x; out += y; out += kind.toFloat(); out += age }
        val icons = listOf(.2f to .87f, .4f to .87f, .6f to .87f, .8f to .87f, .2f to .62f, .8f to .62f, .5f to .62f, .2f to .48f)
        val weights = listOf(14, 9, 6, 12, 5, 3, 2, 1)
        icons.zip(weights).forEach { (p, wgt) -> repeat(wgt) { put(p.first + (rng.next() - .5f) * .05f, p.second + (rng.next() - .5f) * .025f, 0) } }
        repeat(9) {
            var y = .38f + (rng.next() - .5f) * .12f; var x = .85f
            put(x, y, 1)
            while (x > .15f) { x -= .045f; y += (rng.next() - .5f) * .006f; put(x, y, 2) }
        }
        repeat(5) {
            val x = .5f + (rng.next() - .5f) * .3f; var y = .05f
            put(x, y, 1)
            while (y < .32f) { y += .022f; put(x, y, 2) }
        }
        // The last hour: a couple of taps on the dock
        put(.4f, .87f, 0, .3f); put(.8f, .87f, 0, .2f)
        return out.toFloatArray()
    }
}
