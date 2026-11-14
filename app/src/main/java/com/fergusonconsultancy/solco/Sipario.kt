package com.fergusonconsultancy.solco

import com.cerniera.kit.Kit
import com.cerniera.kit.withAlpha
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Sipario: five scenes, each one world drawn at the Fold8 Ultra's own scale.
 *
 * The cover (1,080 × 2,520) and the main screen (2,256 × 2,504) share 422 ppi, and the cover is
 * almost exactly the main screen's right half. The hinge is on the left and the door swings open to
 * the left, so the half you hold stays where it was and the door's inside is the half you never saw.
 * Unfolding adds information; it never zooms.
 *
 * Everything is in world pixels. Each scene is a pure function of the time since its arrival and of
 * the touches it has been given. Nothing moves at rest.
 */
object World {
    const val W = 2256f
    const val H = 2504f
    const val X0 = 1128f
    /** The cover screen within the main screen's world: its right half, 24 px in and 8 px up. */
    val COVER = RectF(1152f, -8f, 2232f, 2512f)

    private val MAIN = RectF(0f, 0f, W, H)

    /**
     * Scale and offset that place the cover's or the main screen's part of the world on a w × h
     * surface. An upright surface is filled, as it always was. A turned one (either screen on its
     * side, 12 Nov 2026) holds the whole part instead, centred, so nothing in the scene is cut
     * off; its edges fade into the ground (see [draw]).
     */
    fun transform(w: Int, h: Int, main: Boolean): FloatArray {
        val r = if (main) MAIN else COVER
        val fx = w / r.width(); val fy = h / r.height()
        val turned = w > h
        val s = if (turned) min(fx, fy) else max(fx, fy)
        return floatArrayOf(s, (w - r.width() * s) / 2f - r.left * s, (h - r.height() * s) / 2f - r.top * s)
    }

    /** Draws [scene] into a w × h canvas. True while something still moves. */
    fun draw(c: Canvas, w: Int, h: Int, scene: SipScene, e: Env): Boolean {
        val t = transform(w, h, e.main)
        c.save(); c.translate(t[1], t[2]); c.scale(t[0], t[0])
        val busy = scene.draw(c, e)
        // Held whole on a surface of another shape: the world's edges fade into its ground
        val r = if (e.main) MAIN else COVER
        val l = (-t[1] / t[0]); val tp = (-t[2] / t[0]); val rt = (w - t[1]) / t[0]; val bt = (h - t[2]) / t[0]
        if (l < r.left - 1f || rt > r.right + 1f || tp < r.top - 1f || bt > r.bottom + 1f) frame(c, r, RectF(l, tp, rt, bt), e)
        c.restore()
        return busy
    }
}

enum class Act(val title: String, val folded: String, val unfolded: String) {
    CATENARIA("Catenaria", "Weighted chains hang from a rod.", "The door is Gaudí’s mirror: the same curves stand up as a church."),
    STAGIONI("Stagioni", "The tree as it stands today.", "The canopy runs back through the year that grew it."),
    PONTE("Ponte", "The road stops at a raised bridge.", "The span has come down on the hinge, and a town lights up across the river."),
    FANALE("Fanale", "A red moon over a wet road, pointing at the hinge.", "Its twin lights bead by bead. They were tail lamps."),
    GOCCIA("Goccia", "A drop forms on a leaf tip.", "The hinge’s settle shakes it loose into the pond the door was hiding.");

    fun make(): SipScene = when (this) {
        CATENARIA -> Catenaria()
        STAGIONI -> Stagioni()
        PONTE -> Ponte()
        FANALE -> Fanale()
        GOCCIA -> Goccia()
    }
}

/** What a frame knows: which screen, seconds since arrival, the light, and Concentrato's factors. */
class Env {
    var main = false
    var t = 1e6f
    var now = 0L
    var day = 0f
    var prog = 0f
    var afterSet = false
    /** Arrival time stretch: 0.65 after a fast fold with Concentrato on, else 1. */
    var k = 1f
    /** Ornament: 0.25 after a fast fold with Concentrato on, else 1. */
    var orn = 1f
    var month = 0f

    /** The real sunrise and sunset for the phone's place (the Kit's Sky), in local clock hours. */
    fun refreshSky() {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f
        val d = Sky.day()
        fun hours(t: java.time.Instant) = t.atZone(java.time.ZoneId.systemDefault()).let { it.hour + it.minute / 60f + it.second / 3600f }
        val (r, s) = when {
            d.sunrise != null && d.sunset != null -> hours(d.sunrise!!) to hours(d.sunset!!)
            d.noonAltitude > 0 -> -1f to 25f   // midnight sun: day all day
            else -> 12f to 12f                 // polar night: no day at all
        }
        day = smooth(r - .5f, r + .5f, h) * (1f - smooth(s - .5f, s + .5f, h))
        prog = if (s > r) cl((h - r) / (s - r)) else 0f
        afterSet = h > s
        month = c.get(Calendar.MONTH) + (c.get(Calendar.DAY_OF_MONTH) - 1) / 30f
    }
}

interface SipScene {
    fun arrive(main: Boolean, now: Long) {}
    fun touch(x: Float, y: Float, main: Boolean, now: Long): Boolean
    fun draw(c: Canvas, e: Env): Boolean
}

// ---------------- Shared arithmetic and paint ----------------

private const val W = World.W
private const val H = World.H
private const val X0 = World.X0
private val TAU = (PI * 2).toFloat()
private val PIf = PI.toFloat()

private fun cl(v: Float, a: Float = 0f, b: Float = 1f) = min(b, max(a, v))
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun ease(t: Float): Float { val x = cl(t); return 1f - (1f - x).pow(3) }
private fun smooth(a: Float, b: Float, x: Float): Float { val t = cl((x - a) / (b - a)); return t * t * (3 - 2 * t) }
/** A soft spring: overshoots a touch and settles. Only for soft things. */
private fun spring(t: Float, z: Float = .45f, w: Float = 14f): Float =
    if (t <= 0f) 0f else 1f - exp(-z * w * t) * cos(w * sqrt(1f - z * z) * t)
private fun sway(t: Float, w: Float = 7f, tau: Float = .6f): Float = if (t < 0f) 0f else sin(w * t) * exp(-t / tau)
private fun mix(a: Int, b: Int, t: Float): Int {
    val u = cl(t)
    val r = lerp(((a shr 16) and 255).toFloat(), ((b shr 16) and 255).toFloat(), u).toInt()
    val g = lerp(((a shr 8) and 255).toFloat(), ((b shr 8) and 255).toFloat(), u).toInt()
    val bl = lerp((a and 255).toFloat(), (b and 255).toFloat(), u).toInt()
    return (r shl 16) or (g shl 8) or bl
}
private fun op(rgb: Int) = rgb or 0xFF000000.toInt()

