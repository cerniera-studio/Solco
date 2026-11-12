package com.fergusonconsultancy.solco

import android.content.Context
import android.os.PowerManager
import com.cerniera.kit.Crash
import com.cerniera.kit.Hinge
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
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
    /** One hinge for the whole process, from the Kit, heard while any wallpaper engine lives. */
    private var hinge: Hinge? = null
    private var users = 0

    /** True once the main screen was the last to arrive. */
    @JvmStatic val unfolded: Boolean get() = lastTall == false

    /**
     * Cover or main screen, from a surface's shape. The cover is long and narrow either way round
     * (about 2.3 : 1), the main screen nearly square, so turning either screen never reads as a fold.
     */
    @JvmStatic fun tall(w: Int, h: Int) = maxOf(w, h) > minOf(w, h) * 1.5f

    /** Returns 1 for an opening, -1 for a closing, 0 when nothing folded. */
    @JvmStatic @Synchronized fun arrive(tall: Boolean): Int {
        val prev = lastTall
        lastTall = tall
        return if (prev == null || prev == tall) 0 else if (tall) -1 else 1
    }

    /**
     * Starts hearing the hinge, for as long as a wallpaper engine lives: the screen goes dark before
     * the hinge finishes moving, so a listener that ran only while visible never had a fold's
     * readings. The sensor reports only when the hinge moves. Returns whether there is a hinge.
     */
    @JvmStatic @Synchronized fun listen(c: Context): Boolean {
        users++
        val h = hinge ?: Hinge(c.applicationContext, object : Hinge.Listener {
            // The swing is timed the moment the hinge reaches open or closed, while its readings are
            // fresh. The wallpaper may only see the new screen seconds later (folding with an app open,
            // or before unlocking), by when the last 700 ms hold no movement at all.
            override fun onFold(opened: Boolean, fast: Boolean) { swing(hinge?.speed() ?: 0f) }
        }).also { hinge = it; it.start() }
        return h.present
    }

    @JvmStatic @Synchronized fun unlisten() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { hinge?.stop(); hinge = null }
    }

    /** The last complete swing: its speed and when it ended. */
    private var swingSpeed = 0f
    private var swingAt = 0L

    @Synchronized private fun swing(speed: Float) {
        if (speed <= 0f) return
        swingSpeed = speed; swingAt = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * Whether the hinge swung fast just before the screens switched, by the Kit's measure (degrees a
     * second over 700 ms, fast above [Hinge.FAST]: still an estimate, to be tuned on the Fold).
     * A screen that arrives mid-swing reads the hinge as it moves; one that arrives later (an app was
     * open, or the phone was locked) takes the swing that finished within the last 15 s.
     */
    @JvmStatic @Synchronized fun fast(c: Context): Boolean {
        val live = hinge?.takeIf { it.present }?.speed() ?: 0f
        val recent = swingAt > 0L && android.os.SystemClock.elapsedRealtime() - swingAt < 15_000L
        val speed = if (live > 0f) live else if (recent) swingSpeed else 0f
        swingAt = 0L
        if (speed <= 0f) { Diag.fold(c, -1, false); return false }
        val fast = speed > Hinge.FAST
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
 * What the GPU did, for Diagnostics. A failure is also written as a crash report through the Kit,
 * so Settings shows it on the next open with Share, Copy and Try again; the wallpaper falls back
 * to its ground instead of ending.
 */
object Gpu {
    /** The store keeps its old name, so diagnostics already on the phone carry over. */
    private const val NAME = "solco_crash"
    private fun store(c: Context) = c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private fun stamp() = SimpleDateFormat("HH:mm:ss, d MMM", Locale.UK).format(Date())

    /** A caught failure: kept as a report by the Kit's Crash, without ending the process. */
    @JvmStatic fun failed(c: Context, where: String, e: Throwable) = runCatching { Crash.save(c, "Solco", where, e) }

    /** GPU facts and the first frame drawn on each screen, for Diagnostics. */
    fun gpu(c: Context, s: String) { store(c).edit().putString("gpu", s).apply() }
    /** A frame drawn means any earlier GPU problem is over. */
    fun drew(c: Context, tall: Boolean, s: String) {
        store(c).edit().putString(if (tall) "drew_cover" else "drew_main", "$s, ${stamp()}").remove("gl_error").apply()
    }
    fun glError(c: Context, s: String) { store(c).edit().putString("gl_error", "$s, ${stamp()}").commit() }
    fun get(c: Context, key: String): String? = store(c).getString(key, null)
}
