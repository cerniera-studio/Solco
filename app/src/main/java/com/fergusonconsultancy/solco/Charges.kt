package com.fergusonconsultancy.solco

import android.content.Context

/**
 * Delta's record (21 Nov 2026): each charge, when it began and how much it gave. Heard by the
 * wallpaper service for as long as Solco is set (the plug goes in while the screen is dark), and
 * reconciled with the battery whenever the wallpaper is seen, so a charge missed while the process
 * slept is still closed. Kept on the phone, one week deep. No permission.
 */
object Charges {
    private const val KEY = "charges"
    private const val OPEN = "charge_open"
    private const val DAY = 86_400_000L
    private const val WEEK = 7 * DAY
    /** A top-up smaller than this carves nothing. */
    private const val LEAST = .02f

    /** The battery says whether it is charging; open or close a charge to match. Safe to call often. */
    @JvmStatic @Synchronized fun reconcile(c: Context, level: Float, charging: Boolean) {
        val p = Prefs.of(c)
        val open = p.getString(OPEN, null)
        if (charging && open == null) {
            p.edit().putString(OPEN, "${System.currentTimeMillis()}:$level").apply()
        } else if (!charging && open != null) {
            val (t, from) = parse(open) ?: run { p.edit().remove(OPEN).apply(); return }
            val gain = level - from
            val list = read(p.getString(KEY, "") ?: "").filter { System.currentTimeMillis() - it.first < WEEK }.toMutableList()
            if (gain >= LEAST) list += t to gain
            p.edit().remove(OPEN).putString(KEY, list.joinToString(";") { "${it.first}:${it.second}" }).apply()
        }
    }

    /**
     * The channels to carve: for each charge of the past week its age in days, its gain (0 to 1) and
     * a seed from when it began. A charge still running is the newest, its gain so far.
     */
    fun channels(c: Context): FloatArray {
        val now = System.currentTimeMillis()
        val p = Prefs.of(c)
        val list = read(p.getString(KEY, "") ?: "").filter { now - it.first < WEEK }.toMutableList()
        p.getString(OPEN, null)?.let { parse(it) }?.let { (t, from) ->
            val gain = Power.read(c).first - from
            if (gain >= LEAST) list += t to gain
        }
        return pack(list.map { (t, g) -> Triple((now - t) / DAY.toFloat(), g, t) })
    }

    /** A plausible week for the tiles: most nights a long charge, a few top-ups. */
    fun sample(): FloatArray {
        val now = System.currentTimeMillis()
        val week = listOf(6.6f to .62f, 5.7f to .55f, 4.4f to .18f, 3.6f to .7f, 2.7f to .48f, 1.6f to .66f, 1.1f to .14f, .5f to .58f)
        return pack(week.mapIndexed { i, (age, g) -> Triple(age, g, now - (age * DAY).toLong() + i) })
    }

    private fun pack(l: List<Triple<Float, Float, Long>>): FloatArray {
        val out = FloatArray(l.size * 3)
        l.forEachIndexed { i, (age, g, t) ->
            out[3 * i] = age
            out[3 * i + 1] = g.coerceIn(0f, 1f)
            // A seed from the start time, kept within the generator's range
            out[3 * i + 2] = (1 + ((t / 1000L) % 2_000_000_000L).toInt() % 16_000_000).toFloat()
        }
        return out
    }

    private fun parse(s: String): Pair<Long, Float>? {
        val a = s.split(":")
        if (a.size != 2) return null
        val t = a[0].toLongOrNull() ?: return null
        val v = a[1].toFloatOrNull() ?: return null
        return t to v
    }

    private fun read(s: String): List<Pair<Long, Float>> = s.split(";").mapNotNull { if (it.isBlank()) null else parse(it) }
}
