package com.fergusonconsultancy.solco

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The ground, and for Solco the fine grooves of the inner field, kept faint so widgets stay legible. */
object Backdrop {
    fun paint(s: Scene, f: Finish): Bitmap {
        val b = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(f.ground or 0xFF000000.toInt())
        if (s.style == Style.SOLCO) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1f, .6f * s.dp) }
            val rng = Rng(11)
            var rr = s.r * .06f
            while (rr < s.r * .40f) {
                val t = (rr / s.r - .06f) / .34f
                p.color = withAlpha(f.groove, f.grooveA * (.35f + .65f * t.pow(1.4f)))
                c.drawCircle(s.cx, s.cy, rr, p)
                rr += (3.2f + rng.next() * 2.4f) * s.dp
            }
        }
        return b
    }
}

/**
 * The sun or the moon. Cooler and brighter by day, warmer and dimmer at night; with Luna on, the
 * moon shows tonight's phase. Its own fine grain, and one soft highlight upper left falling off
 * like light on a curved surface.
 */
object DiscPainter {
    fun paint(f: Finish, sky: SkyState, luna: Boolean, rad: Float, dp: Float): Bitmap {
        val s = ceil(rad * 2 + 4).toInt().coerceAtLeast(4)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val g = Canvas(b)
        val k = s / 2f
        val col = if (sky.day) f.discDay else f.discNight
        val dim = if (sky.day) 1f else .82f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val circle = RectF(k - rad, k - rad, k + rad, k + rad)
        if (!sky.day && luna) {
            // The unlit limb, then the lit part bounded by the terminator, a half ellipse
            fill.color = withAlpha(col, .13f)
            g.drawCircle(k, k, rad, fill)
            val p = sky.phase
            val rx = max(.01f, rad * abs(cos(2 * PI * p)).toFloat())
            val ell = RectF(k - rx, k - rad, k + rx, k + rad)
            val path = Path()
            if (p < .5f) {
                path.arcTo(circle, -90f, 180f, true)
                if (p < .25f) path.arcTo(ell, 90f, -180f) else path.arcTo(ell, 90f, 180f)
            } else {
                path.arcTo(circle, 90f, 180f, true)
                if (p > .75f) path.arcTo(ell, 270f, -180f) else path.arcTo(ell, -90f, 180f)
            }
            path.close()
            fill.color = withAlpha(col, dim)
            g.drawPath(path, fill)
        } else {
            fill.color = withAlpha(col, dim)
            g.drawCircle(k, k, rad, fill)
        }
        g.save()
        g.clipPath(Path().apply { addCircle(k, k, rad, Path.Direction.CW) })
        val rng = Rng(101)
        val count = min(40_000, (rad * rad / (5f * dp)).toInt())
        val buckets = Array(6) { FloatArray(count * 2) }
        val nb = IntArray(6)
        repeat(count) {
            val a = rng.next() * 6.283f
            val r = sqrt(rng.next()) * rad
            val white = rng.next() < .5f
            val bi = (if (white) 3 else 0) + min(2, (rng.next() * 3).toInt())
            buckets[bi][nb[bi]++] = k + cos(a) * r
            buckets[bi][nb[bi]++] = k + sin(a) * r
        }
        val sp = Paint().apply { strokeWidth = max(1f, dp); strokeCap = Paint.Cap.SQUARE }
        for (bi in 0 until 6) {
            if (nb[bi] == 0) continue
            sp.color = withAlpha(if (bi >= 3) 0xFFFFFF else f.speck, .05f + (bi % 3 + .5f) / 3f * .10f)
            g.drawPoints(buckets[bi], 0, nb[bi], sp)
        }
        val hl = RadialGradient(k - rad * .45f, k - rad * .5f, rad * 1.1f,
            withAlpha(0xFFFAF0, if (sky.day) .16f else .08f), withAlpha(0xFFFAF0, 0f), Shader.TileMode.CLAMP)
        g.drawRect(0f, 0f, s.toFloat(), s.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = hl })
        g.restore()
        return b
    }
}

/** Draws a scene with a Canvas, for the settings preview and its tiles. The wallpaper uses the GPU. */
class ScenePainter {
    private var buf = Array(6) { FloatArray(0) }
    private val cnt = IntArray(6)
    private val paint = Paint().apply { strokeCap = Paint.Cap.SQUARE }

    fun draw(cv: Canvas, s: Scene, f: Finish, back: Bitmap, disc: Bitmap?, dx: Float, dy: Float, dot: Float) {
        cv.drawBitmap(back, 0f, 0f, null)
        if (buf[0].size < s.n * 2) buf = Array(6) { FloatArray(s.n * 2) }
        cnt.fill(0)
        val e = s.empty
        for (i in 0 until s.n) {
            val faded = e > 0f && s.hash[i] < e + .04f
            val bi = s.col[i] * 2 + (if (faded) 1 else 0)
            buf[bi][cnt[bi]++] = s.hx[i] + s.ox[i]
            buf[bi][cnt[bi]++] = s.hy[i] + s.oy[i]
        }
        paint.strokeWidth = dot
        for (bi in 0 until 6) {
            if (cnt[bi] == 0) continue
            paint.color = withAlpha(f.grain[bi / 2], f.grainA * (if (bi % 2 == 1) .25f else 1f))
            cv.drawPoints(buf[bi], 0, cnt[bi], paint)
        }
        if (disc != null) cv.drawBitmap(disc, dx - disc.width / 2f, dy - disc.height / 2f, null)
    }
}
