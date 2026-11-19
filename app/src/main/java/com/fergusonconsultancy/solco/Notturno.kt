package com.fergusonconsultancy.solco

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.cerniera.kit.Sky as KitSky
import com.cerniera.kit.moonAt
import com.cerniera.kit.nextSet
import com.cerniera.kit.planets

/**
 * Notturno (19 Nov 2026, from the Daily App Ideas page): Solco's sky after dark is the real
 * night. The positions come from the Kit (Sky.moonAt, Sky.planets); this keeps the words for
 * Settings and a fixed moment for the emulator walk.
 */
object Notturno {
    /** For the emulator walk ("--es night <ISO instant>"): the previews draw this moment. Null is now. */
    @Volatile var fixed: Instant? = null
    val previewAt: Instant get() = fixed ?: Instant.now()

    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private fun t(i: Instant?) = i?.atZone(ZoneId.systemDefault())?.format(hm)

    /** For Settings, under the switches: tonight's moon and planets in one or two lines. */
    fun describe(on: Boolean): String {
        val at = previewAt
        val place = Sky.place
        val sky = Sky.now(on, at)
        if (!on) return if (sky.day) "Tonight: ${Sky.phaseName(Sky.moonPhase(at.toEpochMilli() + 12 * 3_600_000L))}."
            else "Now: ${Sky.phaseName(sky.phase)}."
        // By day, describe the coming night at civil dusk
        val night = if (sky.day) Sky.day(at.atZone(ZoneId.systemDefault()).toLocalDate()).civilDusk?.takeIf { it.isAfter(at) } ?: at else at
        val m = KitSky.moonAt(place, night)
        val lit = "${(m.illumination * 100).toInt()}% lit"
        val moon = when {
            m.up -> "Moon up, $lit" + (t(KitSky.nextSet("Moon", place, night))?.let { ", sets $it" } ?: "")
            else -> "Moon down ($lit)"
        }
        val seen = KitSky.planets(place, night).filter { it.visible }
        val planets = if (seen.isEmpty()) "no bright planets up" else seen.joinToString(", ") { p ->
            p.name + (t(KitSky.nextSet(p.name, place, night))?.let { " till $it" } ?: "")
        }
        return (if (sky.day) "Tonight from ${t(night)}: " else "Now: ") + "$moon. $planets."
    }
}