// The family's fixed colours, from the Kit (RGB only: mix and al add their own alpha). The day ends
// of each mix are Avorio, which began here as Sipario's daylight.
private const val GROUND = Kit.Fixed.VISCONTI_GROUND and 0xFFFFFF
private const val IVORY = Kit.Fixed.IVORY and 0xFFFFFF
private const val CHAMPAGNE = Kit.Fixed.CHAMPAGNE and 0xFFFFFF
/** Visconti green (Fold Widgets' glass), exact. Goccia's leaf was an approximation of it (#173A2C) until 12 Nov 2026. */
private const val VISCONTI_GREEN = Kit.Fixed.VISCONTI_GREEN and 0xFFFFFF
private fun al(rgb: Int, a: Float) = withAlpha(rgb, a)
private fun deg(rad: Float) = rad * 180f / PIf

private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
private fun fill(color: Int): Paint { fillP.shader = null; fillP.color = color; return fillP }
private fun shaded(s: Shader): Paint { fillP.shader = s; fillP.color = 0xFFFFFFFF.toInt(); return fillP }
private fun stroke(w: Float, color: Int, cap: Paint.Cap = Paint.Cap.BUTT): Paint {
    strokeP.shader = null; strokeP.strokeWidth = w; strokeP.color = color; strokeP.strokeCap = cap; strokeP.strokeJoin = Paint.Join.ROUND
    return strokeP
}

private class Base(e: Env) {
    val d = e.day
    val ground = mix(GROUND, 0xE9E3D6, d)
    val ground2 = mix(0x16201A, 0xF4EFE5, d)
    val ink = mix(IVORY, 0x1D1B19, d)
    val champ = mix(CHAMPAGNE, 0x9A7A45, d)
    val lightC = mix(0xE2A857, 0xFFFFFF, d)
    val lightA = lerp(.09f, .32f, d)
}

/** One light from the top edge: the sun's progress east (left) to west (right), an ember at night. */
private fun lightX(e: Env) = if (e.day > .5f) lerp(260f, W - 260f, e.prog) else if (e.afterSet) W - 260f else 260f

private fun paintGround(c: Canvas, p: Base, e: Env) {
    c.drawRect(-300f, -300f, W + 300f, H + 300f, shaded(LinearGradient(0f, 0f, 0f, H, op(p.ground2), op(p.ground), Shader.TileMode.CLAMP)))
    val lx = lightX(e)
    c.drawRect(-300f, -300f, W + 300f, H + 300f, shaded(RadialGradient(lx, -120f, 1900f,
        intArrayOf(al(p.lightC, p.lightA), al(p.lightC, p.lightA * .35f), al(p.lightC, 0f)), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)))
}

private fun glowAt(c: Canvas, x: Float, y: Float, rgb: Int, lv: Float, r: Float = 110f) {
    if (lv <= 0f) return
    val v = cl(lv)
    c.drawCircle(x, y, r, shaded(RadialGradient(x, y, r, intArrayOf(al(rgb, .9f * v), al(rgb, .42f * v), al(rgb, 0f)), floatArrayOf(0f, .18f, 1f), Shader.TileMode.CLAMP)))
    c.drawCircle(x, y, 8f, fill(al(0xFFF4E0, v)))
}

/**
 * The margins round a world held whole on a surface of another shape: the ground, flat, with the
 * scene's own edges fading into it over [FADE] world pixels, so the world reads as set into the
 * screen rather than cut out of it.
 */
private const val FADE = 120f
private fun frame(c: Canvas, r: RectF, view: RectF, e: Env) {
    val p = Base(e)
    val g = op(mix(p.ground2, p.ground, .5f))
    val clear = g and 0xFFFFFF
    val f = fill(g)
    if (view.left < r.left) c.drawRect(view.left, view.top, r.left, view.bottom, f)
    if (view.right > r.right) c.drawRect(r.right, view.top, view.right, view.bottom, f)
    if (view.top < r.top) c.drawRect(view.left, view.top, view.right, r.top, f)
    if (view.bottom > r.bottom) c.drawRect(view.left, r.bottom, view.right, view.bottom, f)
    if (view.left < r.left - 1f) c.drawRect(r.left, r.top, r.left + FADE, r.bottom, shaded(LinearGradient(r.left, 0f, r.left + FADE, 0f, g, clear, Shader.TileMode.CLAMP)))
    if (view.right > r.right + 1f) c.drawRect(r.right - FADE, r.top, r.right, r.bottom, shaded(LinearGradient(r.right, 0f, r.right - FADE, 0f, g, clear, Shader.TileMode.CLAMP)))
    if (view.top < r.top - 1f) c.drawRect(r.left, r.top, r.right, r.top + FADE, shaded(LinearGradient(0f, r.top, 0f, r.top + FADE, g, clear, Shader.TileMode.CLAMP)))
    if (view.bottom > r.bottom + 1f) c.drawRect(r.left, r.bottom - FADE, r.right, r.bottom, shaded(LinearGradient(0f, r.bottom, 0f, r.bottom - FADE, g, clear, Shader.TileMode.CLAMP)))
}

