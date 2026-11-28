package com.fergusonconsultancy.solco

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The weather outside now, for Pioggia: precipitation (mm in the last hour), snowfall (cm), the WMO
 * weather code, cloud cover (%), wind at 10 m (m/s) and the direction it blows from (degrees).
 * [today] (28 Nov 2026) is the rain that has fallen here since midnight, mm, for Pioggia's puddles.
 * Deserto and Orma read only the wind.
 */
data class Wx(
    val precip: Float, val snow: Float, val code: Int, val cloud: Float,
    val wind: Float, val dir: Float, val at: Long, val today: Float = 0f,
) {
    companion object {
        /** Before the first reading, or when it is too old to trust: a plain overcast sky. */
        val OVERCAST = Wx(0f, 0f, 3, 80f, 0f, 270f, 0L)
        /** For the tile: steady rain on a south-westerly, a Leeds afternoon. */
        val SAMPLE = Wx(2.2f, 0f, 63, 95f, 7f, 250f, 0L, 6f)
    }
}

/**
 * Pioggia's source (24 Nov 2026): Open-Meteo's current conditions for the sky's place, the same
 * no-key service Cielo reads. Asked only while Pioggia, Deserto or Orma is chosen and on screen, at most every 20
 * minutes (the service updates every 15), and kept on the phone so a fold never waits for the
 * network. Nothing is sent but the place's latitude and longitude, rounded to two places.
 */
object Weather {
    private const val KEY = "wx"
    private const val EVERY = 20 * 60_000L
    /** Older than this, a reading no longer says what is falling now. */
    private const val STALE = 3 * 3_600_000L

    @Volatile private var lastTry = 0L
    @Volatile private var busy = false

    fun now(c: Context): Wx? {
        val s = Prefs.of(c).getString(KEY, null) ?: return null
        val a = s.split(":").mapNotNull { it.toDoubleOrNull() }
        // Seven numbers before 28 Nov 2026; the eighth is today's rain so far
        if (a.size != 7 && a.size != 8) return null
        val at = a[6].toLong() * 1000L
        if (System.currentTimeMillis() - at > STALE) return null
        // Today's rain belongs to the day it was read on
        val sameDay = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == java.time.LocalDate.now()
        val today = if (a.size == 8 && sameDay) a[7].toFloat() else 0f
        return Wx(a[0].toFloat(), a[1].toFloat(), a[2].toInt(), a[3].toFloat(), a[4].toFloat(), a[5].toFloat(), at, today)
    }

    /** True if a new reading should be asked for. */
    fun due(c: Context): Boolean {
        val t = System.currentTimeMillis()
        if (busy || t - lastTry < EVERY) return false
        val have = now(c)
        return have == null || t - have.at >= EVERY
    }

    /** Asks for the weather off the main thread, then calls [done] with true if what is falling changed. */
    fun fetch(c: Context, done: (Boolean) -> Unit) {
        if (busy) return
        busy = true
        lastTry = System.currentTimeMillis()
        val app = c.applicationContext
        Thread {
            var changed = false
            try {
                val p = Sky.place
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f".format(java.util.Locale.ROOT, p.lat, p.lon) +
                    "&current=precipitation,snowfall,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms" +
                    "&hourly=precipitation&timezone=auto&forecast_days=1")
                val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000 }
                try {
                    if (conn.responseCode == 200) {
                        val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val cur = root.getJSONObject("current")
                        // Today's rain so far: the place's hours from midnight up to now (local times, so they compare as text)
                        var today = 0.0
                        runCatching {
                            val now = cur.getString("time")
                            val hr = root.getJSONObject("hourly")
                            val times = hr.getJSONArray("time"); val mm = hr.getJSONArray("precipitation")
                            for (i in 0 until times.length()) if (times.getString(i) <= now) today += mm.optDouble(i, 0.0)
                        }
                        val wx = Wx(
                            cur.optDouble("precipitation", 0.0).toFloat(), cur.optDouble("snowfall", 0.0).toFloat(),
                            cur.optInt("weather_code", 3), cur.optDouble("cloud_cover", 80.0).toFloat(),
                            cur.optDouble("wind_speed_10m", 0.0).toFloat(), cur.optDouble("wind_direction_10m", 270.0).toFloat(),
                            System.currentTimeMillis(), today.toFloat(),
                        )
                        // Orma's record of the day's wind, kept whichever weather style asked
                        Gusts.record(app, wx.wind)
                        val was = now(app)
                        changed = was == null || was.copy(at = 0L) != wx.copy(at = 0L)
                        // Kept as plain numbers; "wx" is not a look key, so the renderer picks it up on its own refresh
                        Prefs.of(app).edit().putString(KEY, listOf(wx.precip, wx.snow, wx.code, wx.cloud, wx.wind, wx.dir, wx.at / 1000L, wx.today).joinToString(":")).apply()
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Throwable) {
                // Offline, or the service is away: the last reading stands until it grows stale
            } finally {
                busy = false
            }
            done(changed)
        }.start()
    }
}
