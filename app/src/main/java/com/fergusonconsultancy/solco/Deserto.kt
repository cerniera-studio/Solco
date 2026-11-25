package com.fergusonconsultancy.solco

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deserto (25 Nov 2026): one great dune, its summit on the crease in the lock-screen clock's place,
 * and the time standing in the sky behind its crest, as a depth effect does. A live wallpaper is
 * drawn beneath the lock screen, so One UI's own clock can never go behind the dune: Solco draws its
 * own clock, in Italiana, and shows it only while the phone is locked. Hide One UI's clock to see it
 * alone. From claude/deserto-design.md, where the seven HD images were drawn the same way.
 */

/** Where the clock stands and the dune rises, in view pixels. */
class DesertoLayout(w: Int, h: Int, cover: Boolean) {
    /** A digit's height, the clock's top, its lines (hours over minutes on the cover) and their height. */
    val digit: Float
    val top: Float
    val lines: Int
    val height: Float
    /** The summit, and the horizon behind it. */
    val sx: Float = w * .5f
    val sy: Float
    val horizon: Float = h * (if (cover) .57f else .60f)

    init {
        if (cover) {
            digit = .30f * w; top = .12f * h; lines = 2; height = digit * 2 * 1.02f
            sy = top + height - digit * .45f
        } else {
            digit = .19f * w; top = .13f * h; lines = 1; height = digit * 1.02f
            sy = top + height - digit * .42f
        }
    }
}

/** The clock's shape: an alpha mask [w] by [h] laid at [top] in view pixels (see ClockPaint). */
class ClockMask(val w: Int, val h: Int, val top: Float, val a: ByteArray) {
    fun at(x: Float, y: Float): Boolean {
        val i = x.toInt(); val j = (y - top).toInt()
        if (i < 0 || j < 0 || i >= w || j >= h) return false
        return (a[j * w + i].toInt() and 0xFF) >= 128
    }
}

/**
 * The dune in the world: X across, Z away from the eye, height up, in metres. A barchan whose crest
 * summit projects to the layout's summit, its crest running obliquely so one face takes the light
 * and the other the shade, the slip face at sand's angle of repose, wind ripples on the windward
 * face, low swells beyond, and a ripple field underfoot.
 */
class Dune(w: Int, h: Int, cover: Boolean, val lay: DesertoLayout, morning: Boolean) {
    val f = (if (cover) h else w) * .9f
    val eye = 1.6f
    private val z0 = 60f
    val cx = lay.sx
    val hd = eye + (lay.horizon - lay.sy) * z0 / f
    private val lx = z0 * (w * (if (cover) .55f else .40f)) / f
    private val wind = if (morning) 1f else -1f
    /** The light: low, from the left in the morning and the right after noon, a little towards us. */
    private val lgx: Float; private val lgy: Float; private val lgz: Float

    init {
        val x = if (morning) -.97f else .97f; val y = .12f; val z = -.22f
        val n = sqrt(x * x + y * y + z * z)
        lgx = x / n; lgy = y / n; lgz = z / n
    }

    fun height(x: Float, z: Float): Float {
        val u = x / lx
        val hc = hd * exp(-(u * u) * 1.15f)
        val zc = z0 + .85f * x * wind + 6f * u * u + 1.5f * sin(u * 2.7f)
        val dz = z - zc
        val wlen = min(hd * 2f, z0 * .7f)
        var ff: Float; var rip = 0f
        if (dz < 0f) {
            ff = max(0f, 1f + dz / wlen)
            ff = Math.pow(ff.toDouble(), 1.35).toFloat()
            rip = .035f * sin(z * 1.9f + x * .55f) * min(1f, (1f - ff) * 5f) * ff
        } else {
            ff = max(0f, 1f - dz / (hd * 1.48f))
        }
        val main = hc * ff + rip * hc
        val far = .9f * sin(x / 23f + z / 41f) + .6f * sin(x / 9.7f - z / 17f) + .4f * sin(z / 7.3f + x / 31f)
        val floor = max(0f, far) * min(1f, z / 120f) + .05f * sin(z * 2.3f + x * .45f + sin(x * .3f)) * exp(-z / 40f)
        val nz = (z - 9f) / 4.5f
        val near = 1.1f * max(0f, sin(x / 6.5f + 1.3f) * exp(-nz * nz))
        return max(main, max(floor, near))
    }

    fun light(x: Float, z: Float): Float {
        val e = .05f
        val hx = (height(x + e, z) - height(x - e, z)) / (2 * e)
        val hz = (height(x, z + e) - height(x, z - e)) / (2 * e)
        val n = sqrt(hx * hx + 1f + hz * hz)
        return max(0f, (-hx * lgx + lgy - hz * lgz) / n)
    }
}
