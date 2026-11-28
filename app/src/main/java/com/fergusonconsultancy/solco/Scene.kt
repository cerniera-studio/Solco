package com.fergusonconsultancy.solco

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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
 * Both screens, both ways round (12 Nov 2026). Solco and Tronco answer to the hinge, which
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
    /** The sky: the sun or moon that lights Deserto and Orma, and Notturno's night. */
    sky: SkyState? = null,
    /** Pioggia: the weather outside now; without it, an overcast haze. */
    private val weather: Wx? = null,
    /** Stagione: the year's daylight here, one value a day, and today. */
    private val season: Season? = null,
    /** Sentiero: today's touches on this screen, as fractions of the upright glass (see [Trail]). */
    private val trail: FloatArray = FloatArray(0),
    /** Delta: each charge of the week as age in days, gain and seed (see [Charges]). */
    private val charges: FloatArray = FloatArray(0),
    /** Orma: today's steps, one value a minute from midnight (see [Steps]), and the minute it is now. */
    private val walk: FloatArray = FloatArray(0),
    private val walkNow: Int = 0,
    /** Orma: the day, so each day's walk wanders its own way and keeps it all day. */
    private val walkDay: Long = 0L,
) {
    val cover: Boolean = coverOverride ?: FoldWatch.tall(w, h)
    /**
     * Quarter turns from the glass's own upright: 0 upright, 1 turned anticlockwise (the hinge edge at
     * the bottom), 3 turned clockwise. Both screens stand upright taller than wide, so a wider
     * surface is a turned one; the display's rotation says which way. Tiles are never turned.
     */
    val quarter: Int = if (coverOverride != null || w <= h) 0 else if (rotation == 3) 3 else 1
    private val turned = quarter != 0 && style != Style.MAREA && style != Style.PIOGGIA && style != Style.DESERTO
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

    /** Deserto: whether the dune was lit from the left (the morning) and by night, so the renderer knows when to relight it. */
    val litMorning: Boolean = (sky?.minutes ?: 600) < 720
    val atNight: Boolean = sky?.day == false
    private val skyAt: SkyState? = sky
    /** Deserto: the skyline, to set the sun or moon above it. Declared before [n], because laying the grain sets it. */
    private var deserSky = FloatArray(0)
    private var deserCell = 1f

    /**
     * Orma: how many steps each print stands for on this screen, and how many prints were laid, for
     * Settings. Declared before [n], because laying the grain sets them.
     */
    var perPrint = 0
        private set
    var prints = 0
        private set

    val n: Int = when (style) {
        Style.SOLCO -> genSolco()
        Style.TRONCO -> genTronco()
        Style.MAREA -> genMarea()
        Style.DELTA -> genDelta()
        Style.PIOGGIA -> genPioggia()
        Style.STAGIONE -> genStagione()
        Style.SENTIERO -> genSentiero()
        Style.DESERTO -> genDeserto()
        Style.ORMA -> genOrma()
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

    // ---- Deserto: one great dune ----
    /**
     * Deserto (25 Nov 2026). The dune is a true heightfield seen from a low eye (see [Dune]), cast
     * column by column from near to far, each cell lit by the low sun or moon and exposed for the
     * brightest sand, as a camera would be. Grain then falls by the light, from a fixed number of
     * tries, so the shade stays dark. The backlit crest catches a rim in the accent; at night the sky
     * holds a few stars. Its clock was removed on 28 Nov 2026: the sky above the crest is left empty.
     */
    private fun genDeserto(): Int {
        val lay = DesertoLayout(w, h, cover)
        val minutes = skyAt?.minutes ?: 600
        val night = skyAt?.day == false
        val dune = Dune(w, h, cover, lay, minutes < 720)
        val cell = 2f * dp
        val cw = (w / cell).toInt() + 1; val ch = (h / cell).toInt() + 1
        val light = FloatArray(cw * ch) { -1f }
        val great = BooleanArray(cw * ch)
        val skyline = FloatArray(cw)
        for (i in 0 until cw) {
            val x = (i + .5f) * cell
            var ymax = h.toFloat()
            var z = 3f
            while (z < 900f && ymax > 0f) {
                val xw = (x - dune.cx) * z / dune.f
                val hh = dune.height(xw, z)
                val ys = lay.horizon + (dune.eye - hh) * dune.f / z
                if (ys < ymax) {
                    val b = dune.light(xw, z)
                    val j0 = max(0, (ys / cell).toInt()); val j1 = min(ch, (ymax / cell).toInt() + 1)
                    for (j in j0 until j1) { val q = j * cw + i; if (light[q] < 0f) { light[q] = b; great[q] = hh > 2f } }
                    ymax = ys
                }
                z *= 1.012f
            }
            skyline[i] = ymax
        }
        // Exposed for the brightest sand of the great dune (its 98th percentile)
        val hist = IntArray(256); var lit = 0
        for (q in light.indices) if (light[q] >= 0f && great[q]) { hist[(light[q] * 255f).toInt().coerceIn(0, 255)]++; lit++ }
        var top = 1f
        if (lit > 0) { var acc = 0; for (b in 0 until 256) { acc += hist[b]; if (acc >= lit * .98f) { top = max(b / 255f, .01f); break } } }
        deserSky = skyline; deserCell = cell
        val rng = Rng(151)
        val budget = (cap * .88f).toInt()
        var k = 0
        val tries = (cap * 1.7f).toInt()
        for (t in 0 until tries) {
            if (k >= budget) break
            val x = rng.next() * w; val y = rng.next() * h
            val i = (x / cell).toInt().coerceAtMost(cw - 1); val j = (y / cell).toInt().coerceAtMost(ch - 1)
            val raw = light[j * cw + i]
            val sk = skyline[i]
            var dens: Float; var c: Int
            if (raw < 0f) {
                dens = (if (night) .006f else .004f) + (if (night) .03f else .05f) * exp(-(sk - y) / (80f * dp))
                c = if (night && rng.next() < .35f) 0 else 2
            } else {
                val b = min(1f, raw / top)
                dens = .02f + .98f * Math.pow(b.toDouble(), 1.1).toFloat()
                c = if (b > .3f) 0 else 2
                // The backlit edge catches a rim of light
                if (b < .12f && y - sk < 3.2f * dp) { dens = .97f; c = 1 }
            }
            if (rng.next() >= dens) continue
            hx[k] = x; hy[k] = y; col[k] = c.toByte(); hash[k] = rng.next()
            k++
        }
        return k
    }

    /** Deserto: where the sky meets the sand, column by column, to set the sun or moon above it. */
    private fun skyAtX(x: Float): Float {
        val s = deserSky
        if (s.isEmpty()) return h * .5f
        return s[(x / deserCell).toInt().coerceIn(0, s.size - 1)]
    }

    // ---- Pioggia: the weather outside, drawn as a long exposure ----
    /**
     * Pioggia (24 Nov 2026). The rain falling now where the phone is, as a long-exposure photograph
     * draws it: still streaks, slanted by the real wind and as long as the rain is hard. Drizzle is
     * fine and sparse, a downpour dense; snow lies as soft clumps blown further by the same wind; a
     * dry sky is a haze as heavy as its cloud, thickest at the top. Where drops land, rings on the
     * floor. In a thunderstorm, one fork in the accent. Laid out by gravity, like Marea, so the rain
     * falls down the view whichever way the phone is held; east is to the right, as on a map.
     */
    private fun genPioggia(): Int {
        val wx = weather ?: Wx.OVERCAST
        val rng = Rng(113)
        // As much weather per square dp on every screen as on the cover, which it was judged on
        val area = (w / dp) * (h / dp) / 395_000f
        var k = 0
        fun put(x: Float, y: Float, c: Int): Boolean {
            if (k >= cap) return false
            if (x < 0f || y < 0f || x >= w || y >= h) return true
            hx[k] = x; hy[k] = y; col[k] = c.toByte(); hash[k] = rng.next(); k++
            return true
        }
        // Cloud: a haze as heavy as the cover, heaviest at the top, as overcast is
        val haze = (cap * .10f * wx.cloud / 100f).toInt()
        repeat(haze) { put(rng.next() * w, rng.next().pow(1.8f) * h, 2) }
        // Fog lies in level bands across the view
        if (wx.code == 45 || wx.code == 48) {
            val bands = (cap * .12f).toInt()
            repeat(bands) {
                val y = rng.next() * h
                val b = .5f + .5f * sin(y / (38f * dp))
                if (rng.next() < b * b) put(rng.next() * w, y, if (rng.next() < .3f) 0 else 2)
            }
        }
        val snowy = wx.snow > 0f || wx.code in 71..77 || wx.code == 85 || wx.code == 86
        val wet = wx.precip + wx.snow * 7f
        val inten = if (wet > 0f) (kotlin.math.ln(1f + wet) / kotlin.math.ln(11f)).coerceIn(.08f, 1f) else 0f
        if (inten > 0f) {
            // Rain falls about 6 m/s and snow about 1, so the same wind lays snow far flatter
            val east = -sin(Math.toRadians(wx.dir.toDouble())).toFloat()
            val drift = east * wx.wind / (if (snowy) 1f else 6f)
            val ang = kotlin.math.atan2(1f, drift)
            val ux = cos(ang); val uy = sin(ang)
            val streaks = ((260f + 2600f * inten) * area).toInt()
            for (s in 0 until streaks) {
                val x0 = rng.next() * w * 1.5f - w * .25f; val y0 = rng.next() * h
                if (snowy) {
                    // A flake: a soft clump, not a streak
                    val m = 4 + (rng.next() * 4).toInt()
                    repeat(m) { if (!put(x0 + (rng.next() - .5f) * 3f * dp, y0 + (rng.next() - .5f) * 3f * dp, 0)) return k }
                    continue
                }
                val len = (10f + 26f * inten * (.5f + rng.next())) * dp
                val steps = (len / (1.1f * dp)).toInt()
                val c = if (rng.next() < .75f) 0 else 2
                for (j in 0 until steps) {
                    if (rng.next() > .8f) continue
                    val t = j / steps.toFloat()
                    if (!put(x0 + ux * len * t, y0 + uy * len * t, c)) return k
                }
            }
            // Snow lies: a soft drift along the floor, deeper the harder it falls
            if (snowy) {
                val deep = (8f + 30f * inten) * dp
                val n0 = (cap * .05f * inten).toInt()
                repeat(n0) {
                    val x = rng.next() * w
                    val top = h - deep * (.7f + .3f * sin(x / (57f * dp)) + .15f * sin(x / (13f * dp)))
                    val y = top + rng.next().pow(.6f) * (h - top)
                    if (!put(x, y, if (rng.next() < .8f) 0 else 2)) return k
                }
            }
            // The floor: rings where the drops land, more the harder it falls
            if (!snowy) {
                val rings = ((30f + 160f * inten) * area).toInt()
                val floor = h * .93f
                repeat(rings) {
                    val x0 = rng.next() * w; val y0 = floor + rng.next() * (h - floor)
                    val rad = (3f + 9f * rng.next()) * dp
                    val m = (rad * 2.2f / dp).toInt().coerceAtLeast(6)
                    for (j in 0 until m) {
                        val a = 6.2832f * j / m
                        if (!put(x0 + cos(a) * rad, y0 + sin(a) * rad * .32f, if (rng.next() < .5f) 2 else 0)) return k
                    }
                }
            }
        }
        // A thunderstorm: one fork of lightning in the accent
        if (wx.code >= 95) {
            var x = w * (.3f + .4f * rng.next()); var y = 0f
            while (y < h * .7f) {
                val nx = x + (rng.next() - .5f) * 60f * dp; val ny = y + (20f + 30f * rng.next()) * dp
                for (j in 0 until 40) { val t = j / 40f; if (!put(x + (nx - x) * t, y + (ny - y) * t, 1)) return k }
                x = nx; y = ny
            }
        }
        return k
    }

    // ---- Stagione: the year's daylight, one ray a day, fanned from the hinge ----
    /**
     * Stagione (24 Nov 2026). One ray for each day of the year, fanned from the hinge, as long as
     * that day's daylight here: the fan swells to midsummer and pinches at the winter solstice. On
     * the cover the year runs top to bottom round the hinge on the left edge; on the main screen it
     * goes once round the crease, January at the top. Days gone are dense, days to come faint, today's
     * ray is the accent; a hair of bare sand marks each month. Each ray reaches its share of the way
     * to the edge of the glass, so the year fills the screen whatever its shape.
     */
    private fun genStagione(): Int {
        val se = season ?: Season.EVEN
        val days = se.daylight
        val nd = days.size.coerceAtLeast(1)
        var most = 0f
        for (d in days) if (d > most) most = d
        if (most <= 0f) most = 1f
        val share = FloatArray(nd) { days[it] / most }
        val span = if (cover) 3.1416f else 6.2832f
        val a0 = -1.5708f
        val inner = 14f * dp
        // Candidates are drawn only inside the fan: a day chosen by the area of its ray, then a
        // point spread evenly over that ray's wedge (a sixth the work of trying the whole glass)
        // Each ray's length, taken along its middle: within one day the edge of the glass moves by
        // less than a grain, so a grain's density is settled before any trigonometry is done
        val len = FloatArray(nd) { d ->
            val ac = a0 + span * (d + .5f) / nd
            inner + (reach(gcx, gcy, cos(ac), sin(ac)) * .93f - inner) * share[d]
        }
        val cum = FloatArray(nd)
        var acc = 0f
        for (d in 0 until nd) { val far = len[d] + 2f * dp; acc += far * far; cum[d] = acc }
        val rng = Rng(101)
        var k = 0; var guard = 0
        while (k < cap && guard < cap * 40) {
            guard++
            val pick = rng.next() * acc
            var lo = 0; var hi = nd - 1
            while (lo < hi) { val m = (lo + hi) ushr 1; if (cum[m] < pick) lo = m + 1 else hi = m }
            val d = lo
            val fr = rng.next()
            val rr = (len[d] + 2f * dp) * sqrt(rng.next())
            if (rr < inner) continue
            if (d != 0 && d in se.months && fr < .9f) continue
            val t = rr / len[d]
            val tip = rr > len[d] - 5f * dp
            val across = 1f - abs(fr - .5f) * 2f
            // Each day a fine ray with dark sand between, thinning towards the hinge where they crowd
            val a2 = across * across; val a4 = a2 * a2
            val spine = a4 * a4
            val fade = .25f + .75f * t * sqrt(t)
            val dens: Float; val c: Int
            when {
                d == se.today -> { dens = .95f * (.4f + .6f * across * across); c = 1 }
                d < se.today -> { dens = .85f * spine * fade + (if (tip) .55f else 0f) * (.3f + .7f * across); c = if (tip || rng.next() < .85f) 0 else 2 }
                else -> { dens = .30f * spine * fade + (if (tip) .30f else 0f) * (.3f + .7f * across); c = 2 }
            }
            if (rng.next() > dens) continue
            val a = a0 + span * (d + fr) / nd
            val x = gcx + cos(a) * rr; val y = gcy + sin(a) * rr
            if (x < 0f || y < 0f || x >= gw || y >= gh) continue
            hx[k] = x; hy[k] = y; col[k] = c.toByte(); hash[k] = rng.next()
            k++
        }
        return k
    }

    /** How far from (x, y) along (ux, uy) the glass's edge lies. */
    private fun reach(x: Float, y: Float, ux: Float, uy: Float): Float {
        var t = 1e9f
        if (ux > 1e-5f) t = min(t, (gw - x) / ux)
        if (ux < -1e-5f) t = min(t, -x / ux)
        if (uy > 1e-5f) t = min(t, (gh - y) / uy)
        if (uy < -1e-5f) t = min(t, -y / uy)
        return t
    }

    // ---- Orma: today's steps, a trail of footprints pressed into the sand ----
    /**
     * The walk's line on the upright glass: in from the bottom edge, then sweeping side to side up
     * the glass as a walk along a beach does, each day its own way. Points every dp.
     */
    private fun ormaLine(): Pair<FloatArray, FloatArray> {
        val rng = Rng((walkDay % 2_000_000L).toInt() + 17)
        val top = gh * .07f; val bot = gh * .95f
        val rows = max(2, ((bot - top) / (118f * dp)).toInt())
        val sp = (bot - top) / rows
        var side = if (rng.next() < .5f) 0 else 1
        val px = ArrayList<Float>(); val py = ArrayList<Float>()
        px += gw * (.34f + .32f * rng.next()); py += gh + 30f * dp
        var y = bot
        for (i in 0..rows) {
            // Each sweep reaches its own distance across, so the turns never line up
            val reachOut = .06f + .22f * rng.next()
            px += gw * (if (side == 0) reachOut else 1f - reachOut); py += y + (rng.next() - .5f) * sp * .4f
            if (i < rows) { px += gw * (.36f + .28f * rng.next()); py += y - sp * (.35f + .3f * rng.next()) }
            side = side xor 1; y -= sp
        }
        // Catmull-Rom through the points, sampled about every dp
        val ox = ArrayList<Float>(); val oy = ArrayList<Float>()
        val n = px.size
        for (i in 0 until n - 1) {
            val i0 = max(0, i - 1); val i3 = min(n - 1, i + 2)
            val seg = hypot(px[i + 1] - px[i], py[i + 1] - py[i])
            val k = max(2, (seg / dp).toInt())
            for (q in 0 until k) {
                val t = q / k.toFloat(); val t2 = t * t; val t3 = t2 * t
                fun cr(a: Float, b: Float, c: Float, d: Float) = .5f * (2f * b + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t2 + (-a + 3f * b - 3f * c + d) * t3)
                ox += cr(px[i0], px[i], px[i + 1], px[i3]); oy += cr(py[i0], py[i], py[i + 1], py[i3])
            }
        }
        ox += px[n - 1]; oy += py[n - 1]
        return ox.toFloatArray() to oy.toFloatArray()
    }

    /**
     * The hollow of one footprint in its own frame, in units of about a dp: [u] forward, [v] to the
     * right, [out] +1 for a right foot (its outer edge to the right). A heel, a broad ball, the outer
     * edge joining them with the arch lifted on the inside, and five toes, the big toe inside.
     */
    private fun footAt(u: Float, v: Float, out: Float): Float {
        fun ell(cu: Float, cv: Float, ru: Float, rv: Float): Float {
            val a = (u - cu) / ru; val b = (v - cv) / rv
            return ((1f - (a * a + b * b)) * 2.2f).coerceIn(0f, 1f)
        }
        var h = max(ell(-5f, .15f * out, 2.5f, 2.2f), ell(1.2f, -.35f * out, 2.9f, 3f))
        h = max(h, ell(-1.9f, 1.55f * out, 3.6f, .95f))
        h = max(h, .9f * ell(5.15f, -1.75f * out, 1.2f, 1.2f))
        h = max(h, .9f * ell(5.55f, -.25f * out, .78f, .78f))
        h = max(h, .9f * ell(5.3f, .85f * out, .7f, .7f))
        h = max(h, .9f * ell(4.85f, 1.8f * out, .64f, .64f))
        h = max(h, .9f * ell(4.2f, 2.6f * out, .58f, .58f))
        return h
    }

    /**
     * Orma (27 Nov 2026). Today's steps pressed into smooth, wind-rippled sand as a trail of
     * footprints, one print for every [perPrint] steps, laid in the order they were walked along a
     * line that enters at the bottom of the glass and sweeps up it. The stride follows the pace
     * (a stroll short, brisk walking long, a run longest and dug in at the toes); after a quarter of
     * an hour still, the feet stand side by side before the trail goes on. Prints older than an hour
     * are softened by the wind, the last hour's rims wear the accent, and the low sun lights each
     * hollow's far wall. The trail stops where you are now; ahead of it the sand is untouched.
     */
    private fun genOrma(): Int {
        val (lx, ly) = ormaLine()
        val m = lx.size
        val len = FloatArray(m)
        for (i in 1 until m) len[i] = len[i - 1] + hypot(lx[i] - lx[i - 1], ly[i] - ly[i - 1])
        // Each print: arc length, foot (+1 right, -1 left), age in hours, a run, and whether it stands beside the last
        val ps = ArrayList<FloatArray>()
        fun lay(per: Float): Float {
            ps.clear()
            var acc = 0f; var s = 20f * dp; var last = -999; var foot = 1f
            for (t in walk.indices) {
                val spm = walk[t]
                if (spm <= 0f) continue
                acc += spm
                while (acc >= per) {
                    acc -= per
                    val stride = (if (spm < 60f) 26f else if (spm < 100f) 33f else if (spm < 130f) 37f else 47f) * dp
                    if (last >= 0 && t - last > 15 && ps.isNotEmpty()) {
                        val p = ps[ps.size - 1]
                        ps += floatArrayOf(p[0], -p[1], p[2], 0f, 1f)
                        s += 22f * dp
                    }
                    s += stride
                    ps += floatArrayOf(s, foot, (walkNow - t) / 60f, if (spm >= 130f) 1f else 0f, 0f)
                    foot = -foot; last = t
                }
            }
            return s
        }
        var per = 100f
        if (walk.isNotEmpty()) while (lay(per) > len[m - 1] - 10f * dp && per < 20_000f) per += 50f
        perPrint = per.toInt(); prints = ps.size

        val cell = dp
        val cw = (gw / cell).toInt() + 2; val ch = (gh / cell).toInt() + 2
        val hol = FloatArray(cw * ch); val fresh = FloatArray(cw * ch); val push = FloatArray(cw * ch)
        val big = 2f
        var seg = 0
        for (p in ps) {
            while (seg < m - 2 && len[seg + 1] < p[0]) seg++
            val x = lx[seg]; val y = ly[seg]
            val a = kotlin.math.atan2(ly[seg + 1] - y, lx[seg + 1] - x)
            val foot = p[1]; val age = p[2]; val run = p[3] > .5f
            val depth = .55f + .45f * exp(-age.coerceAtLeast(0f) / 10f)
            val recent = age < 1f
            val ca = cos(a); val sa = sin(a)
            // The foot sits to its own side of the line, turned out a little, as feet are
            val fx = x - sa * 6.5f * dp * foot; val fy = y + ca * 6.5f * dp * foot
            val turn = .09f * foot; val ct = cos(turn); val st = sin(turn)
            val reachR = 9f * big * dp
            val i0 = ((fx - reachR) / cell).toInt().coerceAtLeast(0); val i1 = ((fx + reachR) / cell).toInt().coerceAtMost(cw - 1)
            val j0 = ((fy - reachR) / cell).toInt().coerceAtLeast(0); val j1 = ((fy + reachR) / cell).toInt().coerceAtMost(ch - 1)
            for (j in j0..j1) for (i in i0..i1) {
                val dx = (i + .5f) * cell - fx; val dy = (j + .5f) * cell - fy
                val u0 = (dx * ca + dy * sa) / (dp * big); val v0 = (-dx * sa + dy * ca) / (dp * big)
                val u = u0 * ct + v0 * st; val v = -u0 * st + v0 * ct
                var hh = footAt(u, v, foot) * depth
                // A run lands on the forefoot: the heel barely down, sand thrown up behind the ball
                if (run) hh *= ((u + 6f) / 5f).coerceIn(.25f, 1f)
                if (hh <= 0f) continue
                val q = j * cw + i
                if (hh > hol[q]) hol[q] = hh
                if (recent && hh > fresh[q]) fresh[q] = hh
                val pu = (u + .6f) / 1.6f; val pv = v / 3.6f
                push[q] += exp(-(pu * pu + pv * pv)) * (if (run) .55f else .25f) * depth
            }
        }
        // The rim: a little wider than the hollow, less the hollow itself
        val broad = blur(hol.copyOf(), cw, ch, 1)
        val broadF = blur(fresh.copyOf(), cw, ch, 1)
        // The low light, from the side the sun or moon is on: the hollow's far wall catches it
        val soft = blur(hol.copyOf(), cw, ch, 1)
        val morning = (skyAt?.minutes ?: 600) < 720
        val lxd = if (morning) .8f else -.8f; val lyd = .6f
        val rng = Rng(149)
        var k = 0; var guard = 0
        while (k < cap && guard < cap * 40) {
            guard++
            val x = rng.next() * gw; val y = rng.next() * gh
            val ci = (x / cell).toInt().coerceIn(1, cw - 2); val cj = (y / cell).toInt().coerceIn(1, ch - 2)
            val q = cj * cw + ci
            val ho = hol[q]
            val rim = (broad[q] * 2.4f - ho * 1.5f + push[q] * .4f).coerceIn(0f, 1f)
            val rimF = (broadF[q] * 2.4f - fresh[q] * 1.5f).coerceIn(0f, 1f)
            val gxh = (soft[q + 1] - soft[q - 1]) * .5f; val gyh = (soft[q + cw] - soft[q - cw]) * .5f
            val lit = ((gxh * lxd + gyh * lyd) * 4f).coerceIn(-1f, 1f)
            // The sand itself: fine ripples smoothed by the wind, quiet enough for a print to read
            val ph = ((y + 4f * dp * sin(x / (70f * dp)) + 2f * dp * sin(x / (23f * dp) + 1f)) / (11f * dp)) % 1f
            val ripple = if (ph < .7f) (ph / .7f).pow(1.4f) else (1f - (ph - .7f) / .3f).pow(2.6f)
            val open = 1f - ho
            val dens = (.15f + .11f * ripple) * open * open * open + .015f * ho +
                1.15f * rim * open * (1f - .6f * (-lit).coerceIn(0f, 1f)) + .75f * lit.coerceIn(0f, 1f) * ho
            if (rng.next() > dens) continue
            val c = if (rimF > .2f && lit > -.2f) 1 else if (rim > .2f || lit > .3f || ripple > .75f) 0 else 2
            hx[k] = x; hy[k] = y; col[k] = c.toByte(); hash[k] = rng.next()
            k++
        }
        return k
    }

    // ---- Sentiero: raked gravel, worn by the day's touches ----
    /**
     * Sentiero (24 Nov 2026). Raked gravel, fine level lines across the glass, worn by your hand:
     * every touch on the home screen today presses a hollow with the gravel banked round it, and
     * every swipe drags a path. Where you go often the hollow is deep and bare; the last hour's
     * banks wear the accent. At midnight the garden is raked again. The trail is today's, on this
     * screen, kept on the phone (see [Trail]).
     */
    private fun genSentiero(): Int {
        val cell = 4f * dp
        val cw = (gw / cell).toInt() + 2; val ch = (gh / cell).toInt() + 2
        val wear = FloatArray(cw * ch); val fresh = FloatArray(cw * ch)
        fun splat(x: Float, y: Float, sig: Float, amt: Float, recent: Boolean) {
            val s = sig / cell; val cx0 = x / cell; val cy0 = y / cell
            val rad = (s * 3f).toInt() + 1
            val i0 = (cx0.toInt() - rad).coerceAtLeast(0); val i1 = (cx0.toInt() + rad).coerceAtMost(cw - 1)
            val j0 = (cy0.toInt() - rad).coerceAtLeast(0); val j1 = (cy0.toInt() + rad).coerceAtMost(ch - 1)
            val inv = 1f / (2f * s * s)
            for (j in j0..j1) for (i in i0..i1) {
                val ddx = i - cx0; val ddy = j - cy0
                val v = amt * kotlin.math.exp(-(ddx * ddx + ddy * ddy) * inv)
                wear[j * cw + i] += v
                if (recent) fresh[j * cw + i] += v
            }
        }
        // Each record is fx, fy, kind (0 a tap, 1 a stroke's start, 2 its next point) and age in hours
        val m = trail.size / 4
        var px = 0f; var py = 0f
        for (q in 0 until m) {
            val x = trail[4 * q] * gw; val y = trail[4 * q + 1] * gh
            val kind = trail[4 * q + 2].toInt(); val recent = trail[4 * q + 3] < 1f
            when (kind) {
                0 -> splat(x, y, 9f * dp, .9f, recent)
                1 -> splat(x, y, 5f * dp, .45f, recent)
                else -> {
                    // A swipe is a path, not a row of prints: laid every 3 dp from the last point
                    val seg = hypot(x - px, y - py)
                    val steps = (seg / (3f * dp)).toInt().coerceIn(1, 400)
                    for (st in 1..steps) { val t = st / steps.toFloat(); splat(px + (x - px) * t, py + (y - py) * t, 5f * dp, .45f * 3f * dp / (8f * dp), recent) }
                }
            }
            px = x; py = y
        }
        val hollow = FloatArray(wear.size) { 1f - kotlin.math.exp(-wear[it] * 2.2f) }
        // The bank: a broader spread of the wear, less the hollow itself
        val broad = blur(hollow.copyOf(), cw, ch, 3); val broadF = blur(FloatArray(fresh.size) { 1f - kotlin.math.exp(-fresh[it] * 2.2f) }, cw, ch, 3)
        val rng = Rng(131)
        val rake = 7f * dp
        var k = 0; var guard = 0
        while (k < cap && guard < cap * 40) {
            guard++
            val x = rng.next() * gw; val y = rng.next() * gh
            val q = (y / cell).toInt().coerceIn(0, ch - 1) * cw + (x / cell).toInt().coerceIn(0, cw - 1)
            val ho = hollow[q]
            val rim = (broad[q] * 2.2f - ho * 1.2f).coerceIn(0f, 1f)
            // The lines, with a gentle swell so they read as raked by hand
            val ph = ((y + 3f * dp * sin(x / (90f * dp))) / rake) % 1f
            val line = kotlin.math.exp(-((ph - .5f) / .16f).let { it * it })
            val dens = (.03f + .62f * line * line) * (1f - ho) * (1f - ho) + 1.1f * rim * (1f - ho)
            if (rng.next() > dens) continue
            val c = if (rim > .25f && broadF[q] > broad[q] * .5f) 1 else if (rim > .25f || line > .6f) 0 else 2
            hx[k] = x; hy[k] = y; col[k] = c.toByte(); hash[k] = rng.next()
            k++
        }
        return k
    }

    /** Three passes of a box blur of [r] cells each way: near enough a Gaussian. In place; returns [a]. */
    private fun blur(a: FloatArray, cw: Int, ch: Int, r: Int): FloatArray {
        val t = FloatArray(a.size)
        val span = 2 * r + 1f
        repeat(3) {
            for (j in 0 until ch) {
                var acc = 0f
                for (i in -r..r) acc += a[j * cw + i.coerceIn(0, cw - 1)]
                for (i in 0 until cw) {
                    t[j * cw + i] = acc / span
                    acc += a[j * cw + (i + r + 1).coerceAtMost(cw - 1)] - a[j * cw + (i - r).coerceAtLeast(0)]
                }
            }
            for (i in 0 until cw) {
                var acc = 0f
                for (j in -r..r) acc += t[j.coerceIn(0, ch - 1) * cw + i]
                for (j in 0 until ch) {
                    a[j * cw + i] = acc / span
                    acc += t[(j + r + 1).coerceAtMost(ch - 1) * cw + i] - t[(j - r).coerceAtLeast(0) * cw + i]
                }
            }
        }
        return a
    }

    /**
     * Sentiero: where on the upright glass, as fractions of its width and height, a touch at surface
     * x, y lands, so a touch is kept in the same place whichever way the phone is held.
     */
    fun glassFrac(x: Float, y: Float): FloatArray {
        val gx0: Float; val gy0: Float
        when { !turned -> { gx0 = x; gy0 = y }; quarter == 1 -> { gx0 = gw - y; gy0 = x }; else -> { gx0 = y; gy0 = gh - x } }
        return floatArrayOf((gx0 / gw).coerceIn(0f, 1f), (gy0 / gh).coerceIn(0f, 1f))
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
        if (style == Style.DESERTO) {
            // Low in the sky on the side the light comes from, always above the sand
            val rad = (if (cover) .07f else .055f) * min(w, h)
            val x = if (sky.minutes < 720) w * (if (cover) .13f else .17f) else w * (if (cover) .87f else .83f)
            val ground = skyAtX(x) - rad - 10f * dp
            val high = h * .10f + rad
            return floatArrayOf(x, ground - (ground - high).coerceAtLeast(0f) * sky.alt.coerceIn(0f, 1f) * .6f, rad)
        }
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
        for (i in 0 until n) {
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
