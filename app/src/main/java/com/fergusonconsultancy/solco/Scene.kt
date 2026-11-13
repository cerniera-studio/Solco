package com.fergusonconsultancy.solco

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The grain: where each grain lives, where it has been pushed, and how it springs home.
 * Pure arithmetic, shared by the wallpaper (drawn with the GPU) and the settings preview (drawn
 * with a Canvas). Everything is in surface pixels; [dp] scales distances and speeds.
 *
 * Rings centre on the hinge: the left edge of the tall cover screen, the crease of the main one.
 *
 * Both screens, both ways round (12 Nov 2026). Solco, Tronco and Duna answer to the hinge, which
 * stays where it is on the glass when the phone turns, so they are laid out the right way up for
 * the glass and then turned with it: on the cover held sideways, the hinge runs along the bottom
 * (or the top). Marea answers to gravity, so its water always fills from the bottom of the view.
 */
class Scene(
    val w: Int,
    val h: Int,
    val dp: Float,
    val style: Style,
    private val rings: FloatArray,
    coverOverride: Boolean? = null,
    /** Marea: the battery, 0 to 1, and whether it is charging (the only time its surface moves). */
    val level: Float = 1f,
    charging: Boolean = false,
    /** The display's rotation (Surface.ROTATION_*), to tell which way a sideways surface was turned. */
    rotation: Int = 0,
) {
    val cover: Boolean = coverOverride ?: FoldWatch.tall(w, h)
    /**
     * Quarter turns from the glass's own upright: 0 upright, 1 turned anticlockwise (the hinge edge at
     * the bottom), 3 turned clockwise. Both screens stand upright taller than wide, so a wider
     * surface is a turned one; the display's rotation says which way. Tiles are never turned.
     */
    val quarter: Int = if (coverOverride != null || w <= h) 0 else if (rotation == 3) 3 else 1
    private val turned = quarter != 0 && style != Style.MAREA
    /** The glass's own upright size, in which the hinge styles are laid out before turning. */
    private val gw: Int = if (turned) h else w
    private val gh: Int = if (turned) w else h
    private val gcx: Float = if (cover) 0f else gw / 2f
    private val gcy: Float = gh / 2f
    /** Upright glass to this surface. */
    private fun sx(x: Float, y: Float) = when { !turned -> x; quarter == 1 -> y; else -> gh - y }
    private fun sy(x: Float, y: Float) = when { !turned -> y; quarter == 1 -> gw - x; else -> x }
    val cx: Float = sx(gcx, gcy)
    val cy: Float = sy(gcx, gcy)
    val r: Float = hypot(max(gcx, gw - gcx), max(gcy, gh - gcy))

    /** About one grain per 4.2 dp², the density judged right in the preview, capped for the CPU. */
    val cap: Int = min(140_000, ((w / dp) * (h / dp) / 4.2f).toInt()).coerceAtLeast(1)

    val hx = FloatArray(cap); val hy = FloatArray(cap)
    val ox = FloatArray(cap); val oy = FloatArray(cap)
    private val vx = FloatArray(cap); private val vy = FloatArray(cap)
    private val kick = FloatArray(cap); private val kickAt = LongArray(cap)
    val hash = FloatArray(cap)
    val col = ByteArray(cap)

    /** Concentrato: grains whose hash is below this drop to a quarter. Decays as the grain settles. */
    var empty = 0f
        private set

    private var last = 0L
    private var pressed = false
    private var lastX = 0f
    private var lastY = 0f

    val n: Int = when (style) {
        Style.SOLCO -> genSolco()
        Style.TRONCO -> genTronco()
        Style.MAREA -> genMarea()
        Style.DUNA -> genDuna()
    }.also { k ->
        if (turned) for (i in 0 until k) { val x = hx[i]; val y = hy[i]; hx[i] = sx(x, y); hy[i] = sy(x, y) }
    }

    // ---- Marea: grain filled to the battery level, foam at the surface, sediment at the floor ----
    /** Where the surface lies at x: the level, with a slow swell along it. */
    fun surface(x: Float): Float {
        // Full sits a sixth of the way down, empty near the floor: the surface and its foam always show
        val base = h * (1f - (.12f + .70f * level.coerceIn(0f, 1f)))
        return base + 3.2f * dp * sin(x / (w * .23f) + 1.3f) + 1.6f * dp * sin(x / (w * .071f))
    }

    private fun genMarea(): Int {
        val rng = Rng(43)
        var k = 0; var guard = 0
        val foam = 7f * dp
        while (k < cap && guard < cap * 40) {
            guard++
            val x = rng.next() * w; val y = rng.next() * h
            val sy = surface(x)
            if (y < sy - foam * .6f) continue
            val depth = (y - sy) / (h - sy).coerceAtLeast(1f)
            // Dense foam at the surface, a clear band beneath it, then the body thickening to the floor
            val dens: Float; val c: Int
            if (y < sy + foam) { dens = .95f; c = 1 }
            else { dens = .10f + .55f * depth.pow(1.6f) + .25f * (.5f + .5f * sin(y / (11f * dp))) * depth; c = if (depth > .82f) 2 else 0 }
            if (rng.next() > dens) continue
            hx[k] = x; hy[k] = y; col[k] = c.toByte(); hash[k] = rng.next()
            k++
        }
        return k
    }

    /** Marea while charging: the surface breathes. Nothing else ever moves on its own. */
    var breathing = charging && style == Style.MAREA
    private var breathT0 = 0L

    // ---- Duna: wind ripples blowing out from the hinge, gentle windward, steep on the lee ----
    private fun genDuna(): Int {
        val rng = Rng(53)
        val lambda = 26f * dp
        var k = 0; var guard = 0
        while (k < cap && guard < cap * 40) {
            guard++
            val x = rng.next() * gw; val y = rng.next() * gh
            // Distance downwind from the hinge, the ripples curving a little as wind does
            val u = abs(x - gcx) + .035f * gh * sin(y / gh * 6.2832f * 1.3f + abs(x - gcx) / (gw * .4f))
            val ph = (u / lambda) % 1f
            val ripple = if (ph < .75f) (ph / .75f).pow(1.5f) else (1f - (ph - .75f) / .25f).pow(3f)
            val dune = .55f + .45f * sin(u / (lambda * 9f) + y / (gh * .9f))
            val dens = .06f + .80f * ripple * dune
            if (rng.next() > dens) continue
            hx[k] = x; hy[k] = y
            col[k] = (if (ripple > .82f) 1 else if (ripple < .25f && rng.next() < .6f) 2 else 0).toByte()
            hash[k] = rng.next()
            k++
        }
        return k
    }

    /** Gravity across the screen (px/s² in screen axes), from the tilt sensor; Duna only. */
    private var gx = 0f
    private var gy = 0f
    /** Below this share of g a slope holds: the angle of repose, about 17°. */
    private val repose = .30f * 9.81f

    fun tilt(x: Float, y: Float) { gx = x; gy = y }

    /** Only the loosest fifth of the grain slides, each at its own speed, so no seam forms. */
    private fun loose(i: Int) = if (style == Style.DUNA && hash[i] > .80f) (hash[i] - .80f) / .20f else 0f

    /** True if the slope is past the angle of repose, so the loose grain will move. */
    fun sliding() = style == Style.DUNA && hypot(gx, gy) > repose

    // ---- Solco: bands of grain round the hinge, densest towards each band's outer edge ----
    private fun genSolco(): Int {
        val bands = if (cover) arrayOf(
            floatArrayOf(.40f, .52f, .55f, 0f), floatArrayOf(.58f, .74f, .9f, 1f),
            floatArrayOf(.79f, .86f, .6f, 2f), floatArrayOf(.90f, 1.02f, 1f, 0f))
        else arrayOf(
            floatArrayOf(.40f, .50f, .5f, 0f), floatArrayOf(.55f, .70f, .85f, 1f),
            floatArrayOf(.76f, .84f, .6f, 2f), floatArrayOf(.88f, 1.02f, 1f, 0f))
        var wsum = 0f
        for (b in bands) wsum += (b[1] - b[0]) * b[2]
        val rng = Rng(29)
        var k = 0; var guard = 0
        while (k < cap && guard < cap * 40) {
            guard++
            var u = rng.next() * wsum
            var b = bands[0]
            for (bb in bands) {
                val m = (bb[1] - bb[0]) * bb[2]
                if (u < m) { b = bb; break }
                u -= m
            }
            val t = rng.next()
            if (rng.next() > t.pow(1.8f) * .92f + .08f) continue
            val rad = r * (b[0] + (b[1] - b[0]) * t) + (rng.next() - .5f) * 2f * dp
            val a = rng.next() * 6.2832f
            val x = gcx + cos(a) * rad; val y = gcy + sin(a) * rad
            if (x < 0f || y < 0f || x >= gw || y >= gh) continue
            hx[k] = x; hy[k] = y
            val p = rng.next(); val base = b[3].toInt()
            col[k] = (if (p < .72f) base else if (p < .9f) (base + 1) % 3 else (base + 2) % 3).toByte()
            hash[k] = rng.next()
            k++
        }
        return k
    }

    // ---- Tronco: one ring per day, its width that day's use; the bark closes the newest ring ----
    private fun wob(a: Float, rr: Float) =
        1f + .028f * sin(3 * a + rr / (r * .2f)) + .018f * sin(7 * a + 1.7f) + .01f * sin(13 * a + rr / (r * .05f))

    private fun genTronco(): Int {
        val days = if (rings.isEmpty()) floatArrayOf(.3f) else rings
        val unit = r * .032f
        var total = 1.2f
        for (d in days) total += d
        // Rings keep their natural size until a month's worth would overfill the screen
        val sc = min(unit, r * .97f / total)
        val edges = FloatArray(days.size + 1)
        edges[0] = 1.2f * sc
        for (i in days.indices) edges[i + 1] = edges[i] + days[i] * sc
        val outer = edges[days.size]
        val rr0 = outer * 1.06f
        val rng = Rng(61)
        var k = 0; var guard = 0
        while (k < cap && guard < cap * 40) {
            guard++
            val a = rng.next() * 6.2832f
            val rr = sqrt(rng.next()) * rr0
            val wb = wob(a, rr)
            val x = gcx + cos(a) * rr * wb; val y = gcy + sin(a) * rr * wb
            if (x < 0f || y < 0f || x >= gw || y >= gh) continue
            var dens: Float; var c: Int
            if (rr < edges[0]) { dens = .55f; c = 2 }
            else if (rr >= outer) { dens = .85f; c = 0 }
            else {
                var lo = 0; var hi = days.size
                while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (edges[m] <= rr) lo = m else hi = m }
                val t = (rr - edges[lo]) / (edges[lo + 1] - edges[lo])
                val late = if (t > .8f) ((t - .8f) / .2f).pow(.7f) else 0f
                dens = .10f + .85f * late
                c = if (late > .3f) 0 else if (rng.next() < .8f) 1 else 2
            }
            if (rng.next() > dens) continue
            hx[k] = x; hy[k] = y; col[k] = c.toByte(); hash[k] = rng.next()
            k++
        }
        return k
    }

    // ---- The disc rides a ring round the hinge: low at dawn and dusk, highest at noon ----
    /** x, y and radius of the disc for [sky]. */
    fun disc(sky: SkyState): FloatArray {
        val rad = (if (cover) .21f else .11f) * min(w, h)
        val phi = Math.toRadians(-60.0 + 120.0 * sky.alt).toFloat()
        val ring = r * .30f
        // Placed on the upright glass, then turned with it, so the disc keeps its place by the hinge
        val x = gcx + cos(phi) * ring; val y = gcy - sin(phi) * ring
        return floatArrayOf(sx(x, y), sy(x, y), rad)
    }

    // ---- Forces ----
    /**
     * The fold: a tangential push along the swing, outer grain first and furthest. [dir] is 1 for
     * opening, -1 for closing. A fast fold with Concentrato on empties the bands for a moment.
     */
    /** Concentrato's fill-back: the share of the emptiness left after each frame at rest. */
    private var decay = .95f

    fun stir(dir: Int, fast: Boolean, concentrato: Boolean, now: Long, depth: Float = .75f, linger: Float = .95f) {
        val k0 = (if (fast) 7.5f else 4.2f) * dp * dir
        val spread = if (fast) 260f else 520f
        for (i in 0 until n) {
            val t = hypot(hx[i] - cx, hy[i] - cy) / r
            kick[i] = k0 * (.35f + .65f * t) * (.85f + .3f * hash[i])
            kickAt[i] = now + ((1f - t) * spread).toLong()
        }
        empty = if (fast && concentrato) depth else 0f
        decay = linger
    }

    /** A finger: grain yields under it and is dragged a little with it; neighbours follow. */
    fun touch(x: Float, y: Float, down: Boolean) {
        if (!down) { pressed = false; return }
        val rho = 64f * dp; val rho2 = rho * rho
        val mvx = if (pressed) x - lastX else 0f
        val mvy = if (pressed) y - lastY else 0f
        for (i in 0 until n) {
            val dx = hx[i] + ox[i] - x; val dy = hy[i] + oy[i] - y
            val d2 = dx * dx + dy * dy
            if (d2 > rho2) continue
            val d = sqrt(d2).coerceAtLeast(1f)
            val f = (1f - d / rho).let { it * it }
            vx[i] += dx / d * f * 2.2f * dp + mvx * f * .18f
            vy[i] += dy / d * f * 2.2f * dp + mvy * f * .18f
        }
        pressed = true; lastX = x; lastY = y
    }

    /**
     * One frame of a soft spring home, with a touch of give, because grain is soft. Returns true
     * while anything still moves; when it returns false the caller stops drawing.
     */
    fun step(now: Long): Boolean {
        val dt = if (last == 0L) 1f else min(2.5f, (now - last) / 16.67f).coerceAtLeast(.1f)
        last = now
        val k = .010f * dt
        val damp = .915f.pow(dt)
        var energy = 0f
        // Duna: past the angle of repose the loose grain runs downhill; below it, the wind blows it home slowly
        val slide = sliding()
        val gm = hypot(gx, gy).coerceAtLeast(1e-3f)
        val pull = if (slide) (gm - repose) / 9.81f * .9f * dp * dt else 0f
        val ux = gx / gm; val uy = gy / gm
        for (i in 0 until n) {
            val m = loose(i)
            if (m > 0f && (slide || abs(ox[i]) + abs(oy[i]) > .5f * dp)) {
                if (slide) {
                    vx[i] = (vx[i] + ux * pull * (.35f + .65f * m)) * .94f.pow(dt)
                    vy[i] = (vy[i] + uy * pull * (.35f + .65f * m)) * .94f.pow(dt)
                } else {
                    // The wind: a much softer spring than the touch's, so it reads as resettling
                    vx[i] = (vx[i] - ox[i] * .0016f * dt) * .9f.pow(dt)
                    vy[i] = (vy[i] - oy[i] * .0016f * dt) * .9f.pow(dt)
                }
                ox[i] += vx[i] * dt; oy[i] += vy[i] * dt
                // Pools at the edge of the screen
                val x = hx[i] + ox[i]; val y = hy[i] + oy[i]
                if (x < 0f) { ox[i] = -hx[i]; vx[i] = 0f } else if (x > w - 1f) { ox[i] = w - 1f - hx[i]; vx[i] = 0f }
                if (y < 0f) { oy[i] = -hy[i]; vy[i] = 0f } else if (y > h - 1f) { oy[i] = h - 1f - hy[i]; vy[i] = 0f }
                val e = abs(vx[i]) + abs(vy[i])
                if (e > energy) energy = e
                continue
            }
            if (kick[i] != 0f) {
                if (now >= kickAt[i]) {
                    val dx = hx[i] - cx; val dy = hy[i] - cy
                    val rr = hypot(dx, dy).coerceAtLeast(1f)
                    vx[i] += -dy / rr * kick[i]; vy[i] += dx / rr * kick[i]
                    kick[i] = 0f
                }
                energy = 1f
            }
            vx[i] = (vx[i] - ox[i] * k) * damp
            vy[i] = (vy[i] - oy[i] * k) * damp
            ox[i] += vx[i] * dt; oy[i] += vy[i] * dt
            val e = abs(vx[i]) + abs(vy[i]) + (abs(ox[i]) + abs(oy[i])) * .01f
            if (e > energy) energy = e
        }
        if (empty > 0f) {
            if (energy < 1.2f * dp) empty *= decay.pow(dt)
            if (empty < .01f) empty = 0f
        }
        // Marea on charge: the grain near the surface rises and falls on a slow swell
        if (breathing) {
            if (breathT0 == 0L) breathT0 = now
            val t = (now - breathT0) / 1000f
            val band = 40f * dp
            for (i in 0 until n) {
                val d = hy[i] - surface(hx[i])
                if (d > band) continue
                val f = 1f - (d.coerceAtLeast(0f) / band)
                oy[i] = 2.6f * dp * f * sin(t * 1.1f + hx[i] / (w * .18f)) + oy[i] * .2f
            }
        }
        val moving = energy > .01f * dp || pressed || empty > 0f || breathing
        if (!moving) last = 0L
        return moving
    }

    /** Interleaved x, y for every grain. */
    fun positions(out: FloatArray) {
        for (i in 0 until n) { out[2 * i] = hx[i] + ox[i]; out[2 * i + 1] = hy[i] + oy[i] }
    }
}
