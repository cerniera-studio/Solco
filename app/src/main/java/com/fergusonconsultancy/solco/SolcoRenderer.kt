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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
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
    private val tex = IntArray(2)
    private var posArr = FloatArray(0)
    private var posBuf: FloatBuffer? = null
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var disc = FloatArray(3)
    private var discSize = 0
    private var dot = 1f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        progPts = program(PTS_V, PTS_F)
        progTex = program(TEX_V, TEX_F)
        GLES20.glGenBuffers(2, vbo, 0)
        GLES20.glGenTextures(2, tex, 0)
        dirtyBuild = true
        dirtySky = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        if (width != w || height != h) dirtyBuild = true
        w = width; h = height
        dirtySky = true
    }

    /** The look changed in Settings. */
    fun rebuild() { dirtyBuild = true }

    /** The minute changed, or the wallpaper came back into view. */
    fun refresh() {
        dirtySky = true
        val s = scene ?: return
        if (s.style == Style.TRONCO && (LocalDate.now() != builtDay || SystemClock.elapsedRealtime() - builtAt > 15 * 60_000L)) dirtyBuild = true
    }

    fun stir(dir: Int, fast: Boolean) { pendingStir = dir; pendingFast = fast }

    fun touch(x: Float, y: Float, down: Boolean) { scene?.touch(x, y, down) }

    private fun build() {
        if (w <= 0 || h <= 0) return
        val style = Prefs.style(ctx)
        finish = Prefs.finish(ctx)
        val dp = ctx.resources.displayMetrics.density
        val s = Scene(w, h, dp, style, if (style == Style.TRONCO) Growth.rings(ctx) else FloatArray(0))
        scene = s
        dot = max(1f, (dp * 1.05f).roundToInt().toFloat())

        val cols = FloatArray(s.n * 4)
        for (i in 0 until s.n) {
            val c = finish.grain[s.col[i].toInt()]
            cols[4 * i] = ((c shr 16) and 0xFF) / 255f
            cols[4 * i + 1] = ((c shr 8) and 0xFF) / 255f
            cols[4 * i + 2] = (c and 0xFF) / 255f
            cols[4 * i + 3] = s.hash[i]
        }
        val cb = ByteBuffer.allocateDirect(cols.size.coerceAtLeast(1) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        cb.put(cols).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[1])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, cols.size * 4, cb, GLES20.GL_STATIC_DRAW)

        posArr = FloatArray(s.n * 2)
        posBuf = ByteBuffer.allocateDirect(posArr.size.coerceAtLeast(1) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, posArr.size * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        val back = Backdrop.paint(s, finish)
        upload(tex[0], back)
        back.recycle()

        builtAt = SystemClock.elapsedRealtime()
        builtDay = LocalDate.now()
        dirtyBuild = false
        dirtySky = true
        Log.d(TAG, "built ${style.name} ${finish.name} ${w}x$h, ${s.n} grains, cover=${s.cover}")
    }

    private fun paintSky() {
        val s = scene ?: return
        val sky = Sky.now()
        disc = s.disc(sky)
        val bmp = DiscPainter.paint(finish, sky, Prefs.luna(ctx), disc[2], s.dp)
        discSize = bmp.width
        upload(tex[1], bmp)
        bmp.recycle()
        dirtySky = false
    }

    override fun onDrawFrame(gl: GL10?) {
        if (dirtyBuild) build()
        val s = scene ?: return
        if (dirtySky) paintSky()
        val now = SystemClock.uptimeMillis()
        if (pendingStir != 0) {
            s.stir(pendingStir, pendingFast, Prefs.concentrato(ctx), now)
            pendingStir = 0
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
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, s.n)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aCol)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
        val half = discSize / 2f
        drawQuad(tex[1], disc[0] - half, disc[1] - half, disc[0] + half, disc[1] + half)

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
            if (ok[0] == 0) Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "link: " + GLES20.glGetProgramInfoLog(p))
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
