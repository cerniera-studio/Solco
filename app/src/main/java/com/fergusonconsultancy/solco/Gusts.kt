package com.fergusonconsultancy.solco

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime

/**
 * Orma's wind (28 Nov 2026): how hard the real wind has worked the sand through today, hour by
 * hour, so the prints soften as much as the day's weather would soften them, not on a fixed timer.
 *
 * Each weather reading (the one Pioggia and Deserto share) is put in the hour it was taken, keeping
 * the strongest of the hour. Readings are made only while a weather style is on screen, so an hour
 * with none takes the nearest reading before it (or, early in the day, the first after it), and a
 * day with no reading at all is taken as calm. Kept on the phone, today only.
 */
object Gusts {
    private const val KEY = "gusts"

    /**
     * How much an hour of wind at [ms] smooths a print, in e-folds of its depth. Dry sand barely moves
     * below about 5 m/s, so a calm hour still settles a print a little (0.04); past that the sand
     * starts to creep, and a gale (about 17 m/s and up) fills a print in two hours or so.
     */
    fun rate(ms: Float): Float = .04f + .5f * ((ms - 5f) / 12f).coerceIn(0f, 1f)

    /** Notes a reading of the wind at [ms] in this hour. */
    fun record(c: Context, ms: Float) {
        val p = Prefs.of(c)
        val today = LocalDate.now().toEpochDay()
        val a = read(p.getString(KEY, null), today)
        val h = LocalTime.now().hour
        a[h] = maxOf(a[h], ms)
        p.edit().putString(KEY, today.toString() + ";" + a.joinToString(",")).apply()
    }

    /** Today's 24 hourly rates (see [rate]), the gaps filled from the nearest reading. */
    fun today(c: Context): FloatArray = fill(read(Prefs.of(c).getString(KEY, null), LocalDate.now().toEpochDay()))

    /** For the tile and the Settings preview before any reading: a breezy afternoon. */
    fun sample(): FloatArray = FloatArray(24) { rate(if (it in 12..17) 9f else 4f) }

    private fun read(s: String?, today: Long): FloatArray {
        val a = FloatArray(24) { -1f }
        if (s == null) return a
        val parts = s.split(";")
        if (parts.size != 2 || parts[0].toLongOrNull() != today) return a
        parts[1].split(",").forEachIndexed { i, v -> if (i < 24) a[i] = v.toFloatOrNull() ?: -1f }
        return a
    }

    private fun fill(ms: FloatArray): FloatArray {
        val out = FloatArray(24)
        val first = ms.indexOfFirst { it >= 0f }
        if (first < 0) { out.fill(rate(0f)); return out }
        var held = ms[first]
        for (h in 0 until 24) {
            if (ms[h] >= 0f) held = ms[h]
            out[h] = rate(held)
        }
        return out
    }
}