/** The same seeded generator as the browser preview (mulberry32), so the tree grows the same way. */
private class Mulberry(private var seed: Int) {
    fun next(): Float {
        seed += 0x6D2B79F5
        var t = (seed xor (seed ushr 15)) * (1 or seed)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return (((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0).toFloat()
    }
}

// ---------------- 1. Catenaria ----------------

private class Catenaria : SipScene {
    private val bar = 230f
    private class Bead(val x: Float, val y: Float, val s: Float) { var ang = 0f }
    private class Pluck(val t: Long, val a: Float)
    private class Chain(val beads: List<Bead>, val xm: Float, val low: Float, val weight: Boolean) { val plucks = ArrayList<Pluck>() }

    private val chains: List<Chain>
    private val rank: IntArray

    init {
        val defs = arrayOf(
            floatArrayOf(1190f, 2196f, 1560f, 1f), floatArrayOf(1300f, 2090f, 1180f, 1f), floatArrayOf(1420f, 1972f, 820f, 1f),
            floatArrayOf(1540f, 1852f, 470f, 0f), floatArrayOf(1186f, 1500f, 560f, 0f), floatArrayOf(1888f, 2200f, 560f, 0f))
        chains = defs.map { df ->
            val a0 = df[0]; val b0 = df[1]; val d = df[2]
            val h = (b0 - a0) / 2f; val xm = (a0 + b0) / 2f
            val cc = solveC(h.toDouble(), d.toDouble())
            fun ys(x: Float): Float = (bar + d - cc * (cosh((x - xm) / cc) - 1.0)).toFloat()
            val sx = FloatArray(2401); val sy = FloatArray(2401); val sl = FloatArray(2401)
            var len = 0f
            for (i in 0..2400) {
                val x = a0 + (b0 - a0) * i / 2400f; val y = ys(x)
                if (i > 0) len += hypot(x - sx[i - 1], y - sy[i - 1])
                sx[i] = x; sy[i] = y; sl[i] = len
            }
            val n = max(8, round(len / 30f).toInt())
            val beads = ArrayList<Bead>()
            var k = 0
            for (j in 0..n) {
                val t = len * j / n
                while (k < 2400 && sl[k + 1] < t) k++
                beads.add(Bead(sx[k], sy[k], j / n.toFloat()))
            }
            for (j in beads.indices) {
                val a = beads[max(0, j - 1)]; val b = beads[min(beads.size - 1, j + 1)]
                beads[j].ang = atan2(b.y - a.y, b.x - a.x)
            }
            Chain(beads, xm, bar + d, df[3] > 0f)
        }
        val order = chains.indices.sortedBy { abs(chains[it].xm - X0) }
        rank = IntArray(chains.size)
        order.forEachIndexed { r, ci -> rank[ci] = r }
    }

    private fun solveC(h: Double, d: Double): Double {
        var lo = 1.0; var hi = 1e7
        repeat(90) {
            val m = (lo + hi) / 2; val f = m * (kotlin.math.cosh(h / m) - 1)
            if (!f.isFinite() || f > d) lo = m else hi = m
        }
        return (lo + hi) / 2
    }

    private fun amp(i: Int, e: Env): Float {
        val c = chains[i]; val t = e.t / e.k
        var a = (if (e.main) 7f else 16f) * sway(t - i * .07f, 6.2f, .55f)
        for (p in c.plucks) a += p.a * sway((e.now - p.t) / 1000f, 5.2f, .9f)
        return a
    }

    override fun touch(x: Float, y: Float, main: Boolean, now: Long): Boolean {
        var tx = x; var ty = y
        if (x < X0) { tx = W - x; ty = H - y }
        var best = -1; var bd = 200f
        chains.forEachIndexed { i, c -> for (b in c.beads) { val d = hypot(b.x - tx, b.y - ty); if (d < bd) { bd = d; best = i } } }
        if (best < 0) return false
        val c = chains[best]
        c.plucks.removeAll { now - it.t > 4500 }
        c.plucks.add(Pluck(now, (if (tx < c.xm) -1f else 1f) * 30f))
        return true
    }

    override fun draw(c: Canvas, e: Env): Boolean {
        val p = Base(e); val d = e.day; val t = e.t / e.k
        val bead = mix(CHAMPAGNE, 0x9A7A45, d); val beadD = mix(0x6E5A3A, 0x5A4526, d); val stone = mix(0xF1EADB, 0x2B2D2A, d); val glowC = CHAMPAGNE
        paintGround(c, p, e)
        var busy = e.main && t < 4.2f || !e.main && t < 3f
        val amps = FloatArray(chains.size) { amp(it, e) }
        for (ch in chains) for (pl in ch.plucks) if (e.now - pl.t < 4500) busy = true

        c.drawLine(60f, H - bar, X0 - 30f, H - bar, stroke(3f, al(p.champ, .38f)))
        c.drawLine(X0 + 40f, bar, W - 40f, bar, stroke(12f, op(bead), Paint.Cap.ROUND))
        c.drawLine(X0 + 40f, bar - 5f, W - 40f, bar - 5f, stroke(2f, al(p.ink, .18f)))

        // The arches, on the mirror half: built from their feet, nearest the crease first. Stone never bounces.
        chains.forEachIndexed { i, ch ->
            val start = rank[i] * .2f
            val glow = if (ch.plucks.isNotEmpty()) cl(abs(amps[i]) / 16f) else 0f
            for (b in ch.beads) {
                val q = 1f - abs(2f * b.s - 1f); val tj = start + q * .6f
                val pr = if (e.main) ease((t - tj) / .38f) else 1f
                if (pr <= 0f) continue
                val x = W - b.x; val y = H - b.y; val hg = cl((y - (H - 1800f)) / 1800f)
                c.save(); c.translate(x, y - (1f - pr) * 60f); c.rotate(deg(b.ang))
                c.drawRoundRect(-12f, -8f, 12f, 8f, 4f, 4f, fill(al(mix(mix(stone, p.ground, .25f * hg), glowC, glow * .65f * (1f - q * .3f)), pr)))
                c.restore()
            }
            if (ch.weight) {
                val q = if (e.main) ease((t - (start + .66f)) / .4f) else 1f
                if (q > 0f) {
                    val x = W - ch.xm; val yb = H - ch.low
                    c.drawLine(x, yb - 10f, x, yb - 80f, stroke(3f, al(stone, q)))
                    val path = Path().apply {
                        moveTo(x - 22f, yb - 80f); quadTo(x - 14f, yb - 150f, x, yb - 190f); quadTo(x + 14f, yb - 150f, x + 22f, yb - 80f); close()
                    }
                    c.drawPath(path, fill(al(stone, q)))
                    c.drawCircle(x, yb - 198f, 9f, fill(al(bead, q)))
                }
            }
        }
        // The one climax: the keystone of the tallest arch lights
        if (e.main) {
            val ch = chains[0]; val tk = rank[0] * .2f + .6f + .38f
            val g = e.orn * (if (t > tk) exp(-(t - tk) / .55f) else 0f)
            if (g > .01f) {
                val k = ch.beads[ch.beads.size / 2]; val x = W - k.x; val y = H - k.y
                c.drawCircle(x, y, 300f, shaded(RadialGradient(x, y, 300f, al(glowC, .55f * g), al(glowC, 0f), Shader.TileMode.CLAMP)))
            }
        }
        // The chains, on the cover half: soft, so they sway and settle
        chains.forEachIndexed { i, ch ->
            val a = amps[i]
            val path = Path()
            ch.beads.forEachIndexed { j, b -> val x = b.x + a * sin(PIf * b.s); if (j == 0) path.moveTo(x, b.y) else path.lineTo(x, b.y) }
            c.drawPath(path, stroke(2.5f, al(beadD, .9f)))
            for (b in ch.beads) {
                val x = b.x + a * sin(PIf * b.s); val f = cl(1f - (b.y - bar) / 2000f)
                c.drawCircle(x, b.y, 9f, fill(op(mix(beadD, bead, .35f + .65f * f))))
                c.drawCircle(x - 3f, b.y - 3f, 3f, fill(al(0xFFFFFF, .22f * f)))
            }
            if (ch.weight) {
                val x = ch.xm + a; val y = ch.low
                c.drawLine(x, y, x + a * .15f, y + 80f, stroke(2.5f, op(beadD)))
                c.drawOval(x + a * .2f - 24f, y + 82f, x + a * .2f + 24f, y + 154f, fill(op(mix(beadD, bead, .5f))))
            }
        }
        return busy
    }
}

// ---------------- 2. Stagioni ----------------

private class Stagioni : SipScene {
    private val gy = H - 190f
    private class Branch(val x: Float, val y: Float, val x2: Float, val y2: Float, val cx: Float, val cy: Float, val w: Float)
    private class Leaf(val x: Float, val y: Float, val r: Float, val ang: Float, val a: Float, val b: Float, val c: Float) { var fallen = false }
    private class Foliage(val kind: Int, val color: Int, val loose: Boolean)
    private class Fall(val x: Float, val y: Float, val r: Float, val ang: Float, val color: Int, val t: Long, val dur: Float, val dr: Float, val yg: Float)
    private class Landed(val x: Float, val y: Float, val r: Float, val ang: Float, val color: Int)
    private class Touch(val x: Float, val y: Float, val t: Long)

    private val branches: List<Branch>
    private val leaves: List<Leaf>
    private val falling = ArrayList<Fall>()
    private val ground = ArrayList<Landed>()
    private val touches = ArrayList<Touch>()
    private val rnd = Random(1974)

    init {
        val r = Mulberry(1974)
        val br = ArrayList<FloatArray>()   // x, y, x2, y2, cx, cy, w
        val lv = ArrayList<FloatArray>()   // x, y, r, ang, a, b, c
        fun grow(x: Float, y: Float, ang: Float, len: Float, w: Float, dp: Int) {
            val x2 = x + cos(ang) * len; val y2 = y + sin(ang) * len; val bend = (r.next() - .5f) * .5f
            br.add(floatArrayOf(x, y, x2, y2, (x + x2) / 2 + cos(ang + PIf / 2) * len * bend * .5f, (y + y2) / 2 + sin(ang + PIf / 2) * len * bend * .5f, w))
            if (dp >= 5) {
                val n = if (dp >= 8) 6 else 2
                repeat(n) { val a = r.next() * TAU; val rr = r.next() * 70f; lv.add(floatArrayOf(x2 + cos(a) * rr, y2 + sin(a) * rr, 9f + r.next() * 7f, r.next() * PIf, r.next(), r.next(), r.next())) }
            }
            if (dp >= 8) return
            val n = if (dp < 1) 2 else if (r.next() < .35f) 3 else 2
            for (k in 0 until n) {
                val sp = .38f + r.next() * .28f
                grow(x2, y2, ang + (k - (n - 1) / 2f) * sp + (r.next() - .5f) * .25f, len * (.72f + r.next() * .1f), w * .66f, dp + 1)
            }
        }
        grow(0f, 0f, -PIf / 2, 420f, 72f, 0)
        var mnx = 0f; var mxx = 0f; var mny = 0f
        for (b in br) { mnx = min(mnx, b[2]); mxx = max(mxx, b[2]); mny = min(mny, b[3]) }
        // Fit the canopy to the whole world, the trunk rising from the hinge
        fun mx(x: Float) = if (x < 0) X0 + x / (-mnx) * (X0 - 150f) else X0 + x / mxx * (W - X0 - 150f)
        fun my(y: Float) = gy + y / (-mny) * (gy - 330f)
        branches = br.map { Branch(mx(it[0]), my(it[1]), mx(it[2]), my(it[3]), mx(it[4]), my(it[5]), it[6] * 1.25f) }
        leaves = lv.map { Leaf(mx(it[0]), my(it[1]), it[2], it[3], it[4], it[5], it[6]) }
    }

    /** The season this leaf shows: the cover half is the last weeks to today, the far edge last winter. */
    private fun look(l: Leaf, e: Env): Foliage? {
        val u = l.x / W; val mn = e.month
        val m = if (u >= .5f) mn - (1f - u) * 2f * 1.4f else mn - 1.4f - (.5f - u) * 2f * 9.2f
        val mm = (((m + (l.a - .5f) * .9f) % 12f) + 12f) % 12f
        if (mm >= 11.3f || mm < 2.2f) return if (l.b < .45f) Foliage(2, mix(0xDCE6E8, 0x8E9DA3, e.day), false) else null
        if (mm < 4.6f) return if (l.b < .15f) Foliage(0, 0x86A85A, false) else Foliage(1, BLOOM[min(2, (l.c * 3).toInt())], false)
        if (mm < 8.4f) return if (l.b < .07f) Foliage(3, 0xB8141E, false) else Foliage(0, GREEN[min(2, (l.c * 3).toInt())], false)
        val a = (mm - 8.4f) / 2.9f
        if (l.b < a * .5f) return null
        if (l.c < .25f * (1f - a)) return Foliage(0, 0x6E8A45, false)
        return Foliage(0, AUTUMN[min(4, (l.b * 5).toInt())], true)
    }

    private fun drop(l: Leaf, color: Int, now: Long, delay: Long) {
        l.fallen = true
        falling.add(Fall(l.x, l.y, l.r, l.ang, color, now + delay, 1.4f + rnd.nextFloat() * .9f, (rnd.nextFloat() - .5f) * 160f, gy - 6f + rnd.nextFloat() * 30f))
    }

    private val probe = Env()

    override fun arrive(main: Boolean, now: Long) {
        if (main) return
        probe.refreshSky()
        // Folding shakes a few loose autumn leaves down
        var n = 0
        for (l in leaves.filter { !it.fallen && it.x > X0 + 40f }.shuffled(rnd)) {
            if (n >= 5 || ground.size > 90) break
            val f = look(l, probe) ?: continue
            if (f.loose) { drop(l, f.color, now, 120L + n * 140L); n++ }
        }
    }

    override fun touch(x: Float, y: Float, main: Boolean, now: Long): Boolean {
        touches.removeAll { now - it.t > 1600 }
        touches.add(Touch(x, y, now))
        probe.refreshSky()
        var n = 0
        for (l in leaves) {
            if (l.fallen || n >= 4) continue
            if (hypot(l.x - x, l.y - y) < 200f && rnd.nextFloat() < .3f) {
                val f = look(l, probe) ?: continue
                if (f.loose && ground.size < 90) { drop(l, f.color, now, n * 90L); n++ }
            }
        }
        return true
    }

    override fun draw(c: Canvas, e: Env): Boolean {
        val p = Base(e); val d = e.day; val t = e.t / e.k
        paintGround(c, p, e)
        var busy = e.main && t < 2.6f
        val bark = op(mix(0x7A6548, 0x4A3B2C, d))
        c.drawLine(60f, gy, W - 60f, gy, stroke(3f, al(p.champ, .3f)))
        val path = Path()
        for (b in branches) {
            path.reset(); path.moveTo(b.x, b.y); path.quadTo(b.cx, b.cy, b.x2, b.y2)
            c.drawPath(path, stroke(max(2f, b.w), bark, Paint.Cap.ROUND))
        }
        for (g in ground) { c.save(); c.rotate(deg(g.ang), g.x, g.y); c.drawOval(g.x - g.r, g.y - g.r * .45f, g.x + g.r, g.y + g.r * .45f, fill(op(g.color))); c.restore() }
        val live = touches.filter { e.now - it.t < 1600 }
        if (live.isNotEmpty()) busy = true
        val dim = lerp(.22f, 0f, d)
        for (l in leaves) {
            if (l.fallen) continue
            val f = look(l, e) ?: continue
            var sc = 1f
            if (e.main && l.x < X0) { val dl = .1f + (X0 - l.x) / X0 * 1.15f; sc = if (t < dl) 0f else spring(t - dl, .5f, 15f) }
            if (sc <= 0f) continue
            var x = l.x; var y = l.y
            for (tc in live) {
                val dx = x - tc.x; val dy = y - tc.y; val dd = max(1f, hypot(dx, dy))
                if (dd < 260f) { val k = 60f * exp(-dd / 200f) * sway((e.now - tc.t) / 1000f, 8f, .35f); x += dx / dd * k; y += dy / dd * k }
            }
            val pt = fill(op(mix(f.color, p.ground, dim)))
            when (f.kind) {
                0 -> { val rx = l.r * sc; val ry = l.r * .6f * sc; c.save(); c.rotate(deg(l.ang), x, y); c.drawOval(x - rx, y - ry, x + rx, y + ry, pt); c.restore() }
                2 -> c.drawCircle(x, y, l.r * .42f * sc, pt)
                3 -> c.drawCircle(x, y, l.r * .85f * sc, pt)
                else -> c.drawCircle(x, y, l.r * .8f * sc, pt)
            }
        }
        val iter = falling.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            val u = (e.now - f.t) / 1000f / f.dur
            if (u < 0f) {
                busy = true
                c.save(); c.rotate(deg(f.ang), f.x, f.y); c.drawOval(f.x - f.r, f.y - f.r * .6f, f.x + f.r, f.y + f.r * .6f, fill(op(f.color))); c.restore()
                continue
            }
            if (u >= 1f) { ground.add(Landed(f.x + f.dr, f.yg, f.r, (rnd.nextFloat() - .5f) * .6f, f.color)); iter.remove(); continue }
            busy = true
            val x = f.x + f.dr * u + sin(u * 9f) * 40f * (1f - u); val y = lerp(f.y, f.yg, u.pow(1.35f))
            val ry = f.r * .6f * abs(cos(u * 7f))
            c.save(); c.rotate(deg(f.ang + u * 5f), x, y); c.drawOval(x - f.r, y - ry, x + f.r, y + ry, fill(op(f.color))); c.restore()
        }
        return busy
    }

    companion object {
        val BLOOM = intArrayOf(0xEFB8C8, 0xF6D9E1, 0xE59AB2)
        val GREEN = intArrayOf(0x3F6E3A, 0x5C8A45, 0x86A85A)
        val AUTUMN = intArrayOf(0xD9BB86, 0xD98A2B, 0xC2561E, 0xB8141E, 0x6E0A12)
    }
}

// ---------------- 3. Ponte ----------------

private class Ponte : SipScene {
    private val dy = 1900f; private val wy = 1990f; private val span = 760f
    private class Block(val x: Float, val w: Float, val h: Float, val fl: Int, val tower: Boolean = false)
    private class Light(val x: Float, val s: Float, val color: Int, val w: Float)
    private class Ripple(val x: Float, val t: Long)
    private val fore = listOf(Block(20f, 170f, 900f, 12), Block(210f, 150f, 620f, 8, true))
    private val back = listOf(Block(380f, 250f, 820f, 11), Block(650f, 180f, 520f, 7), Block(850f, 240f, 680f, 9))
    private val rips = ArrayList<Ripple>()

