package com.fergusonconsultancy.solco

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.time.LocalDate
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the wallpaper: the ground and grooves as one picture, the grain as points, the disc on top.
 * Frames are drawn only on request and only while something moves. At rest nothing is drawn,
 * which is both the look and the battery policy.
 *
 * Every method runs on the GL thread; the engine reaches it through queueEvent.
 */
class SolcoRenderer(private val ctx: Context, private val wake: Runnable) : GLSurfaceView.Renderer {

    private var scene: Scene? = null
    private var w = 0
    private var h = 0
    private var finish = Finish.VISCONTI
    private var dirtyBuild = true
    private var dirtySky = true
    private var builtAt = 0L
    private var builtDay: LocalDate? = null
    private var pendingStir = 0
    private var pendingFast = false

    private var progPts = 0
    private var progTex = 0
    private val vbo = IntArray(2)
    private val tex = IntArray(4)
    /** Notturno's planets for the current sky: x, y, width. */
    private var sparks: List<FloatArray> = emptyList()
    private var shown = true
    private var posArr = FloatArray(0)
    private var posBuf: FloatBuffer? = null
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var disc = FloatArray(3)
    private var discSize = 0
    private var dot = 1f

    private var failed = false
    private var reported = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        runCatching {
            val range = FloatArray(2); GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
            val maxTex = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
            Gpu.gpu(ctx, "${GLES20.glGetString(GLES20.GL_RENDERER)}, ${GLES20.glGetString(GLES20.GL_VERSION)}, " +
                "points ${range[0].roundToInt()} to ${range[1].roundToInt()} px, textures to ${maxTex[0]} px")
        }
        finish = Prefs.finish(ctx)
        progPts = program(PTS_V, PTS_F)
        progTex = program(TEX_V, TEX_F)
        GLES20.glGenBuffers(2, vbo, 0)
        GLES20.glGenTextures(4, tex, 0)
        glowLevel = -1
        dirtyBuild = true
        dirtySky = true
        failed = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        if (width != w || height != h) dirtyBuild = true
        w = width; h = height
        dirtySky = true
    }

    /** The look changed in Settings. */
    fun rebuild() { dirtyBuild = true; failed = false }

    /** The minute changed, or the wallpaper came back into view. */
    fun refresh() {
        dirtySky = true
        val s = scene ?: return
        if (s.style == Style.TRONCO && (LocalDate.now() != builtDay || SystemClock.elapsedRealtime() - builtAt > 15 * 60_000L)) dirtyBuild = true
        // Stagione moves on a day at midnight; Sentiero is raked again then, and relaid as the trail grows
        if ((s.style == Style.STAGIONE || s.style == Style.SENTIERO) && LocalDate.now() != builtDay) dirtyBuild = true
        if (s.style == Style.SENTIERO && Trail.version != builtTrail && !tracking) dirtyBuild = true
        // Orma: a new day smooths the sand; new steps press new prints (relaid on the minute, never mid-step)
        if (s.style == Style.ORMA && (LocalDate.now() != builtDay || Steps.count(ctx) / 25 != builtSteps / 25)) dirtyBuild = true
        // Deserto: a new minute while the clock shows
        if (s.style == Style.DESERTO && (locked || preview) && builtMinute != minuteNow()) dirtyBuild = true
        // Pioggia: a new reading of the weather
        if (s.style == Style.PIOGGIA && (Weather.now(ctx)?.at ?: 0L) != builtWx) dirtyBuild = true
        // Copertina: the sleeve's colours (or the strength) changed since the grain was coloured
        if (Copertina.read(ctx) != builtCopertina) { dirtyBuild = true; dirtySky = true }
        // Delta's channels silt up as they age: carved again once an hour at most
        if (s.style == Style.DELTA && SystemClock.elapsedRealtime() - builtAt > 60 * 60_000L) dirtyBuild = true
    }

    fun stir(dir: Int, fast: Boolean) { pendingStir = dir; pendingFast = fast }

    /** The display's rotation, set by the engine before a new size arrives, so a turned screen lays out the right way. */
    @Volatile var rotation = 0

    /** Sentiero: whether a finger is down, where it was last kept, and whether it has become a swipe. */
    private var tracking = false
    private var swiped = false
    private var lastTx = 0f
    private var lastTy = 0f

    fun touch(x: Float, y: Float, down: Boolean) {
        val s = scene
        s?.touch(x, y, down)
        // Sentiero keeps where the finger went down, and a swipe's path every 16 dp
        if (s == null || s.style != Style.SENTIERO || !down) { tracking = false; return }
        val f = s.glassFrac(x, y)
        if (!tracking) {
            tracking = true; swiped = false; lastTx = x; lastTy = y
            Trail.add(ctx, s.cover, f[0], f[1], 0)
            return
        }
        if (kotlin.math.hypot(x - lastTx, y - lastTy) >= 16f * s.dp) {
            if (!swiped) { Trail.becomeStroke(s.cover); swiped = true }
            Trail.add(ctx, s.cover, f[0], f[1], 2)
            lastTx = x; lastTy = y
        }
    }

    @Volatile private var builtLevel = -1f
    @Volatile private var builtCharging = false

    /**
     * The battery changed: Marea refills when the level moves a whole percent, and breathes only on
     * charge. Delta carves a new channel when a charge begins or ends, and deepens it every 5% between.
     */
    fun power(level: Float, charging: Boolean) {
        val s = scene ?: return
        when (s.style) {
            Style.MAREA -> {
                if (kotlin.math.abs(level - builtLevel) >= .01f) dirtyBuild = true
                s.breathing = charging
            }
            Style.DELTA -> if (charging != builtCharging || (charging && kotlin.math.abs(level - builtLevel) >= .05f)) dirtyBuild = true
            else -> {}
        }
    }

    /** The tilt changed (Duna). Screen axes, y down. */
    fun tilt(x: Float, y: Float) { scene?.tilt(x, y) }

    /**
     * Deserto: whether the phone is locked (the clock shows), or this is the system's preview (the
     * clock shows so it can be seen before it is set), and the minute the clock was laid for.
     */
    @Volatile var locked = false
    @Volatile var preview = false
    @Volatile private var builtMinute = -1
    private fun minuteNow() = java.time.LocalTime.now().let { it.hour * 60 + it.minute }

    /** Locked or unlocked: the clock's grain is already laid, so it only needs drawing or not. */
    fun locked(v: Boolean) {
        locked = v
        val s = scene ?: return
        if (v && s.style == Style.DESERTO && builtMinute != minuteNow()) dirtyBuild = true
    }

    /** What the last build was laid from: Pioggia's reading and Sentiero's trail. */
    @Volatile private var builtWx = 0L
    @Volatile private var builtTrail = -1
    @Volatile private var builtSteps = -1

    /** Polvere: how long the phone lay untouched, applied once the grain for this screen is in place. */
    private var pendingRest = 0L
    fun rest(ms: Long) { pendingRest = ms }

    /** Everything a look needs, made away from the GL thread so the first frame never waits for it. */
    private class Prepared(val w: Int, val h: Int, val scene: Scene, val finish: Finish, val cols: FloatArray, val back: Bitmap, val ms: Long)

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var ready: Prepared? = null
    @Volatile private var building = false

    /**
     * Starts building the look for the current size on a worker thread. Android holds the system's
     * thread until the first frame is drawn, so the GL thread shows the plain ground at once and
     * the grain arrives when it is ready, a fraction of a second later.
     */
    private fun startBuild() {
        if (w <= 0 || h <= 0 || building) return
        building = true
        dirtyBuild = false
        val bw = w; val bh = h
        worker.execute {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val style = Prefs.style(ctx)
                val f = Prefs.finish(ctx)
                val dp = ctx.resources.displayMetrics.density
                val (lvl, chg) = Power.read(ctx)
                builtLevel = lvl
                builtCharging = chg
                val wx = if (style == Style.PIOGGIA) Weather.now(ctx) else null
                builtWx = wx?.at ?: 0L
                builtTrail = Trail.version
                builtSteps = if (style == Style.ORMA) Steps.count(ctx) else -1
                builtCopertina = Copertina.read(ctx)
                // Deserto: the clock is laid every time, so it is there the moment the phone locks
                builtMinute = minuteNow()
                val clk = if (style == Style.DESERTO) ClockPaint.mask(ctx, bw, bh, dp, FoldWatch.tall(bw, bh)) else null
                val sc = Scene(bw, bh, dp, style, if (style == Style.TRONCO) Growth.rings(ctx) else FloatArray(0), level = lvl, charging = chg, rotation = rotation,
                    sky = if (style == Style.POLVERE || style == Style.DESERTO || style == Style.ORMA) Sky.now(Prefs.notturno(ctx)) else null, clock = clk,
                    charges = if (style == Style.DELTA) Charges.channels(ctx) else FloatArray(0),
                    weather = wx, season = if (style == Style.STAGIONE) Seasons.now() else null,
                    trail = if (style == Style.SENTIERO) Trail.points(ctx, FoldWatch.tall(bw, bh)) else FloatArray(0),
                    walk = if (style == Style.ORMA) Steps.today(ctx) else FloatArray(0), walkNow = Steps.nowMinute(), walkDay = LocalDate.now().toEpochDay())
                val cols = FloatArray(sc.n * 4)
                for (i in 0 until sc.n) {
                    val c = Copertina.grain(f, sc.col[i].toInt())
                    cols[4 * i] = ((c shr 16) and 0xFF) / 255f
                    cols[4 * i + 1] = ((c shr 8) and 0xFF) / 255f
                    cols[4 * i + 2] = (c and 0xFF) / 255f
                    cols[4 * i + 3] = sc.hash[i]
                }
                val back = Backdrop.paint(sc, f)
                ready = Prepared(bw, bh, sc, f, cols, back, SystemClock.elapsedRealtime() - t0)
            } catch (t: Throwable) {
                Gpu.failed(ctx, "Building the wallpaper (${bw}x$bh)", t)
                Gpu.glError(ctx, "Build failed: $t")
                Log.e(TAG, "build failed", t)
            } finally {
                building = false
            }
            wake.run()
        }
    }

    /** On the GL thread: hand a finished build to the GPU. */
    private fun install(p: Prepared) {
        ready = null
        if (p.w != w || p.h != h) { p.back.recycle(); dirtyBuild = true; return }   // the screen changed meanwhile
        val s = p.scene
        finish = p.finish
        dot = max(1f, (s.dp * 1.05f).roundToInt().toFloat())
        val cb = ByteBuffer.allocateDirect(p.cols.size.coerceAtLeast(1) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        cb.put(p.cols).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[1])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, p.cols.size * 4, cb, GLES20.GL_STATIC_DRAW)
        posArr = FloatArray(s.n * 2)
        posBuf = ByteBuffer.allocateDirect(posArr.size.coerceAtLeast(1) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, posArr.size * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        upload(tex[0], p.back)
        p.back.recycle()
        // A stir asked for while the grain was being made applies to the new grain
        scene = s
        builtAt = SystemClock.elapsedRealtime()
        builtDay = LocalDate.now()
        dirtySky = true
        reported = false
        lastBuildMs = p.ms
        stillDue = true
        Log.d(TAG, "built ${s.style.name} ${finish.name} ${w}x$h, ${s.n} grains in ${p.ms} ms, cover=${s.cover}")
    }

    private var lastBuildMs = 0L
    /** A scene was just installed: save a new still on the next frame. */
    private var stillDue = true

    /** The sleeve the grain was last coloured for ("" for the finish's own). */
    @Volatile private var builtCopertina = ""

    /** Sveglia's dawn glow, repainted only when its level moves a fortieth (40 steps over the dawn). */
    private var glowLevel = -1

    private fun drawGlow(s: Scene) {
        val q = DawnGlow.now()
        if (q < 0f) { glowLevel = -1; return }
        val level = (q * 40).toInt()
        if (level != glowLevel) {
            val b = DawnGlow.paint(w, h, s.cx, s.cy, s.r, q)
            upload(tex[2], b); b.recycle()
            glowLevel = level
        }
        drawQuad(tex[2], 0f, 0f, w.toFloat(), h.toFloat())
    }

    private fun paintSky() {
        val s = scene ?: return
        val sky = Sky.now(Prefs.notturno(ctx))
        // Polvere's shaft falls from the disc: rebuilt once the sun or moon has moved on, or set
        if (s.style == Style.POLVERE && (kotlin.math.abs(sky.alt - s.skyAlt) > .04f || sky.shown != s.skyShown)) dirtyBuild = true
        // Deserto: relit when the light crosses to the other side at noon, or night falls or lifts
        if (s.style == Style.DESERTO && ((sky.minutes < 720) != s.litMorning || !sky.day != s.atNight)) dirtyBuild = true
        // Orma: the prints are lit from the other side once the sun passes noon
        if (s.style == Style.ORMA && (sky.minutes < 720) != s.litMorning) dirtyBuild = true
        disc = s.disc(sky)
        shown = sky.shown
        val bmp = DiscPainter.paint(finish, sky, Prefs.luna(ctx), disc[2], s.dp, tint = true)
        discSize = bmp.width
        upload(tex[1], bmp)
        bmp.recycle()
        sparks = sky.sparks.map { s.spark(it, sky) }
        if (sparks.isNotEmpty()) SparkPainter.paint(finish).let { upload(tex[3], it); it.recycle() }
        dirtySky = false
    }

    override fun onDrawFrame(gl: GL10?) {
        if (failed) { clearToGround(); return }
        try {
            frame()
        } catch (t: Throwable) {
            // Never take the process down: record what failed and show the plain ground instead
            failed = true
            Gpu.failed(ctx, "Drawing the wallpaper (${w}x$h)", t)
            Gpu.glError(ctx, t.toString())
            Log.e(TAG, "frame failed", t)
            clearToGround()
        }
    }

    private fun clearToGround() {
        val g = finish.ground
        GLES20.glClearColor(((g shr 16) and 0xFF) / 255f, ((g shr 8) and 0xFF) / 255f, (g and 0xFF) / 255f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun frame() {
        ready?.let { install(it) }
        if (dirtyBuild) startBuild()
        val s = scene?.takeIf { it.w == w && it.h == h } ?: run { clearToGround(); return }
        if (dirtySky) paintSky()
        val now = SystemClock.uptimeMillis()
        if (pendingStir != 0) {
            s.stir(pendingStir, pendingFast, Prefs.concentrato(ctx), now, Prefs.depth(ctx).grain, Prefs.linger(ctx).decay)
            pendingStir = 0
        }
        // The dust that settled while the phone lay still, once this screen's grain is final
        if (pendingRest > 0L && !dirtyBuild && !building && ready == null) {
            if (s.style == Style.POLVERE) s.settle(Polvere.share(pendingRest))
            pendingRest = 0L
        }
        val moving = s.step(now)

        GLES20.glDisable(GLES20.GL_BLEND)
        drawQuad(tex[0], 0f, 0f, w.toFloat(), h.toFloat())

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val pb = posBuf
        if (s.n > 0 && pb != null) {
            s.positions(posArr)
            pb.position(0); pb.put(posArr, 0, s.n * 2); pb.position(0)
            GLES20.glUseProgram(progPts)
            val aPos = GLES20.glGetAttribLocation(progPts, "aPos")
            val aCol = GLES20.glGetAttribLocation(progPts, "aCol")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, s.n * 8, pb)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[1])
            GLES20.glEnableVertexAttribArray(aCol)
            GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progPts, "uRes"), w.toFloat(), h.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progPts, "uSize"), dot)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progPts, "uEmpty"), s.empty)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progPts, "uAlpha"), finish.grainA)
            // Deserto's clock is the last of the grain: drawn only while the phone is locked
            val count = if (s.style == Style.DESERTO && !(locked || preview)) min(s.clockFrom, s.n) else s.n
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aCol)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
        // Sveglia's dawn, when its alarm screen is see-through over Solco: over the grain, under the sun
        drawGlow(s)
        val half = discSize / 2f
        if (shown) drawQuad(tex[1], disc[0] - half, disc[1] - half, disc[0] + half, disc[1] + half)
        for (p in sparks) { val hw = p[2] / 2f; drawQuad(tex[3], p[0] - hw, p[1] - hw, p[0] + hw, p[1] + hw) }

        // A still for the family's glass (27 Nov 2026): after a build, then now and then; never while locked
        if (!locked && !preview && !dirtyBuild && !building) {
            runCatching { Still.maybe(ctx, w, h, s.cover, fresh = stillDue) }.onFailure { Log.w(TAG, "still", it) }
            stillDue = false
        }
        if (!reported) {
            reported = true
            val err = GLES20.glGetError()
            if (err != GLES20.GL_NO_ERROR) Gpu.glError(ctx, "GL error 0x${Integer.toHexString(err)} on the first frame, ${w}x$h")
            Gpu.drew(ctx, s.cover, "${w}x$h, ${s.n} grains, built in $lastBuildMs ms")
        }
        if (moving) wake.run()
    }

    private fun drawQuad(t: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        quad.position(0)
        quad.put(floatArrayOf(x0, y0, 0f, 0f, x1, y0, 1f, 0f, x0, y1, 0f, 1f, x1, y1, 1f, 1f))
        GLES20.glUseProgram(progTex)
        val aPos = GLES20.glGetAttribLocation(progTex, "aPos")
        val aUv = GLES20.glGetAttribLocation(progTex, "aUv")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progTex, "uTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progTex, "uRes"), w.toFloat(), h.toFloat())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    private fun upload(t: Int, b: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { val log = GLES20.glGetShaderInfoLog(s); Log.e(TAG, "shader: $log"); Gpu.glError(ctx, "Shader did not compile: $log") }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES20.glGetProgramInfoLog(p); Log.e(TAG, "link: $log"); Gpu.glError(ctx, "Program did not link: $log") }
        return p
    }

    companion object {
        const val TAG = "Solco"

        // Pixels in, y down. A grain whose hash falls below uEmpty drops to a quarter (Concentrato).
        private const val PTS_V = """
attribute vec2 aPos;
attribute vec4 aCol;
uniform vec2 uRes;
uniform float uSize;
uniform float uEmpty;
uniform float uAlpha;
varying vec4 vC;
void main() {
    vec2 p = aPos / uRes * 2.0 - 1.0;
    gl_Position = vec4(p.x, -p.y, 0.0, 1.0);
    gl_PointSize = uSize;
    float a = 1.0;
    if (uEmpty > 0.0) a = clamp(0.25 + 0.75 * (aCol.a - uEmpty) / 0.08, 0.25, 1.0);
    a *= uAlpha;
    vC = vec4(aCol.rgb * a, a);
}"""
        private const val PTS_F = """
precision mediump float;
varying vec4 vC;
void main() { gl_FragColor = vC; }"""

        private const val TEX_V = """
attribute vec2 aPos;
attribute vec2 aUv;
uniform vec2 uRes;
varying vec2 vUv;
void main() {
    vec2 p = aPos / uRes * 2.0 - 1.0;
    gl_Position = vec4(p.x, -p.y, 0.0, 1.0);
    vUv = aUv;
}"""
        private const val TEX_F = """
precision mediump float;
uniform sampler2D uTex;
varying vec2 vUv;
void main() { gl_FragColor = texture2D(uTex, vUv); }"""
    }
}

/** Polvere: how much of the dust in the light settles while the phone lies untouched. */
object Polvere {
    /** Half has settled after about an hour still; nearly all after four. Glances settle none. */
    @JvmStatic fun share(restMs: Long): Float =
        if (restMs < 30_000L) 0f else (1f - kotlin.math.exp(-(restMs - 30_000L) / 5_200_000f)).coerceIn(0f, .96f)
}
