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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.cerniera.kit.Card
import com.cerniera.kit.Crash
import com.cerniera.kit.Fold
import com.cerniera.kit.Kit
import com.cerniera.kit.Row
import com.cerniera.kit.Screen
import com.cerniera.kit.Toggle
import com.cerniera.kit.doctrineCard
import com.cerniera.kit.primary
import com.cerniera.kit.px
import com.cerniera.kit.rounded
import com.cerniera.kit.text
import kotlin.math.min

/**
 * Sipario's one screen, on the Kit: the scene live with a way to watch it unfold and its one main
 * action, the five scenes as painted tiles, one switch. Diagnostics and the doctrine fold away at
 * the foot. Laid out for every shape of the phone by [Stage].
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
        if (Crash.show(this, "Solco")) return
        Kit.dark = true

        val screen = Screen(this, "Sipario", "Live wallpaper \u00b7 in Solco")
        screen.add(text("Folded, a quiet scene. Unfold, and the door swings away from what was standing beside it.", 14.5f, Kit.MUTED).apply {
            setPadding(px(4), px(8), 0, px(14))
        })
        val stage = Stage(this, screen)

        // The scene, live, in this screen's shape. The button plays the other screen's arrival.
        val bounds = windowManager.currentWindowMetrics.bounds
        val onMain = !FoldWatch.tall(bounds.width(), bounds.height())
        preview = SiparioView(this, onMain, bounds.height().toFloat() / bounds.width().coerceAtLeast(1))
        stage.add(preview)
        play = primary(if (preview.main) "Play the fold" else "Play the unfold", filled = false) { flip() }
        stage.add(play, px(14))
        stage.add(text("Touch the scene. Nothing moves at rest.", 12.5f, Kit.MUTED).apply { gravity = Gravity.CENTER_HORIZONTAL }, px(10))
        action = primary("Set as wallpaper") { setWallpaper() }
        stage.add(action, px(16), fill = true)
        val root = stage.build()

        // What this scene does, folded and unfolded
        name = text("", 28f, Kit.INK, displayFace = true).apply { setPadding(px(4), px(26), 0, px(8)) }
        folded = text("", 14f, Kit.MUTED).apply { setPadding(px(4), 0, 0, 0) }
        unfolded = text("", 14f, Kit.MUTED).apply { setPadding(px(4), px(6), 0, 0) }
        screen.add(name); screen.add(folded); screen.add(unfolded)

        // The five scenes, each a still of its cover screen
        screen.section("Scene")
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Act.values().forEachIndexed { i, a ->
            val t = PictureTile(this, a.title, Prefs.act(this) == a, 196, { cv, w, h -> paintAct(cv, w, h, a) }) { pick(a) }
            tiles += a to t
            strip.addView(t, LinearLayout.LayoutParams(px(84), -2).apply { if (i > 0) leftMargin = px(10) })
        }
        screen.add(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(strip)
        })

        screen.section("Motion")
        screen.add(concentratoCard("A fast fold plays the arrival a third quicker, with less of the ornament. Shared with Solco.") { showFlick() })

        diag = text("", 12.5f, Kit.MUTED)
        screen.add(Fold(this, "Diagnostics", "What the wallpaper has seen on this phone").apply { body.addView(diag) }, px(30))
        screen.add(doctrineCard(DOCTRINE_NOTES), px(12))

        setContentView(root)
        window.decorView.setBackgroundColor(Kit.GROUND)
        stage.record(root, preview, action)
        describe(Prefs.act(this))
    }

    override fun onResume() {
        super.onResume()
        if (!::action.isInitialized) return
        val info = runCatching { WallpaperManager.getInstance(this).wallpaperInfo }.getOrNull()
        val ours = info?.serviceName == SiparioWallpaperService::class.java.name
        action.text = if (ours) "Sipario is your wallpaper" else "Set as wallpaper"
        action.background = if (ours) rounded(0, px(28).toFloat(), Kit.HAIRLINE, px(1)) else rounded(Kit.CHAMPAGNE, px(28).toFloat())
        action.setTextColor(if (ours) Kit.INK else Kit.GROUND)
        fillDiag()
    }

    /** Plays the other screen's arrival as a fast fold would, scrolling the scene into view first if needed. */
    private fun showFlick() {
        var v: View = preview
        var top = 0
        while (v.parent is View && v.parent !is android.widget.ScrollView) { top += v.top; v = v.parent as View }
        val go = { preview.play(!preview.main, fast = true); play.text = if (preview.main) "Play the fold" else "Play the unfold" }
        (v.parent as? android.widget.ScrollView)?.let { sv -> if (sv.scrollY > top) { sv.smoothScrollTo(0, (top - px(24)).coerceAtLeast(0)); preview.postDelayed({ go() }, 380); return } }
        go()
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

    companion object {
        /** How Sipario answers each principle, under the doctrine at the foot of its Settings. */
        val DOCTRINE_NOTES = listOf(
            "The monolithic membrane" to "Each scene is one world across both screens: a touch on one half can move something on the other.",
            "Concentrato" to "A fast fold plays the arrival a third quicker with a quarter of the ornament. The trajectory stays.",
            "Giulietta" to "No controls on the scenes. Folded, a quiet scene; unfolding adds the rest of the story and never zooms.",
            "Nothing moves at rest" to "A scene draws on a fold, a touch, or a minute's change of light, and is still otherwise.",
        )
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
class SiparioView(c: Context, var main: Boolean, private val here: Float = 0f) : View(c), Fits {
    override var maxH = c.px(460)
    /** The screen Settings is open on, whose real shape (turned or not) the preview takes. */
    private val onMain = main
    private var scene: SipScene = Prefs.act(c).make()
    private val env = Env()
    private var arrivedAt = SystemClock.uptimeMillis() - 1_000_000L

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 22f * v.resources.displayMetrics.density)
        }
    }

    private val aspect get() = if (main == onMain && here > 0f) here else if (main) World.H / World.W else World.COVER.height() / World.COVER.width()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        val hh = min(maxH.toFloat(), avail * aspect)
        setMeasuredDimension((hh / aspect).toInt(), hh.toInt())
    }

    /** After a fast fold with Concentrato: the arrival a third quicker, with the chosen share of the ornament. */
    private var fast = false

    fun play(toMain: Boolean, fast: Boolean = false) {
        this.fast = fast
        main = toMain
        requestLayout()
        arrivedAt = SystemClock.uptimeMillis()
        scene.arrive(main, arrivedAt)
        invalidate()
    }

    fun show(a: Act) {
        fast = false
        scene = a.make()
        arrivedAt = SystemClock.uptimeMillis()
        scene.arrive(main, arrivedAt)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val now = SystemClock.uptimeMillis()
        env.refreshSky(); env.main = main; env.now = now; env.t = (now - arrivedAt) / 1000f; env.k = if (fast) .65f else 1f; env.orn = if (fast) Prefs.depth(context).ornament else 1f
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