    override fun touch(x: Float, y: Float, main: Boolean, now: Long): Boolean {
        if (y < wy - 40f) return false
        rips.removeAll { now - it.t > 3000 }; rips.add(Ripple(x, now)); return true
    }

    private fun ripAmp(x: Float, now: Long): Float {
        var a = 0f
        for (r in rips) { val age = (now - r.t) / 1000f; a += 26f * exp(-age / .9f) * exp(-abs(x - r.x) / 600f) }
        return a
    }

    override fun draw(c: Canvas, e: Env): Boolean {
        val p = Base(e); val d = e.day; val t = e.t / e.k; val main = e.main
        paintGround(c, p, e)
        var busy = if (main) t < 2.4f else t < 1.4f
        if (rips.any { e.now - it.t < 3000 }) busy = true
        val lampOn = lerp(1f, .28f, d); val amber = 0xE2A857
        val steel = mix(0xC9AE7E, 0x3B3A34, d); val dark = mix(0x141B17, 0xC9C1B0, d); val glassC = mix(0x0A0D0C, 0xA3AAA5, d)
        // Steel is rigid: one crisp deceleration, no bounce
        val theta = (if (main) 16f * (1f - ease(t / .52f)) else 90f - 18f * (1f - ease(t / .52f))) * PIf / 180f
        val seat = if (main && t >= .52f) exp(-(t - .52f) / .2f) else 0f
        fun lit(tl: Float) = if (main) cl((t - tl) / .16f) else 1f
        fun head(tl: Float) = if (main && t > tl && t < tl + .32f) 1f - (t - tl) / .32f else 0f
        val lights = ArrayList<Light>()

        // The town across the river: lit outwards from the crease, one running head at a time
        fun town(bs: List<Block>, alpha: Float) {
            for (b in bs) {
                val top = dy - b.h
                c.drawRect(b.x, top, b.x + b.w, dy, fill(al(dark, alpha)))
                c.drawRect(b.x + 1f, top + 1f, b.x + b.w - 1f, dy - 1f, stroke(2f, al(p.champ, .22f * alpha)))
                val dist = (X0 - (b.x + b.w / 2f)) / X0
                var sum = 0f
                for (f in 0 until b.fl) {
                    val y = top + 40f + f * ((b.h - 70f) / b.fl); val tl = .95f + dist * .9f + (b.fl - f) * .03f
                    val lv = lit(tl); val hd = head(tl)
                    c.drawRect(b.x + 14f, y, b.x + b.w - 14f, y + 24f, fill(al(glassC, alpha)))
                    if (lv > 0f) c.drawRect(b.x + 14f, y, b.x + b.w - 14f, y + 24f, fill(al(amber, alpha * lampOn * (.72f * lv + .5f * hd))))
                    sum += lv
                }
                if (b.tower) {
                    val tl = .95f + dist * .9f
                    c.drawRect(b.x + b.w - 46f, top + 20f, b.x + b.w - 18f, dy - 20f, fill(al(glassC, alpha)))
                    c.drawRect(b.x + b.w - 46f, top + 20f, b.x + b.w - 18f, dy - 20f, fill(al(amber, alpha * lampOn * .5f * lit(tl))))
                }
                lights.add(Light(b.x + b.w / 2f, alpha * sum / b.fl * .8f, amber, b.w * .5f))
            }
        }
        town(back, .55f)
        c.drawRect(360f, dy - 160f, 1080f, dy, shaded(LinearGradient(0f, dy - 160f, 0f, dy, al(p.ground, 0f), al(p.ground, .75f), Shader.TileMode.CLAMP)))
        town(fore, 1f)

        // Water and quays
        c.drawRect(-300f, wy, W + 300f, H + 300f, shaded(LinearGradient(0f, wy, 0f, H, op(mix(0x0B1210, 0xAEB9B3, d)), op(mix(0x050807, 0x8C9B95, d)), Shader.TileMode.CLAMP)))
        val quay = op(mix(0x18201C, 0xB5AC9A, d))
        c.drawRect(X0, dy, W + 300f, wy, fill(quay)); c.drawRect(-300f, dy, X0 - span, wy, fill(quay))
        val edge = stroke(3f, al(p.champ, .4f))
        c.drawLine(X0, dy, W + 300f, dy, edge); c.drawLine(-300f, dy, X0 - span, dy, edge)

        for ((x, tl) in listOf(120f to .8f, 300f to .7f)) { val lv = lit(tl); lamp(c, x, lv * lampOn + .5f * head(tl), amber, steel); lights.add(Light(x, lv * lampOn, amber, 40f)) }
        // Near quay: the keeper's hut, the railing, three lamps
        c.drawRect(1210f, dy - 240f, 1410f, dy, fill(op(dark)))
        c.drawRect(1211f, dy - 239f, 1409f, dy - 1f, stroke(2f, al(p.champ, .3f)))
        c.drawRect(1234f, dy - 200f, 1386f, dy - 136f, fill(al(amber, .8f * lampOn + .1f)))
        lights.add(Light(1295f, .6f * lampOn, amber, 60f))
        val rail = stroke(4f, al(p.champ, .35f))
        c.drawLine(1400f, dy - 70f, W + 300f, dy - 70f, rail); c.drawLine(1400f, dy - 36f, W + 300f, dy - 36f, rail)
        for (x in floatArrayOf(1500f, 1800f, 2100f)) { lamp(c, x, lampOn, amber, steel); lights.add(Light(x, lampOn, amber, 40f)) }

        // The span pivots on the crease
        c.save(); c.translate(X0, dy); c.rotate(deg(theta))
        c.drawRect(-span, 0f, 0f, 34f, fill(op(steel)))
        val tr = Path().apply { moveTo(-span + 30f, 0f); lineTo(-span + 70f, -150f); lineTo(-40f, -150f); lineTo(0f, 0f) }
        c.drawPath(tr, stroke(7f, op(steel)))
        val lat = Path(); val seg = 8
        for (i in 0..seg) { val x = lerp(-span + 70f, -40f, i / seg.toFloat()); lat.moveTo(x, -150f); lat.lineTo(x, 0f) }
        for (i in 0 until seg) {
            val x1 = lerp(-span + 70f, -40f, i / seg.toFloat()); val x2 = lerp(-span + 70f, -40f, (i + 1) / seg.toFloat())
            lat.moveTo(x1, if (i % 2 == 1) 0f else -150f); lat.lineTo(x2, if (i % 2 == 1) -150f else 0f)
        }
        c.drawPath(lat, stroke(4f, op(steel)))
        c.restore()
        val raised = theta > PIf / 4
        val nx = -span + 60f; val ny = -170f
        val navX = X0 + cos(theta) * nx - sin(theta) * ny; val navY = dy + sin(theta) * nx + cos(theta) * ny
        if (raised) {
            val g = 1f + (if (!main && t >= .52f) 1.2f * e.orn * exp(-(t - .52f) / .35f) else 0f)
            glowAt(c, navX, navY, 0xE0242E, g)
            lights.add(Light(navX, .8f, 0xE0242E, 26f))
        } else {
            for ((pp, tl) in listOf(-260f to .62f, -520f to .72f)) {
                val lx = X0 + pp * cos(theta); val ly = dy - 150f + pp * sin(theta); val lv = lit(tl)
                glowAt(c, lx, ly - 26f, amber, (lv * lampOn + .6f * head(tl)) * .7f)
                lights.add(Light(lx, lv * lampOn * .7f, amber, 30f))
            }
            // The one climax: the span seats
            if (seat > .01f) {
                c.drawLine(X0 - span - 4f, dy - 40f, X0 - span - 4f, dy + 40f, stroke(4f, al(0xFFFFFF, .8f * seat * e.orn)))
                glowAt(c, X0 - span, dy, p.champ, .8f * seat * e.orn)
            }
        }
        // Reflections: still unless the water is touched
        for (s in lights) {
            if (s.s <= .02f) continue
            val len = 520f * s.s + 60f
            var yy = wy + 10f
            while (yy < wy + len) {
                val f = 1f - (yy - wy) / len
                val wob = sin(yy * .05f + s.x) * 4f + ripAmp(s.x, e.now) * sin(yy * .06f - e.now * .006f)
                c.drawRect(s.x - s.w * f * .6f + wob, yy, s.x + s.w * f * .6f + wob, yy + 7f, fill(al(s.color, .28f * s.s * f)))
                yy += 16f
            }
        }
        return busy
    }

