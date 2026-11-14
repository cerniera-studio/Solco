package com.fergusonconsultancy.solco

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import com.cerniera.kit.Sky as KitSky

/**
 * The disc's force: the real sky, read from the Kit's [KitSky] for the phone's place.
 *
 * By day the disc follows the sun's true altitude. 0 is the horizon, and 1 is the highest the sun
 * ever climbs at this latitude (midsummer noon), so the season shows: in a Leeds December the disc
 * stays low all day, in June it rides high. By night it is the moon, riding an arc from sunset to
 * the next sunrise. Sunrise and sunset are the real ones, not 06:00 and 18:00.
 */
data class SkyState(val day: Boolean, val alt: Float, val phase: Float, val minutes: Int)

object Sky {

    /** Where the sky is read from: the phone's last place, or Leeds until there has been one. */
    @Volatile var place: KitSky.Place = KitSky.Place.LEEDS
        private set
    /** When [place] came from the phone, epoch ms; 0 for the Leeds fallback. */
    @Volatile var placeAt = 0L
        private set

    /** Loads the kept place. Called once from [SolcoApp]. */
    fun load(c: Context) {
        val p = Prefs.of(c)
        val name = p.getString("sky_name", null) ?: return
        place = KitSky.Place(name, p.getFloat("sky_lat", 0f).toDouble(), p.getFloat("sky_lon", 0f).toDouble())
        placeAt = p.getLong("sky_at", 0L)
    }

    fun hasLocation(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Takes the phone's place, only while Settings is open (Solco never reads location in the
     * background). Coarse location only: a few kilometres moves sunrise by seconds. Asks for a
     * fresh fix when there is no recent one, and calls [done] with true if the place changed.
     */
    fun follow(c: Context, done: (Boolean) -> Unit) {
        if (!hasLocation(c)) { done(false); return }
        val lm = c.getSystemService(LocationManager::class.java) ?: run { done(false); return }
        // Naming the place can touch the network, so it is done off the main thread
        fun finish(loc: Location?) = Thread {
            val moved = loc?.let { runCatching { keep(c, it) }.getOrDefault(false) } ?: false
            c.mainExecutor.execute { done(moved) }
        }.start()
        val last = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3_600_000L) { finish(last); return }
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) { finish(last); return }
        runCatching { lm.getCurrentLocation(provider, null, c.mainExecutor) { loc -> finish(loc ?: last) } }
            .onFailure { finish(last) }
    }

    private fun keep(c: Context, loc: Location): Boolean {
        val name = runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(c).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull() ?: "Here"
        val moved = abs(loc.latitude - place.lat) > 0.01 || abs(loc.longitude - place.lon) > 0.01 || placeAt == 0L
        placeAt = System.currentTimeMillis()
        if (!moved) return false
        place = KitSky.Place(name, loc.latitude, loc.longitude)
        synchronized(days) { days.clear() }
        // "sky_at" is a look key: writing it redraws the wallpaper with the new sky, so only on a move
        Prefs.of(c).edit().putString("sky_name", name).putFloat("sky_lat", loc.latitude.toFloat())
            .putFloat("sky_lon", loc.longitude.toFloat()).putLong("sky_at", placeAt).apply()
        return true
    }

    // One SunDay per local date and place: Sipario asks every frame.
    private val days = HashMap<String, KitSky.SunDay>()

    fun day(date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): KitSky.SunDay {
        val p = place
        val key = "$date|${p.lat}|${p.lon}|$zone"
        synchronized(days) {
            days[key]?.let { return it }
            if (days.size > 8) days.clear()
            return KitSky.day(p, date, zone).also { days[key] = it }
        }
    }

    fun now(): SkyState {
        val at = Instant.now()
        val zone = ZoneId.systemDefault()
        val local = at.atZone(zone)
        val minutes = local.hour * 60 + local.minute
        val sun = KitSky.sun(place, at)
        val phase = KitSky.moon(at).fraction.toFloat()
        if (sun.up) {
            // Highest the sun climbs here all year: midsummer noon, never above the zenith
            val top = min(90.0, 90 - abs(place.lat) + 23.44)
            val a = (sin(Math.toRadians(sun.altitude.coerceAtLeast(0.0))) / sin(Math.toRadians(top))).coerceIn(0.0, 1.0)
            return SkyState(true, a.toFloat(), phase, minutes)
        }
        // Night: the moon's arc from the last sunset to the next sunrise
        val today = local.toLocalDate()
        val before = if (local.hour < 12) day(today.minusDays(1), zone).sunset else day(today, zone).sunset
        val after = if (local.hour < 12) day(today, zone).sunrise else day(today.plusDays(1), zone).sunrise
        val prog = if (before != null && after != null && after.isAfter(before))
            ((at.toEpochMilli() - before.toEpochMilli()).toDouble() / (after.toEpochMilli() - before.toEpochMilli())).coerceIn(0.0, 1.0)
        else .5 // polar night or day: no sunset or sunrise to measure from
        return SkyState(false, sin(PI * prog).toFloat(), phase, minutes)
    }

    /** 0 is new, 0.5 full. From the Kit (the mean month, good to about a day). */
    fun moonPhase(ms: Long): Float = KitSky.moon(Instant.ofEpochMilli(ms)).fraction.toFloat()

    fun phaseName(p: Float): String = KitSky.MoonPhase(p.toDouble(), (1 - kotlin.math.cos(2 * PI * p)) / 2).label
}
