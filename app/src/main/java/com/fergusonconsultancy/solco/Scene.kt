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
    /** Polvere: the sky the shaft of light falls from. Without one, or with the moon set, there is no shaft. */
    sky: SkyState? = null,
    /** Nebulosa: the room's light, 0 dark to 1 bright, which sets how tightly the arms wind. */
    light: Float = .5f,
    /** Delta: each charge of the week as age in days, gain and seed (see [Charges]). */
    private val charges: FloatArray = FloatArray(0),
) {
    val cover: Boolean = coverOverride ?: FoldWatch.tall(w, h)
    /**
     * Quarter turns from the glass's own upright: 0 upright, 1 turned anticlockwise (the hinge edge at
     * the bottom), 3 turned clockwise. Both screens stand upright taller than wide, so a wider
     * surface is a turned one; the display's rotation says which way. Tiles are never turned.
     */
    val quarter: Int = if (coverOverride != null || w <= h) 0 else if (rotation == 3) 3 else 1
    private val turned = quarter != 0 && style != Style.MAREA && style != Style.POLVERE
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

    /** Polvere: the sky the shaft was built for, so the renderer knows when the sun has moved on. */
    val skyAlt: Float = sky?.alt ?: -1f
    val skyShown: Boolean = sky?.shown ?: false
    private val skyAt: SkyState? = sky
    /** Polvere: which grains hang in the light (and so can settle), and the floor they settle to. */
    private val airborne = BooleanArray(if (style == Style.POLVERE) cap else 0)
    private val floorY = h * .955f

    /** Nebulosa: each grain's place in the galaxy, independent of how tightly it is wound. */
    private val na = FloatArray(if (style == Style.NEBULOSA) cap else 0)
    private val nb = FloatArray(if (style == Style.NEBULOSA) cap else 0)
    private val nc = FloatArray(if (style == Style.NEBULOSA) cap else 0)
    var light: Float = light.coerceIn(0f, 1f)
        private set
    /**
     * Nebulosa moves as a fluid, not a wheel (21 Nov 2026): each grain keeps its home and its
     * displacement in polar terms about the core, and returns along the swirl. The inner grain turns
     * fast and the outer lags behind it, as in a whirlpool, so the arms shear and feather rather than
     * turning as one rigid disc.
     */
    private val nbs = if (style == Style.NEBULOSA) cap else 0
    private val pRh = FloatArray(nbs); private val pTh = FloatArray(nbs)
    private val dRh = FloatArray(nbs); private val dTh = FloatArray(nbs)
    private val wRh = FloatArray(nbs); private val wTh = FloatArray(nbs)

    val n: Int = when (style) {
        Style.SOLCO -> genSolco()
        Style.TRONCO -> genTronco()
        Style.MAREA -> genMarea()
        Style.DUNA -> genDuna()
        Style.POLVERE -> genPolvere()
        Style.NEBULOSA -> genNebulosa()
        Style.DELTA -> genDelta()
    }.also { k ->
        if (turned) for (i in 0 until k) { val x = hx[i]; val y = hy[i]; hx[i] = sx(x, y); hy[i] = sy(x, y) }
    }

    init {
        if (style == Style.NEBULOSA) for (i in 0 until n) {
            pRh[i] = hypot(hx[i] - cx, hy[i] - cy); pTh[i] = kotlin.math.atan2(hy[i] - cy, hx[i] - cx)
        }
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

    // ---- Polvere: dust in a shaft of light from the disc; what has settled lies on the floor ----
    private fun genPolvere(): Int {
        val sk = skyAt
        val d = if (sk != null && sk.shown) disc(sk) else null
        // The shaft falls from the disc towards the floor, leaning away from the hinge
        val side = if (d == null || d[0] >= cx) 1f else -1f
        val lean = .42f * side
        val ul = sqrt(lean * lean + 1f)
        val ux = lean / ul; val uy = 1f / ul
        val rng = Rng(71)
        var k = 0; var guard = 0
        while (k < cap && guard < cap * 40) {
            guard++
            val x = rng.next() * w; val y = rng.next() * h
            var dens: Float; var c: Int; var air = false
            if (y > floorY + 1.5f * dp * sin(x / (w * .09f))) {
                // The floor: everything that ever settled, densest at the bottom
                dens = .55f + .35f * ((y - floorY) / (h - floorY)).coerceIn(0f, 1f); c = if (rng.next() < .3f) 0 else 2
            } else if (d != null) {
                val dx = x - d[0]; val dy = y - d[1]
                val t = dx * ux + dy * uy
                val perp = abs(-dx * uy + dy * ux)
                val half = d[2] * .85f + t * .30f
                if (t > 0f && perp < half) {
                    // In the light: a fine haze, brighter motes, both fading as the shaft spreads and towards its edges
                    val edge = 1f - (perp / half).pow(3f)
                    val fade = (1f - t / (h * 1.1f)).coerceIn(.25f, 1f)
                    val mote = rng.next() < .045f
                    dens = (if (mote) 1f else .26f) * edge * fade; c = if (mote) 0 else 2; air = true
                } else { dens = .010f; c = 2 }
            } else { dens = .006f; c = 2 }
            if (rng.next() > dens) continue
            hx[k] = x; hy[k] = y; col[k] = c.toByte(); hash[k] = rng.next(); airborne[k] = air
            k++
        }
        return k
    }

    /**
     * Polvere: [share] of the dust in the light has settled while the phone lay still. It is put on the
     * floor beneath where it hung, and the spring lifts it home, which is the picking up.
     */
    fun settle(share: Float) {
        if (style != Style.POLVERE) return
        val f = share.coerceIn(0f, 1f)
        if (f <= 0f) return
        for (i in 0 until n) {
            if (!airborne[i] || hash[i] >= f) continue
            val rest = floorY - (hash[i] / f) * 7f * dp - 1f * dp
            ox[i] = (hash[i] - .5f) * 6f * dp
            oy[i] = rest - hy[i]
            vx[i] = 0f; vy[i] = 0f
        }
    }

    // ---- Nebulosa: two arms wound round the hinge, tighter as the room darkens ----
    private val nebR: Float get() = r * .92f

    /** Where grain [i] sits at light [t], on the upright glass. */
    private fun nebGlass(i: Int, t: Float): Long {
        val big = nebR
        val x: Float; val y: Float
        when {
            na[i] >= 0f -> {
                val rho = big * (.05f + .90f * na[i])
                val wind = 2.6f - 1.3f * t
                val th = nc[i] + wind * kotlin.math.ln(rho / (big * .05f))
                val spread = (big * .008f + rho * .07f) * (.75f + .45f * t)
                x = gcx + cos(th) * rho - sin(th) * nb[i] * spread
                y = gcy + sin(th) * rho + cos(th) * nb[i] * spread
            }
            na[i] > -1.5f -> {
                val sig = big * (.030f + .03f * t)
                x = gcx + nb[i] * sig; y = gcy + nc[i] * sig
            }
            else -> { x = nb[i] * gw; y = nc[i] * gh }
        }
        return (x.toBits().toLong() shl 32) or (y.toBits().toLong() and 0xFFFFFFFFL)
    }

    private fun gauss(rng: Rng): Float {
        val u = rng.next().coerceAtLeast(1e-6f); val v = rng.next()
        return sqrt(-2f * kotlin.math.ln(u)) * cos(6.2832f * v)
    }

    private fun genNebulosa(): Int {
        val rng = Rng(83)
        val total = (cap * .62f).toInt()
        var k = 0
        while (k < total) {
            val u = rng.next()
            val c: Int
            when {
                u < .11f -> { na[k] = -1f; nb[k] = gauss(rng); nc[k] = gauss(rng); c = 0 }
                u < .19f -> { na[k] = -2f; nb[k] = rng.next(); nc[k] = rng.next(); c = 2 }
                else -> {
                    val a = rng.next().pow(1.35f)
                    na[k] = a; nb[k] = gauss(rng); nc[k] = (if (rng.next() < .5f) 0f else 3.1416f) + (rng.next() - .5f) * .25f
                    val p = rng.next()
                    c = if (a < .3f) (if (p < .75f) 0 else 1) else if (p < .45f) 2 else if (p < .62f) 1 else 0
                }
            }
            val g = nebGlass(k, light)
            hx[k] = Float.fromBits((g ushr 32).toInt()); hy[k] = Float.fromBits(g.toInt())
            col[k] = c.toByte(); hash[k] = rng.next()
            k++
        }
        return k
    }

    /** Nebulosa: the room's light changed. The grain is carried to its new arms by the spring. Returns true if anything moves. */
    fun setLight(t: Float): Boolean {
        if (style != Style.NEBULOSA) return false
        val nt = t.coerceIn(0f, 1f)
        if (abs(nt - light) < .04f) return false
        val was = light
        light = nt
        for (i in 0 until n) {
            val g = nebGlass(i, nt)
            val gx0 = Float.fromBits((g ushr 32).toInt()); val gy0 = Float.fromBits(g.toInt())
            val x = sx(gx0, gy0); val y = sy(gx0, gy0)
            hx[i] = x; hy[i] = y
            // Keep the grain where it is and let the swirl carry it to its new arm, the short way round
            val rho = hypot(x - cx, y - cy); val th = kotlin.math.atan2(y - cy, x - cx)
            var d = pTh[i] + dTh[i] - th
            while (d > 3.1416f) d -= 6.2832f
            while (d < -3.1416f) d += 6.2832f
            // An arm rewinds the whole way the light asks, outer grain furthest, never torn by taking
            // the nearer way round: the turn it owes is the change in winding times how far out it lies
            if (na[i] >= 0f) {
                val owed = (1.3f * (nt - was)) * kotlin.math.ln((.05f + .90f * na[i]) / .05f)
                d += 6.2832f * Math.round((owed - d) / 6.2832f)
            }
            dTh[i] = d; dRh[i] = pRh[i] + dRh[i] - rho
            pRh[i] = rho; pTh[i] = th
        }
        return true
    }

    // ---- Delta: a channel for each charge of the week, carved out from the hinge ----
    private fun genDelta(): Int {
        val cell = 3f * dp
        val cw = (gw / cell).toInt() + 1; val ch = (gh / cell).toInt() + 1
        // Distance from each cell to the nearest channel's bank: negative in the water
        val dist = FloatArray(cw * ch) { 1e9f }
        val fresh = BooleanArray(cw * ch)
        val m = charges.size / 3
        var newest = -1
        for (j in 0 until m) if (newest < 0 || charges[3 * j] < charges[3 * newest]) newest = j
        val stack = ArrayList<FloatArray>()
        for (j in 0 until m) {
            val age = charges[3 * j]; val gain = charges[3 * j + 1]
            val rng = Rng(charges[3 * j + 2].toInt().coerceAtLeast(1))
            val silt = (1f - .55f * age / 7f).coerceIn(.3f, 1f)
            val w0 = (1.4f + 7.5f * gain) * dp * silt
            val axis = if (cover || rng.next() < .5f) 0f else 3.1416f
            stack.clear()
            stack += floatArrayOf(gcx + (if (axis == 0f) 1f else -1f) * dp, gcy + (rng.next() - .5f) * gh * .55f, axis + (rng.next() - .5f) * .7f, w0, 0f)
            val isNew = j == newest
            var budget = 4000
            while (stack.isNotEmpty() && budget > 0) {
                val b = stack.removeAt(stack.size - 1)
                var x = b[0]; var y = b[1]; var a = b[2]; var wd = b[3]; val depth = b[4].toInt()
                while (budget-- > 0) {
                    x += cos(a) * 2f * dp; y += sin(a) * 2f * dp
                    if (x < 0f || y < 0f || x >= gw || y >= gh) break
                    // Meanders, drawn back towards the way away from the hinge
                    a += (rng.next() - .5f) * .24f + kotlin.math.atan2(sin(axis - a), cos(axis - a)) * .025f
                    // Stamp the channel and its banks into the field
                    val reach = wd + 5f * dp
                    val i0 = ((x - reach) / cell).toInt().coerceAtLeast(0); val i1 = ((x + reach) / cell).toInt().coerceAtMost(cw - 1)
                    val j0 = ((y - reach) / cell).toInt().coerceAtLeast(0); val j1 = ((y + reach) / cell).toInt().coerceAtMost(ch - 1)
                    for (jj in j0..j1) for (ii in i0..i1) {
                        val dd = hypot((ii + .5f) * cell - x, (jj + .5f) * cell - y) - wd
                        val q = jj * cw + ii
                        if (dd < dist[q]) { dist[q] = dd; fresh[q] = isNew }
                    }
                    // Distributaries split off, narrower
                    if (depth < 4 && rng.next() < .016f) {
                        val sgn = if (rng.next() < .5f) 1f else -1f
                        stack += floatArrayOf(x, y, a + .5f * sgn, wd * .72f, depth + 1f)
                        a -= .2f * sgn; wd *= .86f
                    }
                }
            }
        }
        val rng = Rng(97)
        var k = 0; var guard = 0
        val bank = 2.6f * dp
        while (k < cap && guard < cap * 40) {
            guard++
            val x = rng.next() * gw; val y = rng.next() * gh
            val q = (y / cell).toInt().coerceIn(0, ch - 1) * cw + (x / cell).toInt().coerceIn(0, cw - 1)
            val d = dist[q]
            if (d < 0f) continue
            val dens: Float; val c: Int
            if (d < bank) { dens = .95f; c = if (fresh[q]) 1 else 0 }
            else {
                // The sandbars between: faint bars laid along the flow
                dens = .26f + .16f * (.5f + .5f * sin(y / (9f * dp) + abs(x - gcx) / (37f * dp)))
                c = if (rng.next() < .22f) 0 else 2
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

    /**
     * Notturno: where a planet's spark sits, x, y and drawn width. On the disc's arc by its own
     * height, on a ring nearer the hinge for the east and further out for the west, so the order
     * along the arc is the order of height in the real sky.
     */
    fun spark(p: Spark, sky: SkyState? = null): FloatArray {
        val phi = Math.toRadians(-60.0 + 120.0 * p.alt).toFloat()
        var ring = r * (.22f + .18f * p.west)
        // Never on the moon: a spark that would sit on the disc moves out along its ray, clear of it
        if (sky != null && sky.shown) {
            val d = disc(sky)
            val clear = d[2] + 16f * dp
            repeat(2) {
                val x = gcx + cos(phi) * ring; val y = gcy - sin(phi) * ring
                if (hypot(sx(x, y) - d[0], sy(x, y) - d[1]) < clear) ring = r * .30f + clear * (if (ring >= r * .30f) 1f else -1f)
            }
        }
        val x = gcx + cos(phi) * ring; val y = gcy - sin(phi) * ring
        return floatArrayOf(sx(x, y), sy(x, y), SparkPainter.width(p.size, dp))
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
        if (style == Style.NEBULOSA) {
            // A whirlpool, not a flywheel: the core is set turning first and fastest, and the turn
            // spreads outwards, weaker and later, so the arms wind up and then unwind
            val ref = r * .25f
            for (i in 0 until n) {
                val rho = pRh[i].coerceAtLeast(6f * dp)
                // The faint dust between the arms barely stirs, so no bare corner opens up
                val dust = if (na[i] < -1.5f) .2f else 1f
                kick[i] = (if (fast) .030f else .018f) * dir * dust * sqrt(ref / rho).coerceIn(.35f, 2.2f) * (.75f + .5f * hash[i])
                kickAt[i] = now + ((rho / r).coerceAtMost(1f) * spread * 1.6f).toLong()
            }
            empty = if (fast && concentrato) depth else 0f
            decay = linger
            return
        }
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
        val neb = style == Style.NEBULOSA
        for (i in 0 until n) {
            if (neb) { val e = flow(i, now, dt); if (e > energy) energy = e; continue }
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

    /**
     * Nebulosa, one grain, one frame. Pushes from a finger or the fold are taken into the swirl;
     * the turn relaxes with a stiffness that falls with distance from the core (inner fast, outer
     * slow), each grain a little different, and with no overshoot, as a thick liquid settles. While
     * it moves, a fixed field of small eddies curls its path; at rest the eddies are gone.
     */
    private fun flow(i: Int, now: Long, dt: Float): Float {
        var th = pTh[i] + dTh[i]
        var rho = (pRh[i] + dRh[i]).coerceAtLeast(0f)
        val c = cos(th); val s = sin(th)
        val rr = rho.coerceAtLeast(6f * dp)
        if (vx[i] != 0f || vy[i] != 0f) {
            wRh[i] += vx[i] * c + vy[i] * s
            wTh[i] += (-vx[i] * s + vy[i] * c) / rr
            vx[i] = 0f; vy[i] = 0f
        }
        if (kick[i] != 0f && now >= kickAt[i]) { wTh[i] += kick[i]; kick[i] = 0f }
        val q = (r * .25f / rr).pow(1.5f).coerceIn(.12f, 6f) * (.75f + .5f * hash[i])
        val kt = .0045f * q
        val kr = .010f * (.75f + .5f * hash[i])
        wTh[i] = (wTh[i] - dTh[i] * kt * dt) * kotlin.math.exp(-1.9f * sqrt(kt) * dt)
        wRh[i] = (wRh[i] - dRh[i] * kr * dt) * kotlin.math.exp(-1.9f * sqrt(kr) * dt)
        dTh[i] += wTh[i] * dt; dRh[i] += wRh[i] * dt
        th = pTh[i] + dTh[i]
        rho = (pRh[i] + dRh[i]).coerceAtLeast(0f)
        var x = cx + cos(th) * rho; var y = cy + sin(th) * rho
        val off = abs(dTh[i]) * rr + abs(dRh[i])
        // Broad, slow eddies, wide enough that the arm bends rather than crimps
        val amp = min(5f * dp, off * .10f)
        if (amp > .2f * dp) {
            x += sin(y / (97f * dp) + 1.3f * sin(x / (151f * dp))) * amp
            y += sin(x / (113f * dp) + 1.3f * sin(y / (139f * dp))) * amp
        }
        ox[i] = x - hx[i]; oy[i] = y - hy[i]
        if (kick[i] != 0f) return 1f
        return abs(wTh[i]) * rr + abs(wRh[i]) + off * .01f
    }

    /** Interleaved x, y for every grain. */
    fun positions(out: FloatArray) {
        for (i in 0 until n) { out[2 * i] = hx[i] + ox[i]; out[2 * i + 1] = hy[i] + oy[i] }
    }
}