    private fun lamp(c: Canvas, x: Float, lv: Float, color: Int, steel: Int) {
        c.drawLine(x, dy, x, dy - 440f, stroke(6f, op(steel)))
        c.drawRect(x - 20f, dy - 458f, x + 20f, dy - 440f, fill(op(steel)))
        glowAt(c, x, dy - 436f, color, lv)
    }
}

// ---------------- 4. Fanale ----------------

private class Fanale : SipScene {
    private val cy = 1100f; private val rr = 190f
    private val locks = ArrayList<Long>()

    override fun touch(x: Float, y: Float, main: Boolean, now: Long): Boolean {
        locks.removeAll { now - it > 1200 }; locks.add(now); return true
    }

    override fun draw(c: Canvas, e: Env): Boolean {
        val d = e.day; val t = e.t / e.k; val main = e.main
        c.drawRect(-300f, -300f, W + 300f, H + 300f, fill(op(mix(0x07090A, 0x1E2422, d))))
        var busy = if (main) t < 2.4f else t < 1.4f
        // Tap to lock: two flashes
        var flash = 0f
        for (l in locks) { val a = (e.now - l) / 1000f; if (a < 1f) { busy = true; flash = max(flash, .9f * max(0f, sin(a * PIf / .45f))) } }
        for (x in floatArrayOf(566f, W - 566f)) {
            c.drawCircle(x, cy, 620f, shaded(RadialGradient(x, cy, 620f, al(0xB8141E, .16f * (1f + flash)), al(0xB8141E, 0f), Shader.TileMode.CLAMP)))
        }
        val shoulder = Path().apply { moveTo(240f, cy - 250f); cubicTo(700f, cy - 262f, 1556f, cy - 262f, W - 240f, cy - 250f) }
        c.drawPath(shoulder, stroke(3f, al(CHAMPAGNE, .26f)))
        c.drawRect(-300f, 1480f, W + 300f, H + 300f, shaded(LinearGradient(0f, 1480f, 0f, H, op(mix(0x0B0E0E, 0x2A302E, d)), op(mix(0x121716, 0x343B39, d)), Shader.TileMode.CLAMP)))
        val lv = lerp(1f, .82f, d) * (1f + flash)
        val runL = if (main) cl(t / .8f) else 1f; val runBar = if (main) cl((t - .8f) / .32f) else 1f; val tb = 1.12f
        val runR = if (main) 1f else cl(t / .8f); val runRBar = if (main) 1f else cl((t - .8f) / .32f)
        // The one climax: both lamps bloom once
        val bloom = if (main && t > tb) e.orn * exp(-(t - tb) / .45f) else 0f
        lamp(c, W - 566f, -1f, runR, runRBar, lv, bloom)
        lamp(c, 566f, 1f, runL, runBar, lv, if (main) bloom else 0f)
        return busy
    }

