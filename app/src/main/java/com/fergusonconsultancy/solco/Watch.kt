package com.fergusonconsultancy.solco

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The fold, as the wallpaper sees it. Each surface knows whether it is the tall cover screen or the
 * main one; when the screen that arrives differs from the last one, the phone was folded or opened.
 * Works whether One UI gives each screen its own wallpaper engine or resizes one.
 * Unlocking without folding arrives on the same screen, so it never stirs the grain.
 */
object FoldWatch {
    private var lastTall: Boolean? = null
    private val times = LongArray(64)
    private val angles = FloatArray(64)
    private var count = 0
    private var head = 0

    /** True once the main screen was the last to arrive. */
    @JvmStatic val unfolded: Boolean get() = lastTall == false

    /** Returns 1 for an opening, -1 for a closing, 0 when nothing folded. */
    @JvmStatic @Synchronized fun arrive(tall: Boolean): Int {
        val prev = lastTall
        lastTall = tall
        return if (prev == null || prev == tall) 0 else if (tall) -1 else 1
    }

    @JvmStatic @Synchronized fun hinge(angle: Float) {
        times[head] = SystemClock.elapsedRealtime(); angles[head] = angle
        head = (head + 1) % times.size
        if (count < times.size) count++
    }

    /**
     * How fast the hinge swung just before the screens switched, in degrees a second, from the
     * readings of the last 700 ms. A one-handed flick is well above 320 °/s, a deliberate fold
     * well below. The threshold is an estimate, to be tuned on the Fold.
     */
    @JvmStatic @Synchronized fun fast(c: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        var firstT = 0L; var firstA = 0f; var lastT = 0L; var lastA = 0f; var seen = 0
        for (j in 0 until count) {
            val i = (head - count + j + times.size) % times.size
            if (now - times[i] > 700) continue
            if (seen == 0) { firstT = times[i]; firstA = angles[i] }
            lastT = times[i]; lastA = angles[i]; seen++
        }
        if (seen < 2 || lastT <= firstT) { Diag.fold(c, -1, false); return false }
        val speed = abs(lastA - firstA) / ((lastT - firstT) / 1000f)
        val fast = speed > 320f
        Diag.fold(c, speed.roundToInt(), fast)
        return fast
    }
}

/**
 * Tronco's record: minutes of unfolded use per day, sampled once a minute while the screen is on
 * and the main screen was the last to arrive. Kept on the phone, never sent anywhere.
 */
object Growth {
    private const val KEY = "growth"
    private const val START = "growth_start"

    @JvmStatic fun start(c: Context): LocalDate {
        val p = Prefs.of(c)
        val s = p.getString(START, null)
        if (s != null) runCatching { return LocalDate.parse(s) }
        val today = LocalDate.now()
        p.edit().putString(START, today.toString()).apply()
        return today
    }

    @JvmStatic fun tick(c: Context) {
        val pm = c.getSystemService(PowerManager::class.java) ?: return
        if (!pm.isInteractive || !FoldWatch.unfolded) return
        val p = Prefs.of(c)
        val map = read(p.getString(KEY, "") ?: "")
        val today = LocalDate.now().toString()
        map[today] = (map[today] ?: 0) + 1
        p.edit().putString(KEY, write(map)).apply()
    }

    /** Width of each ring from the first day to today; today's is still growing. */
    fun rings(c: Context): FloatArray {
        val start = start(c)
        val today = LocalDate.now()
        val n = (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceIn(1, 400)
        val from = today.minusDays((n - 1).toLong())
        val map = read(Prefs.of(c).getString(KEY, "") ?: "")
        val frac = LocalTime.now().toSecondOfDay() / 86400f
        return FloatArray(n) { i ->
            val m = map[from.plusDays(i.toLong()).toString()] ?: 0
            val use = .70f * min(1f, m / 180f)
            if (i == n - 1) (.30f * frac + use).coerceAtLeast(.04f) else .30f + use
        }
    }

    fun days(c: Context) = rings(c).size

    /** A month of invented days, for the style tile only, so the tile shows what Tronco becomes. */
    fun sample(days: Int): FloatArray {
        val rng = Rng(61)
        val out = FloatArray(days) { d -> val wk = if (d % 7 == 5 || d % 7 == 6) .7f else 1f; (.35f + .65f * rng.next()) * wk }
        out[days - 1] *= .45f
        return out
    }

    private fun read(s: String): MutableMap<String, Int> {
        val m = HashMap<String, Int>()
        for (kv in s.split(';')) {
            val i = kv.indexOf('=')
            if (i > 0) kv.substring(i + 1).toIntOrNull()?.let { m[kv.substring(0, i)] = it }
        }
        return m
    }

    private fun write(m: Map<String, Int>) = m.entries.sortedBy { it.key }.takeLast(420).joinToString(";") { "${it.key}=${it.value}" }
}

/** What the wallpaper has seen, shown folded away at the foot of Settings. */
object Diag {
    private fun stamp() = SimpleDateFormat("HH:mm, d MMM", Locale.UK).format(Date())

    @JvmStatic fun seen(c: Context, tall: Boolean, w: Int, h: Int, display: Int) {
        Prefs.of(c).edit().putString(if (tall) "seen_cover" else "seen_main", "$w × $h px, display $display, ${stamp()}").apply()
    }

    @JvmStatic fun hinge(c: Context, present: Boolean) {
        Prefs.of(c).edit().putBoolean("hinge_sensor", present).apply()
    }

    fun fold(c: Context, speed: Int, fast: Boolean) {
        val s = if (speed < 0) "no hinge readings" else "$speed °/s, ${if (fast) "fast" else "slow"}"
        Prefs.of(c).edit().putString("last_fold", "$s, ${stamp()}").apply()
    }
}

/**
 * Failures, kept so they can be read on the phone. A crash anywhere in the app is written before
 * the process ends; a GPU failure is caught, written, and the wallpaper falls back to its ground.
 */
object Crash {
    private const val NAME = "solco_crash"
    private fun store(c: Context) = c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private fun stamp() = SimpleDateFormat("HH:mm:ss, d MMM", Locale.UK).format(Date())

    fun install(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { save(app, "Crash on ${t.name}", e) }
            previous?.uncaughtException(t, e)
        }
    }

    /** Written at once (commit, not apply), because the process may be about to end. */
    @JvmStatic fun save(c: Context, where: String, e: Throwable) {
        val trace = android.util.Log.getStackTraceString(e).lines().take(40).joinToString("\n")
        val report = "Solco ${runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull()}\n" +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n" +
            "$where, ${stamp()}\n\n$trace"
        store(c).edit().putString("report", report).commit()
    }

    fun report(c: Context): String? = store(c).getString("report", null)
    fun clear(c: Context) { store(c).edit().remove("report").commit() }

    /** GPU facts and the first frame drawn on each screen, for Diagnostics. */
    fun gpu(c: Context, s: String) { store(c).edit().putString("gpu", s).apply() }
    /** A frame drawn means any earlier GPU problem is over. */
    fun drew(c: Context, tall: Boolean, s: String) {
        store(c).edit().putString(if (tall) "drew_cover" else "drew_main", "$s, ${stamp()}").remove("gl_error").apply()
    }
    fun glError(c: Context, s: String) { store(c).edit().putString("gl_error", "$s, ${stamp()}").commit() }
    fun get(c: Context, key: String): String? = store(c).getString(key, null)
    fun stampNow() = stamp()
}
