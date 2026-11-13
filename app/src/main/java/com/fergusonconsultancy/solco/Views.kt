package com.fergusonconsultancy.solco

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cerniera.kit.Card
import com.cerniera.kit.Kit
import com.cerniera.kit.Row
import com.cerniera.kit.Segmented
import com.cerniera.kit.Toggle
import com.cerniera.kit.primary
import com.cerniera.kit.Screen
import com.cerniera.kit.px
import com.cerniera.kit.text
import kotlin.math.max
import kotlin.math.min

private fun View.roundClip(radiusDp: Float) {
    clipToOutline = true
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radiusDp * v.resources.displayMetrics.density)
    }
}

/**
 * The wallpaper itself, live, in the shape of the screen Settings is open on: tall on the cover,
 * near square on the main screen, wide when either is turned. Touch moves the grain exactly as on
 * the home screen. [rotation] is the display's, so a turned preview lies as the wallpaper will.
 */
class PreviewView(c: Context, private val aspect: Float, private val rotation: Int = 0) : View(c), Fits {
    override var maxH = c.px(460)
    private var scene: Scene? = null
    private var back: Bitmap? = null
    private var disc: Bitmap? = null
    private var dpos = FloatArray(3)
    private var finish = Finish.VISCONTI
    private val painter = ScenePainter()

    init { roundClip(22f) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        val hh = min(maxH.toFloat(), avail * aspect)
        setMeasuredDimension((hh / aspect).toInt(), hh.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rebuild()
        postDelayed({ stir(1) }, 350)
    }

    fun rebuild() {
        if (width <= 0 || height <= 0) return
        val style = Prefs.style(context)
        finish = Prefs.finish(context)
        val dp = resources.displayMetrics.density
        val (lvl, chg) = Power.read(context)
        val s = Scene(width, height, dp, style, if (style == Style.TRONCO) Growth.rings(context) else FloatArray(0), level = lvl, charging = chg, rotation = rotation)
        back?.recycle(); disc?.recycle()
        back = Backdrop.paint(s, finish)
        val sky = Sky.now()
        dpos = s.disc(sky)
        disc = DiscPainter.paint(finish, sky, Prefs.luna(context), dpos[2], dp)
        scene = s
        invalidate()
    }

    fun stir(dir: Int) {
        scene?.stir(dir, false, false, SystemClock.uptimeMillis())
        invalidate()
    }

    /** Plays a fast fold with Concentrato as chosen, so it can be seen without flicking the phone. */
    fun flick() {
        scene?.stir(1, true, true, SystemClock.uptimeMillis(), Prefs.depth(context).grain, Prefs.linger(context).decay)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = scene ?: return
        val b = back ?: return
        val moving = s.step(SystemClock.uptimeMillis())
        painter.draw(canvas, s, finish, b, disc, dpos[0], dpos[1], max(1f, s.dp * 1.05f))
        if (moving) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = scene ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); s.touch(e.x, e.y, true) }
            MotionEvent.ACTION_MOVE -> s.touch(e.x, e.y, true)
            else -> { s.touch(e.x, e.y, false); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        invalidate()
        return true
    }
}

/** A choice shown as a painted picture of itself, the chosen one ringed in champagne. */
class PictureTile(
    c: Context,
    title: String,
    var chosen: Boolean,
    picHeightDp: Int,
    private val paintPicture: (Canvas, Int, Int) -> Unit,
    onPick: () -> Unit,
) : LinearLayout(c) {
    private var cache: Bitmap? = null
    private val name: TextView
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Kit.CHAMPAGNE }

    private val picture = object : View(c) {
        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val b = cache ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { paintPicture(Canvas(it), width, height); cache = it }
            canvas.drawBitmap(b, 0f, 0f, null)
            if (chosen) {
                val d = resources.displayMetrics.density
                ring.strokeWidth = 2f * d
                canvas.drawRoundRect(RectF(d, d, width - d, height - d), 15f * d, 15f * d, ring)
            }
        }
    }.apply { roundClip(16f) }

    init {
        orientation = VERTICAL
        addView(picture, LayoutParams(-1, c.px(picHeightDp)))
        name = c.text(title, 13f, if (chosen) Kit.CHAMPAGNE else Kit.MUTED, 500).apply { setPadding(c.px(2), c.px(9), 0, 0) }
        addView(name)
        setOnClickListener { onPick() }
    }

    fun refresh(isChosen: Boolean, repaint: Boolean) {
        chosen = isChosen
        name.setTextColor(if (chosen) Kit.CHAMPAGNE else Kit.MUTED)
        if (repaint) { cache?.recycle(); cache = null }
        picture.invalidate()
    }
}

/** A still of a style in a finish, as a miniature of the cover screen, for the tiles. */
fun paintStill(c: Context, cv: Canvas, w: Int, h: Int, style: Style, f: Finish) {
    val dp = c.resources.displayMetrics.density * .5f
    val rings = if (style == Style.TRONCO) Growth.sample(30) else FloatArray(0)
    val s = Scene(w, h, dp, style, rings, coverOverride = true, level = Power.read(c).first)
    val back = Backdrop.paint(s, f)
    val sky = Sky.now()
    val d = s.disc(sky)
    val disc = DiscPainter.paint(f, sky, Prefs.luna(c), d[2], dp)
    ScenePainter().draw(cv, s, f, back, disc, d[0], d[1], max(1f, dp * 1.05f * 1.6f))
    back.recycle(); disc.recycle()
}

/** A live view that keeps its own shape and is told how tall it may grow. */
interface Fits { var maxH: Int }

