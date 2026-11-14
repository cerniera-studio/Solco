package com.fergusonconsultancy.solco

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Sveglia's dawn, drawn by Solco (14 Nov 2026). When Sveglia's alarm screen goes see-through over this
 * wallpaper, it tells Solco how far the dawn has come (0 to 1, about once a second) and Solco warms
 * from the hinge: the same light Sveglia would draw itself, rising behind the grain so the grain
 * warms with it. The sun stays the real one (the disc), so the alarm brings the light, never a sun
 * that isn't up. The glow fades by itself if Sveglia goes quiet for six seconds.
 */
object DawnGlow {
    const val ACTION = "com.cerniera.action.DAWN"
    const val EXTRA_P = "p"
    private const val QUIET_MS = 6000L

    @Volatile private var p = -1f
    @Volatile private var at = 0L

    fun set(progress: Float) { p = progress; at = android.os.SystemClock.uptimeMillis() }

    /** The dawn's progress now, or -1 when there is no dawn (or Sveglia went quiet). */
    fun now(): Float = if (p < 0f || android.os.SystemClock.uptimeMillis() - at > QUIET_MS) -1f else p.coerceIn(0f, 1f)

    // Sveglia's sunrise palette (its Alarm.kt), so both apps' dawns are the same light
    private val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val top = intArrayOf(0x0E1311, 0x151A2A, 0x3A2C4A, 0x8C6A7A, 0xE9E3D6)
    private val horizon = intArrayOf(0x0E1311, 0x3A1418, 0xC2561E, 0xE2A857, 0xF6F1E6)

    private fun at(c: IntArray, q: Float): Int {
        var i = 0
        while (i < stops.size - 2 && q > stops[i + 1]) i++
        val t = ((q - stops[i]) / (stops[i + 1] - stops[i])).coerceIn(0f, 1f)
        fun ch(v: Int, s: Int) = (v shr s) and 255
        fun mix(s: Int) = (ch(c[i], s) + (ch(c[i + 1], s) - ch(c[i], s)) * t).toInt()
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun argb(rgb: Int, a: Float) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or rgb

    /**
     * The glow at [q], a quarter of the surface's size (drawn scaled up, so it stays soft): a wash
     * of the sky's colour over everything and a warm pool rising from the hinge at ([cx], [cy]).
     */
    fun paint(w: Int, h: Int, cx: Float, cy: Float, reach: Float, q: Float): Bitmap {
        val k = 4
        val bw = (w / k).coerceAtLeast(1); val bh = (h / k).coerceAtLeast(1)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val e = q * q * (3 - 2 * q)
        c.drawColor(argb(at(top, q), 0.30f * e))
        val hz = at(horizon, q)
        val r = (reach / k) * (0.45f + 0.95f * e)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(cx / k, cy / k, r.coerceAtLeast(1f),
            intArrayOf(argb(hz, 0.80f * e), argb(hz, 0.42f * e), argb(hz, 0f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        return b
    }
}
