package com.fergusonconsultancy.solco

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
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
 * near square on the main screen. Touch moves the grain exactly as on the home screen.
 */
class PreviewView(c: Context, private val aspect: Float) : View(c) {
    private var scene: Scene? = null
    private var back: Bitmap? = null
    private var disc: Bitmap? = null
    private var dpos = FloatArray(3)
    private var finish = Finish.VISCONTI
    private val painter = ScenePainter()

    init { roundClip(22f) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        val hh = min(context.px(460).toFloat(), avail * aspect)
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
        val s = Scene(width, height, dp, style, if (style == Style.TRONCO) Growth.rings(context) else FloatArray(0), level = lvl, charging = chg)
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
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.CHAMPAGNE }

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
        name = c.label(title, 13f, if (chosen) Palette.CHAMPAGNE else Palette.MUTED, 500).apply { setPadding(c.px(2), c.px(9), 0, 0) }
        addView(name)
        setOnClickListener { onPick() }
    }

    fun refresh(isChosen: Boolean, repaint: Boolean) {
        chosen = isChosen
        name.setTextColor(if (chosen) Palette.CHAMPAGNE else Palette.MUTED)
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