/**
 * Settings in every shape of the phone (12 Nov 2026). Upright on the cover: one scrolling column,
 * the live wallpaper and its main action first, the choices after. Anywhere wider (the cover
 * turned, the main screen either way round): the wallpaper stands in a pane of its own, as tall as
 * the screen allows, with its action beneath, and the choices scroll beside it. Either way the
 * wallpaper and the action are in view at rest; only the choices sit below the fold.
 */
class Stage(private val a: Activity, val screen: Screen) {
    val wide: Boolean = a.resources.configuration.let { it.screenWidthDp > it.screenHeightDp || it.smallestScreenWidthDp >= 600 }

    /** Holds the live view and what goes under it; in a wide shape it fits them to the pane's height. */
    val stage = object : LinearLayout(a) {
        override fun onMeasure(ws: Int, hs: Int) {
            if (MeasureSpec.getMode(hs) != MeasureSpec.UNSPECIFIED) {
                var used = paddingTop + paddingBottom
                var fits: Fits? = null
                for (i in 0 until childCount) {
                    val v = getChildAt(i)
                    if (v is Fits) { fits = v; continue }
                    if (v.visibility == GONE) continue
                    measureChild(v, ws, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    val lp = v.layoutParams as LayoutParams
                    used += v.measuredHeight + lp.topMargin + lp.bottomMargin
                }
                fits?.maxH = (MeasureSpec.getSize(hs) - used).coerceAtLeast(a.px(120))
            }
            super.onMeasure(ws, hs)
        }
    }.apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

    fun add(v: View, top: Int = 0, fill: Boolean = false) =
        stage.addView(v, LinearLayout.LayoutParams(if (fill) -1 else -2, -2).apply { topMargin = top; gravity = Gravity.CENTER_HORIZONTAL })

    /** Call once everything is added: returns the Activity's content. */
    fun build(): View {
        if (!wide) { screen.add(stage, a.px(6)); return screen.root }
        // The panes keep clear of the bars and the camera on every side, so nothing hides under them
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Kit.GROUND)
            setOnApplyWindowInsetsListener { v, i ->
                val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        stage.setPadding(a.px(24), a.px(20), a.px(8), a.px(20))
        stage.gravity = Gravity.CENTER
        row.addView(stage, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(screen.root, LinearLayout.LayoutParams(0, -1, 1f))
        return row
    }

    /**
     * Records where the live view and the main action ended up, for the launch test: both must be
     * in view at rest, in every shape. Written to files/layout.json once laid out.
     */
    fun record(root: View, live: View, action: View) {
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (live.height <= 0 || root.height <= 0) return
                fun box(v: View): String { val p = IntArray(2); v.getLocationInWindow(p); return "[${p[0]}, ${p[1]}, ${p[0] + v.width}, ${p[1] + v.height}]" }
                val inset = root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
                val json = "{\"wide\": $wide, \"view\": [${root.width}, ${root.height}], \"insets\": [${inset?.left ?: 0}, ${inset?.top ?: 0}, ${inset?.right ?: 0}, ${inset?.bottom ?: 0}], " +
                    "\"live\": ${box(live)}, \"action\": ${box(action)}}"
                runCatching { java.io.File(a.filesDir, "layout.json").writeText(json) }
            }
        })
    }
}

/**
 * Concentrato and its choices (13 Nov 2026), the same card in Solco's and Sipario's settings: the
 * switch, then, only while it is on (Giulietta), what counts as fast, how empty, how long it
 * lingers, and Show me, which plays a fast fold on the live wallpaper above.
 */
fun Activity.concentratoCard(note: String, show: () -> Unit): View {
    val a = this
    val card = Card(a)
    val more = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = if (Prefs.concentrato(a)) View.VISIBLE else View.GONE }
    card.row(Row(a, "Concentrato", note, Toggle(a, Prefs.concentrato(a)) { on ->
        Prefs.setConcentrato(a, on)
        (card.parent as? android.view.ViewGroup)?.let { android.transition.TransitionManager.beginDelayedTransition(it) }
        more.visibility = if (on) View.VISIBLE else View.GONE
    }))
    fun hairline() = more.addView(View(a).apply { setBackgroundColor(Kit.HAIRLINE) },
        LinearLayout.LayoutParams(-1, a.px(1)).apply { marginStart = a.px(18); marginEnd = a.px(18) })
    fun choice(title: String, sub: String, options: List<String>, selected: Int, picked: (Int) -> Unit) {
        hairline()
        more.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.px(18), a.px(14), a.px(18), a.px(16))
            addView(a.text(title, 16f, Kit.INK, 450))
            addView(a.text(sub, 13f, Kit.MUTED).apply { tag = com.cerniera.kit.SECONDARY }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.px(3) })
            addView(Segmented(a, options, selected, picked), LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.px(12) })
        })
    }
    choice("What counts as fast", "Your hinge reads a swing in two steps: brisk folds, or only the quickest flicks.",
        Trigger.values().map { it.title }, Prefs.trigger(a).ordinal) { Prefs.setTrigger(a, Trigger.values()[it]) }
    choice("How empty", "How much of the grain falls away. In Sipario, how much of the ornament.",
        Depth.values().map { it.title }, Prefs.depth(a).ordinal) { Prefs.setDepth(a, Depth.values()[it]) }
    choice("How long it lingers", "How slowly Solco's grain fills back in.",
        Linger.values().map { it.title }, Prefs.linger(a).ordinal) { Prefs.setLinger(a, Linger.values()[it]) }
    hairline()
    more.addView(FrameLayout(a).apply {
        setPadding(a.px(18), a.px(14), a.px(18), a.px(18))
        addView(a.primary("Show me", filled = false) { show() }, FrameLayout.LayoutParams(-1, -2))
    })
    card.addView(more, LinearLayout.LayoutParams(-1, -2))
    return card
}