    private fun lamp(c: Canvas, cx: Float, dir: Float, run: Float, runBar: Float, lv: Float, bloom: Float) {
        val n = 30; val m = 15
        val inA = if (dir > 0) 0f else PIf
        c.drawCircle(cx, cy, rr, stroke(30f, op(0x22060A)))
        val all = run >= 1f && runBar >= 1f
        val tube = (run * .6f + (if (all) .4f else 0f)) * lv
        // The tube's glow, an annulus of light falling off either side
        val r1 = rr + 90f + 60f * bloom; val r0 = max(1f, rr - 90f)
        c.drawCircle(cx, cy, r1, shaded(RadialGradient(cx, cy, r1,
            intArrayOf(0, 0, al(0xB8141E, cl(.38f * tube + .35f * bloom)), 0), floatArrayOf(0f, r0 / r1, rr / r1, 1f), Shader.TileMode.CLAMP)))
        // Its reflection in the wet road
        val rs = cl(tube + bloom * .5f)
        var yy = 1500f
        while (yy < 2480f) {
            val f = 1f - (yy - 1500f) / 980f; val w = rr * 1.4f * f + 40f; val ox = sin(yy * .07f) * 6f
            c.drawRect(cx - w / 2 + ox, yy, cx + w / 2 + ox, yy + 6f, fill(al(0xC8202A, .34f * rs * f)))
            yy += 14f
        }
        val hi = run * n
        for (j in 0 until n) {
            val a = inA + dir * -1f * j / n * TAU
            var b = if (j < floor(hi).toInt()) 1f else .12f
            val isHead = run < 1f && j == floor(hi).toInt()
            if (isHead) b = 1.5f
            bead(c, cx + cos(a) * rr, cy + sin(a) * rr, 11f * (if (isHead) 1.15f else 1f), b * lv)
        }
        val x0 = cx + dir * (rr + 46f); val hb = runBar * m
        for (j in 0 until m) {
            val u = j / (m - 1f); val x = x0 + dir * j * 17f; val r = lerp(11f, 6f, u)
            var b = if (j < floor(hb).toInt()) lerp(1f, .45f, u) else .1f
            if (runBar > 0f && runBar < 1f && j == floor(hb).toInt()) b = 1.4f
            bead(c, x, cy, r, b * lv)
        }
    }

