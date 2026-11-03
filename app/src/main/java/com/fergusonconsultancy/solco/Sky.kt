package com.fergusonconsultancy.solco

import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The disc's force: the time of day. The sun from 06:00 to 18:00, the moon the rest of the night. */
data class SkyState(val day: Boolean, val alt: Float, val phase: Float, val minutes: Int)

object Sky {
    private const val SYNODIC = 29.530588853
    /** A known new moon: 6 Jan 2000, 18:14 UTC. */
    private const val NEW_MOON_MS = 947182440000L

    fun now(): SkyState {
        val cal = Calendar.getInstance()
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val h = m / 60.0
        val day = h >= 6 && h < 18
        val alt = if (day) sin(PI * (h - 6) / 12) else sin(PI * (((h - 18) + 24) % 24) / 12)
        return SkyState(day, alt.toFloat(), moonPhase(System.currentTimeMillis()), m)
    }

    /** 0 is new, 0.5 full. Accurate to within about a day, which a disc this size cannot show. */
    fun moonPhase(ms: Long): Float {
        val age = (((ms - NEW_MOON_MS) / 86_400_000.0) % SYNODIC + SYNODIC) % SYNODIC
        return (age / SYNODIC).toFloat()
    }

    fun phaseName(p: Float): String {
        val lit = ((1 - cos(2 * PI * p)) / 2 * 100).roundToInt()
        val n = when {
            p < 0.03f || p > 0.97f -> "New moon"
            p < 0.22f -> "Waxing crescent"
            p < 0.28f -> "First quarter"
            p < 0.47f -> "Waxing gibbous"
            p < 0.53f -> "Full moon"
            p < 0.72f -> "Waning gibbous"
            p < 0.78f -> "Last quarter"
            else -> "Waning crescent"
        }
        return "$n, $lit% lit"
    }
}
