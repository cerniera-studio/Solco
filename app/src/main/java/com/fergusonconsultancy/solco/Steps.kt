package com.fergusonconsultancy.solco

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Orma's record (27 Nov 2026): today's steps, minute by minute, from the phone's own step counter.
 * The counter is the hardware's running total since the phone started; each reading's rise over
 * the last is put in the minute it was taken. Heard for as long as Solco is the wallpaper and
 * physical activity is allowed, so the trail holds the whole day whichever style was chosen this
 * morning. Kept on the phone, today only; nothing leaves it.
 *
 * A gap in the readings (the process slept, or the sensor's queue overflowed) still keeps the
 * steps, since the total keeps counting: they are laid back from the reading that found them at a
 * walking pace (about 100 a minute), the one estimate here.
 */
object Steps {
    private const val PACE = 100f
    private val minutes = FloatArray(1440)
    private var day = -1L
    private var last = -1f
    private var boot = 0L
    private var lastAt = 0L
    private var savedAt = 0L
    @Volatile private var loaded = false
    @Volatile var total = 0
        private set

    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    fun sensor(c: Context): Sensor? = (c.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private fun epochDay() = LocalDate.now().toEpochDay()
    private fun startOfDay() = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Synchronized private fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = Prefs.of(c)
        last = p.getFloat("steps_last", -1f)
        boot = p.getLong("steps_boot", 0L)
        lastAt = p.getLong("steps_at", 0L)
        day = p.getLong("steps_day", -1L)
        minutes.fill(0f)
        if (day == epochDay()) (p.getString("steps_min", "") ?: "").split(";").forEach { e ->
            val a = e.split(":")
            val m = a.getOrNull(0)?.toIntOrNull(); val v = a.getOrNull(1)?.toFloatOrNull()
            if (m != null && v != null && m in 0 until 1440) minutes[m] += v
        }
        total = minutes.sum().toInt()
    }

    private fun rollDay() {
        val d = epochDay()
        if (d != day) { day = d; minutes.fill(0f); total = 0 }
    }

    /** A reading from the step counter: [value] the running total, [ts] the sensor's time (ns since boot). */
    @JvmStatic @Synchronized fun reading(c: Context, value: Float, ts: Long) {
        load(c)
        rollDay()
        val now = System.currentTimeMillis()
        val bootWall = now - SystemClock.elapsedRealtime()
        val at = (bootWall + ts / 1_000_000L).coerceIn(bootWall, now)
        val sameBoot = boot > 0L && kotlin.math.abs(bootWall - boot) < 60_000L
        val gain: Float
        val from: Long
        when {
            last < 0f -> { gain = 0f; from = at }                          // the first reading only sets where counting starts
            !sameBoot || value < last -> { gain = value; from = maxOf(bootWall, lastAt) }  // the phone restarted
            else -> { gain = value - last; from = lastAt }
        }
        last = value; boot = bootWall; lastAt = at
        if (gain > 0f) lay(gain, from, at)
        if (now - savedAt > 30_000L) save(c)
    }

    /** Puts [gain] steps in the minutes before [at]: its own minute when the readings run close, else back at a walking pace. */
    private fun lay(gain: Float, from: Long, at: Long) {
        val sod = startOfDay()
        if (at < sod) return
        fun minute(t: Long) = ((t - sod) / 60_000L).toInt().coerceIn(0, 1439)
        val first = minute(maxOf(from, sod))
        var m = minute(at)
        var left = gain
        // Close readings: all in the minute of the reading. A gap: back from it a minute at a time
        while (left > 0f) {
            val put = if (m <= first || at - from <= 120_000L) left else minOf(left, PACE)
            minutes[m] += put
            left -= put
            m--
        }
        total = minutes.sum().toInt()
    }

    @JvmStatic @Synchronized fun save(c: Context) {
        if (!loaded) return
        savedAt = System.currentTimeMillis()
        val sb = StringBuilder()
        for (m in 0 until 1440) if (minutes[m] > 0f) { if (sb.isNotEmpty()) sb.append(';'); sb.append(m).append(':').append(minutes[m].toInt()) }
        Prefs.of(c).edit().putFloat("steps_last", last).putLong("steps_boot", boot).putLong("steps_at", lastAt)
            .putLong("steps_day", day).putString("steps_min", sb.toString()).apply()
    }

    /** Today's steps for [Scene], one value a minute from midnight. */
    @Synchronized fun today(c: Context): FloatArray {
        load(c)
        rollDay()
        return minutes.copyOf()
    }

    @Synchronized fun count(c: Context): Int { load(c); rollDay(); return total }

    fun nowMinute(): Int = java.time.LocalTime.now().let { it.hour * 60 + it.minute }

    /** A plausible day for the tile: a walk to the station, a lunch walk, a run for the train, pottering between. */
    const val SAMPLE_NOW = 17 * 60 + 40
    fun sample(): FloatArray {
        val r = Rng(11)
        val m = FloatArray(1440)
        fun walk(a: Int, b: Int, spm: Float) { for (t in a until b) m[t] += spm * (.85f + .3f * r.next()) }
        walk(7 * 60 + 5, 7 * 60 + 12, 40f)
        walk(8 * 60 + 10, 8 * 60 + 31, 112f)
        for (t in 9 * 60 until 12 * 60 step 37) m[t] += 30f + 40f * r.next()
        walk(12 * 60 + 30, 12 * 60 + 52, 104f)
        for (t in 13 * 60 until 17 * 60 step 29) m[t] += 25f + 30f * r.next()
        walk(17 * 60 + 5, 17 * 60 + 14, 150f)
        walk(17 * 60 + 14, 17 * 60 + 30, 108f)
        return m
    }
}