    private fun bead(c: Canvas, x: Float, y: Float, r: Float, b: Float) {
        c.drawCircle(x, y, r, fill(op(mix(0x3A0A0C, 0xE0242E, cl(b)))))
        if (b > .8f) c.drawCircle(x, y, r * .38f, fill(al(0xFFD9D0, .55f * cl(b - .8f, 0f, .7f))))
    }
}

// ---------------- 5. Goccia ----------------

private class Goccia : SipScene {
    private val wy = 1960f
    private class Blade(val bx: Float, val by: Float, val cx: Float, val cy: Float, val px: Float, val py: Float, val w: Float)
    private class Mark(val i: Int, val x: Float, val t: Long)
    private val blades = listOf(
        Blade(560f, 1960f, 860f, 200f, 1640f, 1020f, 96f), Blade(520f, 1960f, 260f, 700f, 110f, 980f, 84f),
        Blade(600f, 1960f, 560f, 520f, 330f, 430f, 92f), Blade(660f, 1960f, 930f, 960f, 1010f, 1240f, 70f),
        Blade(470f, 1960f, 380f, 1250f, 40f, 1520f, 72f), Blade(700f, 1960f, 760f, 1100f, 820f, 640f, 66f))
    private val rips = ArrayList<Mark>()
    private val bobs = ArrayList<Mark>()
    private val tmp = FloatArray(2)

    private fun q(l: Blade, t: Float, off: Float, out: FloatArray) {
        val u = 1f - t
        out[0] = u * u * l.bx + 2 * u * t * l.cx + t * t * l.px
        out[1] = u * u * l.by + 2 * u * t * (l.cy + off * .35f) + t * t * (l.py + off)
    }

    override fun touch(x: Float, y: Float, main: Boolean, now: Long): Boolean {
        if (y > wy - 30f) { rips.removeAll { now - it.t > 3200 }; rips.add(Mark(0, x, now)); return true }
        var bi = -1; var bd = 220f
        blades.forEachIndexed { i, l -> for (k in 0..20) { q(l, k / 20f, 0f, tmp); val dd = hypot(tmp[0] - x, tmp[1] - y); if (dd < bd) { bd = dd; bi = i } } }
        if (bi >= 0) { bobs.removeAll { now - it.t > 2500 }; bobs.add(Mark(bi, 0f, now)); return true }
        return false
    }

