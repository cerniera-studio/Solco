package com.fergusonconsultancy.solco

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.cerniera.kit.Kit

/**
 * Sipario's wallpaper. The scenes are drawings rather than grain, so this engine draws with a
 * hardware canvas straight onto the wallpaper's surface. Frames run only while something moves:
 * after a fold, a touch, or once a minute for the light. Hidden, it listens to nothing.
 */
class SiparioWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SiparioEngine()

    /** Shared by every engine, so a fold is seen whether One UI gives each screen an engine or resizes one. */
    private object Seen { var tall: Boolean? = null }

    private inner class SiparioEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener,
        Choreographer.FrameCallback {

        private var w = 0
        private var h = 0
        private var tall = true
        private var visible = false
        private var scheduled = false
        private var act = Prefs.act(this@SiparioWallpaperService)
        private var scene: SipScene = act.make()
        private val env = Env()
        private var arrivedAt = 0L
        private var fast = false
        private var listening = false
        private var failed = false
        private var reported = false

        private val clock = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { frame() }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setTouchEventsEnabled(true)
            val c = this@SiparioWallpaperService
            Prefs.of(c).registerOnSharedPreferenceChangeListener(this)
            // The hinge (the Kit's) is heard for as long as the wallpaper is set, not only while it is in
            // view: the screen goes dark before the hinge finishes moving, so a visible-only listener
            // never has the readings a fold's speed is measured from
            FoldWatch.listen(c)
            arrivedAt = SystemClock.uptimeMillis() - 1_000_000L
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            // One UI hands over a zero-size surface mid-fold. It is neither screen, so it must not count as an arrival.
            if (width <= 0 || height <= 0) return
            w = width; h = height
            // Cover or main from the shape, either way round: turning the cover is not a fold
            tall = FoldWatch.tall(width, height)
            SipDiag.seen(this@SiparioWallpaperService, tall, width, height)
            if (visible) arrive() else frame()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            frame()
        }

        /** A screen arrived. If it is not the one last seen, the phone folded or opened, and the scene plays its arrival. */
        private fun arrive() {
            val prev = Seen.tall
            Seen.tall = tall
            if (prev != null && prev != tall) {
                val c = this@SiparioWallpaperService
                fast = Prefs.concentrato(c) && FoldWatch.fast(c)
                arrivedAt = SystemClock.uptimeMillis()
                scene.arrive(!tall, arrivedAt)
            }
            frame()
        }

        override fun onVisibilityChanged(v: Boolean) {
            super.onVisibilityChanged(v)
            visible = v
            if (v) { listen(true); arrive() } else { listen(false); stop() }
        }

        private fun listen(on: Boolean) {
            if (on == listening) return
            listening = on
            val c = this@SiparioWallpaperService
            if (on) {
                val f = IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }
                if (android.os.Build.VERSION.SDK_INT >= 33) c.registerReceiver(clock, f, Context.RECEIVER_NOT_EXPORTED) else c.registerReceiver(clock, f)
            } else {
                runCatching { c.unregisterReceiver(clock) }
            }
        }

        override fun onTouchEvent(e: MotionEvent) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN && w > 0) {
                val t = World.transform(w, h, !tall)
                val x = (e.x - t[1]) / t[0]; val y = (e.y - t[2]) / t[0]
                if (scene.touch(x, y, !tall, SystemClock.uptimeMillis())) frame()
            }
            super.onTouchEvent(e)
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
            if (key != "sipario") return
            act = Prefs.act(this@SiparioWallpaperService)
            scene = act.make()
            arrivedAt = SystemClock.uptimeMillis() - 1_000_000L
            failed = false
            notifyColorsChanged()
            frame()
        }

        override fun onComputeColors(): WallpaperColors =
            WallpaperColors(Color.valueOf(Kit.Fixed.VISCONTI_GROUND), Color.valueOf(if (act == Act.FANALE) Kit.Fixed.LACQUER else Kit.Fixed.CHAMPAGNE), Color.valueOf(Kit.Fixed.IVORY))

        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (!visible) return
            if (draw()) schedule()
        }

        private fun schedule() {
            if (scheduled) return
            scheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun stop() {
            if (scheduled) Choreographer.getInstance().removeFrameCallback(this)
            scheduled = false
        }

        /** Draw one frame now and keep going while the scene says something moves. */
        private fun frame() { if (draw() && visible) schedule() }

        private fun draw(): Boolean {
            if (w <= 0 || h <= 0) return false
            val holder = surfaceHolder
            var c: Canvas? = null
            var busy = false
            try {
                c = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: holder.lockCanvas()
                if (c == null) return false
                if (failed) { c.drawColor(Kit.Fixed.VISCONTI_GROUND); return false }
                val now = SystemClock.uptimeMillis()
                env.refreshSky()
                env.main = !tall
                env.now = now
                env.t = (now - arrivedAt) / 1000f
                env.k = if (fast) .65f else 1f
                env.orn = if (fast) .25f else 1f
                busy = World.draw(c, w, h, scene, env)
                if (!reported) { reported = true; SipDiag.drew(this@SiparioWallpaperService, tall, "${w}x$h, ${act.title}") }
            } catch (t: Throwable) {
                // Never take the process down: record what failed and show the plain ground instead
                failed = true
                Gpu.failed(this@SiparioWallpaperService, "Sipario drawing ${act.title} (${w}x$h)", t)
                Log.e(SolcoRenderer.TAG, "Sipario frame failed", t)
                runCatching { c?.drawColor(Kit.Fixed.VISCONTI_GROUND) }
                busy = false
            } finally {
                if (c != null) runCatching { holder.unlockCanvasAndPost(c) }
            }
            return busy
        }

        override fun onDestroy() {
            Prefs.of(this@SiparioWallpaperService).unregisterOnSharedPreferenceChangeListener(this)
            listen(false)
            FoldWatch.unlisten()
            stop()
            super.onDestroy()
        }
    }
}

/** What Sipario has seen, for its Diagnostics. */
object SipDiag {
    private fun stamp() = java.text.SimpleDateFormat("HH:mm, d MMM", java.util.Locale.UK).format(java.util.Date())
    fun seen(c: Context, tall: Boolean, w: Int, h: Int) {
        Prefs.of(c).edit().putString(if (tall) "sip_seen_cover" else "sip_seen_main", "$w × $h px, ${stamp()}").apply()
    }
    fun drew(c: Context, tall: Boolean, s: String) {
        Prefs.of(c).edit().putString(if (tall) "sip_drew_cover" else "sip_drew_main", "$s, ${stamp()}").apply()
    }
}
