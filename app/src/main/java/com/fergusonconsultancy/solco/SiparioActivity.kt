package com.fergusonconsultancy.solco

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Outline
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * Sipario's one screen: the scene live, a way to watch it unfold, the five scenes as painted tiles,
 * one switch, and one main action. Diagnostics fold away at the foot.
 */
class SiparioActivity : Activity() {

    private lateinit var preview: SiparioView
    private lateinit var play: TextView
    private lateinit var name: TextView
    private lateinit var folded: TextView
    private lateinit var unfolded: TextView
    private lateinit var action: TextView
    private lateinit var diag: TextView
    private val tiles = ArrayList<Pair<Act, PictureTile>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (scroll, col) = screen()
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            col.setPadding(px(20), px(28) + bars.top, px(20), px(40) + bars.bottom)
            insets
        }

        col.addView(label("Sipario", 46f, Palette.IVORY, display = true))
        col.addView(label("Folded, a quiet scene. Unfold, and the door swings away from what was standing beside it.", 14.5f, Palette.MUTED).apply {
            setPadding(0, px(8), 0, px(22))
        })

        // The scene, live, in this screen's shape. The button plays the other screen's arrival.
        val bounds = windowManager.currentWindowMetrics.bounds
        val onCover = bounds.height() > bounds.width() * 1.5f
        preview = SiparioView(this, !onCover)
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(preview, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        play = button(if (preview.main) "Play the fold" else "Play the unfold", primary = false) { flip() }
        col.addView(play, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(14); gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(label("Touch the scene. Nothing moves at rest.", 12.5f, Palette.MUTED).apply {
            gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, px(10), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        // What this scene does, folded and unfolded
        name = label("", 28f, Palette.IVORY, display = true).apply { setPadding(px(2), px(26), 0, px(8)) }
        folded = label("", 14f, Palette.MUTED)
        unfolded = label("", 14f, Palette.MUTED).apply { setPadding(0, px(6), 0, 0) }
        col.addView(name); col.addView(folded); col.addView(unfolded)

        // The five scenes, each a still of its cover screen
        col.addView(eyebrow("Scene"))
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Act.values().forEachIndexed { i, a ->
            val t = PictureTile(this, a.title, Prefs.act(this) == a, 196, { cv, w, h -> paintAct(cv, w, h, a) }) { pick(a) }
            tiles += a to t
            strip.addView(t, LinearLayout.LayoutParams(px(84), -2).apply { if (i > 0) leftMargin = px(10) })
        }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(strip)
        })

        col.addView(eyebrow("Motion"))
        col.addView(card().apply {
            addView(row("Concentrato", "A fast fold plays the arrival a third quicker, with a quarter of the ornament. Shared with Solco.",
                Toggle(this@SiparioActivity, Prefs.concentrato(this@SiparioActivity)) { Prefs.setConcentrato(this@SiparioActivity, it) }))
        })

        action = button("Set as wallpaper") { setWallpaper() }
        col.addView(action, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(30) })

        val head = eyebrow("Diagnostics  +").apply { setTextColor(Palette.MUTED) }
        diag = label("", 12.5f, Palette.MUTED).apply { visibility = View.GONE; setPadding(px(4), 0, 0, 0) }
        head.setOnClickListener {
            diag.visibility = if (diag.visibility == View.GONE) View.VISIBLE else View.GONE
            head.text = if (diag.visibility == View.GONE) "DIAGNOSTICS  +" else "DIAGNOSTICS  −"
            fillDiag()
        }
        col.addView(head); col.addView(diag)

        setContentView(scroll)
        window.decorView.setBackgroundColor(Palette.GROUND)
        describe(Prefs.act(this))
    }

    override fun onResume() {
        super.onResume()
        val info = runCatching { WallpaperManager.getInstance(this).wallpaperInfo }.getOrNull()
        val ours = info?.serviceName == SiparioWallpaperService::class.java.name
        action.text = if (ours) "Sipario is your wallpaper" else "Set as wallpaper"
        action.background = if (ours) rounded(Palette.RAISED, px(22).toFloat(), Palette.HAIRLINE, px(1)) else rounded(Palette.CHAMPAGNE, px(22).toFloat())
        action.setTextColor(if (ours) Palette.IVORY else Palette.GROUND)
        fillDiag()
    }

    private fun flip() {
        preview.play(!preview.main)
        play.text = if (preview.main) "Play the fold" else "Play the unfold"
    }

    private fun pick(a: Act) {
        if (Prefs.act(this) == a) return
        Prefs.setAct(this, a)
        tiles.forEach { (k, t) -> t.refresh(k == a, false) }
        describe(a)
        preview.show(a)
    }

    private fun describe(a: Act) {
        name.text = a.title
        folded.text = "Folded: ${a.folded}"
        unfolded.text = "Unfolded: ${a.unfolded}"
    }

    private fun fillDiag() {
        val p = Prefs.of(this)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        diag.text = listOf(
            "Cover screen: " + (p.getString("sip_seen_cover", null) ?: "not drawn yet"),
            "Main screen: " + (p.getString("sip_seen_main", null) ?: "not drawn yet"),
            "Drawn on the cover: " + (p.getString("sip_drew_cover", null) ?: "never"),
            "Drawn on the main screen: " + (p.getString("sip_drew_main", null) ?: "never"),
            "Last fold: " + (p.getString("last_fold", null) ?: "none yet"),
            "Version: $version",
        ).joinToString("\n")
    }

    private fun setWallpaper() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, SiparioWallpaperService::class.java)))
        } catch (e: Exception) {
            runCatching { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
        }
    }
}

/** A scene at rest on the cover screen, for the tiles. */
fun paintAct(cv: Canvas, w: Int, h: Int, a: Act) {
    val e = Env().apply { refreshSky(); main = false; t = 1e6f; now = SystemClock.uptimeMillis() }
    World.draw(cv, w, h, a.make(), e)
}

/**
 * The scene, live, in the cover's or the main screen's shape. [play] switches shape and plays the
 * arrival exactly as a fold would. Touch reaches the scene as on the home screen.
 */
class SiparioView(c: Context, var main: Boolean) : View(c) {
    private var scene: SipScene = Prefs.act(c).make()
    private val env = Env()
    private var arrivedAt = SystemClock.uptimeMillis() - 1_000_000L

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 22f * v.resources.displayMetrics.density)
        }
    }

    private val aspect get() = if (main) World.H / World.W else World.COVER.height() / World.COVER.width()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        val hh = min(context.px(460).toFloat(), avail * aspect)
        setMeasuredDimension((hh / aspect).toInt(), hh.toInt())
    }

    fun play(toMain: Boolean) {
        main = toMain
        requestLayout()
        arrivedAt = SystemClock.uptimeMillis()
        scene.arrive(main, arrivedAt)
        invalidate()
    }

    fun show(a: Act) {
        scene = a.make()
        arrivedAt = SystemClock.uptimeMillis()
        scene.arrive(main, arrivedAt)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val now = SystemClock.uptimeMillis()
        env.refreshSky(); env.main = main; env.now = now; env.t = (now - arrivedAt) / 1000f; env.k = 1f; env.orn = 1f
        if (World.draw(canvas, width, height, scene, env)) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && width > 0) {
            val t = World.transform(width, height, main)
            if (scene.touch((e.x - t[1]) / t[0], (e.y - t[2]) / t[0], main, SystemClock.uptimeMillis())) invalidate()
        }
        return true
    }
}
