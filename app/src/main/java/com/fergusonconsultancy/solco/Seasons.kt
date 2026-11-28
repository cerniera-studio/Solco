package com.fergusonconsultancy.solco

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import com.cerniera.kit.Sky as KitSky

/**
 * Stagione's year: hours of daylight for each day of this year at the sky's place, the day of the
 * year each month begins on (0 is 1 January), and today. [rise] and [set] (28 Nov 2026) are today's
 * sunrise and sunset in minutes from midnight, so today's ray can fill as the daylight passes.
 */
class Season(val daylight: FloatArray, val months: IntArray, val today: Int, val rise: Int = 360, val set: Int = 1080) {
    /** How much of today's daylight has passed at [minute]: 0 before sunrise, 1 after sunset. */
    fun passed(minute: Int): Float = if (set <= rise) (if (daylight.getOrElse(today) { 12f } >= 24f) minute / 1440f else 0f)
        else ((minute - rise).toFloat() / (set - rise)).coerceIn(0f, 1f)

    companion object {
        /** Without a place: the year at the equator, near enough twelve hours every day. */
        val EVEN = Season(FloatArray(365) { 12f }, intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334), -1)
    }
}

object Seasons {
    @Volatile private var kept: Pair<String, Season>? = null

    /** This year at the sky's place, worked out once a day (a year of sunrises takes a moment). */
    fun now(date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Season {
        val p = Sky.place
        val key = "$date|${p.lat}|${p.lon}|$zone"
        kept?.let { if (it.first == key) return it.second }
        val year = date.year
        val n = date.lengthOfYear()
        val day = FloatArray(n) { i ->
            val d = KitSky.day(p, LocalDate.ofYearDay(year, i + 1), zone)
            val up = d.sunrise; val down = d.sunset
            when {
                up != null && down != null && down.isAfter(up) -> Duration.between(up, down).toMinutes() / 60f
                // Polar day or night: no sunrise or sunset, so all day or none
                else -> if (d.noonAltitude > 0.0) 24f else 0f
            }
        }
        val months = IntArray(12) { LocalDate.of(year, it + 1, 1).dayOfYear - 1 }
        val td = KitSky.day(p, date, zone)
        fun minute(t: java.time.Instant?) = t?.atZone(zone)?.toLocalTime()?.let { it.hour * 60 + it.minute }
        val rise = minute(td.sunrise) ?: 0; val set = minute(td.sunset) ?: 0
        return Season(day, months, date.dayOfYear - 1, rise, set).also { kept = key to it }
    }
}
