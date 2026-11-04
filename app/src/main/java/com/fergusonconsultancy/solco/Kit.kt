package com.fergusonconsultancy.solco

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The family's palette and type, shared with Fold Motion, Fold Taskbar and Fold Widgets: smoked
 * green-black, ivory ink, champagne for what is chosen. Italiana names things; Jost, a geometric
 * sans in the Bauhaus line, does everything else.
 */
object Palette {
    const val GROUND = 0xFF0E1311.toInt()
    const val SURFACE = 0xFF151C18.toInt()
    const val RAISED = 0xFF1D2621.toInt()
    const val HAIRLINE = 0x1AF6F1E6
    const val IVORY = 0xFFF6F1E6.toInt()
    const val MUTED = 0xFF96A098.toInt()
    const val CHAMPAGNE = 0xFFD9BB86.toInt()
    const val GOOD = 0xFF7FC79A.toInt()
}

object Type {
    private var display: Typeface? = null
    private val jost = HashMap<Int, Typeface>()

    fun display(c: Context): Typeface = display ?: runCatching {
        Typeface.createFromAsset(c.assets, "fonts/Italiana-Regular.ttf")
    }.getOrDefault(Typeface.SERIF).also { display = it }

    /** Jost at a given weight (variable font), falling back to the system sans. */
    fun sans(c: Context, weight: Int = 400): Typeface = synchronized(jost) {
        jost.getOrPut(weight) {
            runCatching {
                Typeface.Builder(c.assets, "fonts/Jost.ttf").setFontVariationSettings("'wght' $weight").build()
            }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, weight, false)
        }
    }
}

/** An RGB colour with an alpha from 0 to 1. */
fun withAlpha(rgb: Int, a: Float): Int {
    val v = if (a.isNaN()) 0 else (a.coerceIn(0f, 1f) * 255f).roundToInt()
    return (v shl 24) or (rgb and 0xFFFFFF)
}

fun Context.px(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(fill); cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

fun Context.label(s: CharSequence, size: Float, colour: Int = Palette.IVORY, weight: Int = 400, display: Boolean = false) =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(colour)
        typeface = if (display) Type.display(context) else Type.sans(context, weight)
        includeFontPadding = false
        setLineSpacing(0f, 1.28f)
    }

/** Small tracked capitals in champagne that head a section. */
fun Context.eyebrow(s: String) = label(s.uppercase(), 11.5f, Palette.CHAMPAGNE, 500).apply {
    letterSpacing = 0.2f
    setPadding(px(4), px(30), 0, px(12))
}

/** A raised card holding rows. */
fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Palette.SURFACE, px(20).toFloat(), Palette.HAIRLINE, px(1))
    setPadding(px(18), px(6), px(18), px(6))
}

/** A row: a title and a note on the left, an action on the right. */
fun Context.row(title: String, note: String, action: View?): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, px(14), 0, px(14))
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 16f, Palette.IVORY, 500))
        addView(label(note, 13f, Palette.MUTED).apply { setPadding(0, px(4), 0, 0) })
    }, LinearLayout.LayoutParams(0, -2, 1f))
    if (action != null) addView(action, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14) })
}

/** The one main action of a screen: champagne on smoked glass. */
fun Context.button(text: String, primary: Boolean = true, onClick: () -> Unit) =
    label(text, 15f, if (primary) Palette.GROUND else Palette.IVORY, 500).apply {
        gravity = Gravity.CENTER
        background = if (primary) rounded(Palette.CHAMPAGNE, px(22).toFloat()) else rounded(Palette.RAISED, px(22).toFloat(), Palette.HAIRLINE, px(1))
        setPadding(px(18), px(14), px(18), px(14))
        setOnClickListener { onClick() }
    }

/** The screen: a scrolling column on the family's ground, no wider than a comfortable reading width. */
fun Context.screen(): Pair<ScrollView, LinearLayout> {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(40), px(20), px(48))
    }
    val scroll = ScrollView(this).apply {
        setBackgroundColor(Palette.GROUND)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(FrameLayout(context).apply {
            addView(column, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, px(620)), -2, Gravity.CENTER_HORIZONTAL))
        })
    }
    return scroll to column
}

/** An on/off switch in the family's colours: champagne when on, the thumb gliding on one crisp curve. */
class Toggle(c: Context, initial: Boolean, private val onChange: (Boolean) -> Unit) : View(c) {
    var on = initial
        private set
    private var t = if (initial) 1f else 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val argb = ArgbEvaluator()

    init {
        isClickable = true
        setOnClickListener {
            on = !on
            ValueAnimator.ofFloat(t, if (on) 1f else 0f).apply {
                duration = 220
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
            }.start()
            onChange(on)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(context.px(48), context.px(28))

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = argb.evaluate(t, Palette.RAISED, Palette.CHAMPAGNE) as Int
        canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        if (t < 0.5f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = context.px(1).toFloat(); p.color = 0x33F6F1E6
            canvas.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, h / 2, h / 2, p)
            p.style = Paint.Style.FILL
        }
        p.color = argb.evaluate(t, Palette.IVORY, Palette.GROUND) as Int
        canvas.drawCircle(h / 2 + (w - h) * t, h / 2, h / 2 - context.px(4), p)
    }
}