    private fun blade(c: Canvas, l: Blade, off: Float, fillC: Int, rib: Int) {
        val n = 36; val a = FloatArray((n + 1) * 2); val b = FloatArray((n + 1) * 2)
        val p = FloatArray(2); val p1 = FloatArray(2); val p2 = FloatArray(2)
        for (k in 0..n) {
            val t = k / n.toFloat()
            q(l, t, off, p); q(l, min(1f, t + .01f), off, p2); q(l, max(0f, t - .01f), off, p1)
            val dx = p2[0] - p1[0]; val dy = p2[1] - p1[1]; val m = max(1e-3f, hypot(dx, dy))
            val nx = -dy / m; val ny = dx / m; val w = l.w * sin(PIf * (.15f + .85f * t)) * .5f
            a[2 * k] = p[0] + nx * w; a[2 * k + 1] = p[1] + ny * w; b[2 * k] = p[0] - nx * w; b[2 * k + 1] = p[1] - ny * w
        }
        val path = Path(); path.moveTo(a[0], a[1])
        for (k in 0..n) path.lineTo(a[2 * k], a[2 * k + 1])
        for (k in n downTo 0) path.lineTo(b[2 * k], b[2 * k + 1])
        path.close()
        c.drawPath(path, fill(fillC))
        if ((rib ushr 24) != 0) {
            val mid = Path()
            for (k in 0..n) { q(l, k / n.toFloat(), off, p); if (k == 0) mid.moveTo(p[0], p[1]) else mid.lineTo(p[0], p[1]) }
            c.drawPath(mid, stroke(2.5f, rib))
        }
    }

    /** Water held by surface tension: lit glass, one highlight, the caustic below. */
    private fun drop(c: Canvas, x: Float, y: Float, sc: Float, sx: Float, sy: Float) {
        if (sc <= 0f) return
        val r = 30f * sc; val ac = 0x8CC7D1
        c.save(); c.translate(x, y); c.scale(sx, sy)
        val path = Path().apply {
            moveTo(0f, -r * 1.5f); cubicTo(r * .5f, -r * .9f, r, -r * .3f, r, r * .1f)
            arcTo(RectF(-r, r * .1f - r, r, r * .1f + r), 0f, 180f, false)
            cubicTo(-r, -r * .3f, -r * .5f, -r * .9f, 0f, -r * 1.5f); close()
        }
        c.drawPath(path, shaded(RadialGradient(-r * .12f, -r * .15f, r * 1.05f,
            intArrayOf(al(IVORY, .16f), al(ac, .32f), al(ac, .62f)), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)))
        c.drawPath(path, stroke(2f, al(IVORY, .38f)))
        c.save(); c.rotate(deg(-.5f), -r * .38f, -r * .18f)
        c.drawOval(-r * .38f - r * .16f, -r * .18f - r * .24f, -r * .38f + r * .16f, -r * .18f + r * .24f, fill(al(0xFFFFFF, .85f)))
        c.restore()
        c.drawArc(RectF(-r * .68f, r * .12f - r * .68f, r * .68f, r * .12f + r * .68f), 63f, 72f, false, stroke(3f, al(CHAMPAGNE, .5f)))
        c.restore()
    }

    private fun ring(c: Canvas, x: Float, r: Float, a: Float, color: Int) {
        c.drawOval(x - r, wy + 12f - r * .15f, x + r, wy + 12f + r * .15f, stroke(3f, al(color, a)))
    }

    override fun draw(c: Canvas, e: Env): Boolean {
        val p = Base(e); val d = e.day; val t = e.t / e.k; val main = e.main
        paintGround(c, p, e)
        var busy = if (main) t < 3.4f else t < 1.6f
        val leaf = op(mix(VISCONTI_GREEN, 0x3F6447, d)); val rib = al(mix(CHAMPAGNE, 0xE8E2CF, d), .42f)
        val ringC = mix(IVORY, 0xFFFFFF, d)
        fun bob(i: Int): Float {
            var o = 0f
            for (b in bobs) { if (b.i != i) continue; val a = (e.now - b.t) / 1000f; if (a < 2.5f) { o += 30f * sway(a, 8f, .45f); busy = true } }
            return o
        }
        // The hinge's magnetic settle jolts the leaves: soft, so they dip and spring back
        val dip = if (main) 36f * sway(t, 9f, .32f) else 0f
        val offs = FloatArray(blades.size) { i -> bob(i) + if (i == 0) dip else dip * .3f }

        c.drawRect(-300f, wy, W + 300f, H + 300f, shaded(LinearGradient(0f, wy, 0f, H, op(mix(0x0B1513, 0xB9C6C0, d)), op(mix(0x050807, 0x91A39B, d)), Shader.TileMode.CLAMP)))
        c.save(); c.clipRect(-300f, wy, W + 300f, H + 300f); c.translate(0f, 2 * wy); c.scale(1f, -1f)
        blades.forEachIndexed { i, l -> blade(c, l, offs[i], al(leaf, .2f), 0) }
        c.restore()
        c.drawLine(-300f, wy, W + 300f, wy, stroke(3f, al(p.champ, .32f)))
        blades.forEachIndexed { i, l -> blade(c, l, offs[i], leaf, rib) }

        val l = blades[0]; val tipX = l.px; val tipY = l.py + offs[0] + 44f
        if (!main) {
            // Folded: the drop finds its shape
            drop(c, tipX, tipY, spring(t - .12f, .4f, 12f), 1f, 1f)
        } else {
            val g = 7800f; val rel = .14f; val y0 = l.py + 44f
            val ti = rel + sqrt(2f * (wy - y0) / g)
            if (t < rel) drop(c, tipX, tipY, 1f, 1f, 1f)
            else if (t < ti) {
                val tt = t - rel; val y = y0 + .5f * g * tt * tt; val sy = 1f + min(.35f, g * tt / 7000f)
                drop(c, tipX, y, 1f, 1f / sqrt(sy), sy)
            } else {
                // The one climax: the splash
                val a = t - ti
                for (dl in floatArrayOf(0f, .2f, .42f)) { val k = a - dl; if (k <= 0f || k > 2.6f) continue; ring(c, tipX, 40f + 700f * ease(k / 2.6f), .6f * (1f - k / 2.6f), ringC) }
                if (a < .6f) for (i in 0 until 5) {
                    val vx = (i - 2) * 190f; val vy = -900f - (i % 2) * 260f
                    val x = tipX + vx * a; val y = wy + vy * a + .5f * g * a * a
                    if (y < wy) drop(c, x, y, .22f, 1f, 1f)
                }
                val fl = e.orn * exp(-a / .25f)
                if (fl > .02f) c.drawCircle(tipX, wy, 260f, shaded(RadialGradient(tipX, wy, 260f, al(IVORY, .5f * fl), al(IVORY, 0f), Shader.TileMode.CLAMP)))
            }
        }
        for (rp in rips) {
            val a = (e.now - rp.t) / 1000f; if (a > 3f) continue; busy = true
            for (dl in floatArrayOf(0f, .22f)) { val k = a - dl; if (k <= 0f || k > 2.4f) continue; ring(c, rp.x, 30f + 520f * ease(k / 2.4f), .5f * (1f - k / 2.4f), ringC) }
        }
        return busy
    }
}
