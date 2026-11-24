package com.fergusonconsultancy.solco

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The weather outside now, for Pioggia: precipitation (mm in the last hour), snowfall (cm), the WMO
 * weather code, cloud cover (%), wind at 10 m (m/s) and the direction it blows from (degrees).
 */
data class Wx(
    val precip: Float, val snow: Float, val code: Int, val cloud: Float,
    val wind: Float, val dir: Float, val at: Long,
) {
    companion object {
        /** Before the first reading, or when it is too old to trust: a plain overcast sky. */
        val OVERCAST = Wx(0f, 0f, 3, 80f, 0f, 270f, 0L)
        /** For the tile: steady rain on a south-westerly, a Leeds afternoon. */
        val SAMPLE = Wx(2.2f, 0f, 63, 95f, 7f, 250f, 0L)
    }
}

/**
 * Pioggia's source (24 Nov 2026): Open-Meteo's current conditions for the sky's place, the same
 * no-key service Cielo reads. Asked only while Pioggia is chosen and on screen, at most every 20
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
        if (a.size != 7) return null
        val at = a[6].toLong() * 1000L
        if (System.currentTimeMillis() - at > STALE) return null
        return Wx(a[0].toFloat(), a[1].toFloat(), a[2].toInt(), a[3].toFloat(), a[4].toFloat(), a[5].toFloat(), at)
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
                    "&current=precipitation,snowfall,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms")
                val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000 }
                try {
                    if (conn.responseCode == 200) {
                        val cur = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
                        val wx = Wx(
                            cur.optDouble("precipitation", 0.0).toFloat(), cur.optDouble("snowfall", 0.0).toFloat(),
                            cur.optInt("weather_code", 3), cur.optDouble("cloud_cover", 80.0).toFloat(),
                            cur.optDouble("wind_speed_10m", 0.0).toFloat(), cur.optDouble("wind_direction_10m", 270.0).toFloat(),
                            System.currentTimeMillis(),
                        )
                        val was = now(app)
                        changed = was == null || was.copy(at = 0L) != wx.copy(at = 0L)
                        // Kept as plain numbers; "wx" is not a look key, so the renderer picks it up on its own refresh
                        Prefs.of(app).edit().putString(KEY, listOf(wx.precip, wx.snow, wx.code, wx.cloud, wx.wind, wx.dir, wx.at / 1000L).joinToString(":")).apply()
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
