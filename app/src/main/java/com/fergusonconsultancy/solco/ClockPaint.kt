package com.fergusonconsultancy.solco

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.format.DateFormat
import com.cerniera.kit.Kit
import java.time.LocalTime

/**
 * Deserto's clock (25 Nov 2026): the time in Italiana, the family's face for the one large figure,
 * painted once a minute into an alpha mask the size of the clock's band, for [Scene] to fill with
 * grain. Hours over minutes on the cover, as One UI stacks them; one line on the main screen. Drawn
 * a little heavy, so a hairline face still holds grain.
 */
object ClockPaint {
    /** The time as Deserto shows it: 24-hour or 12-hour as the phone is set. */
    fun text(c: Context, lines: Int, t: LocalTime = LocalTime.now()): String {
        val hh = if (DateFormat.is24HourFormat(c)) "%02d".format(t.hour) else ((t.hour + 11) % 12 + 1).toString()
        val mm = "%02d".format(t.minute)
        return if (lines == 2) "$hh\n$mm" else "$hh:$mm"
    }

    fun mask(c: Context, w: Int, h: Int, dp: Float, cover: Boolean, text: String = text(c, if (cover) 2 else 1)): ClockMask {
        val lay = DesertoLayout(w, h, cover)
        val bh = (lay.height * 1.25f).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, bh, Bitmap.Config.ALPHA_8)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Kit.display(c)
            textSize = lay.digit * 1.32f
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 2.8f * dp
            textAlign = Paint.Align.CENTER
        }
        var y = 0f
        for (line in text.split("\n")) {
            // Each line set so its figures' tops sit on the line's top, as the layout expects
            val b = android.graphics.Rect()
            p.getTextBounds(line, 0, line.length, b)
            cv.drawText(line, w / 2f, y - b.top, p)
            y += lay.digit * 1.02f
        }
        val buf = java.nio.ByteBuffer.allocate(bmp.rowBytes * bh)
        bmp.copyPixelsToBuffer(buf)
        val rb = bmp.rowBytes
        val a = ByteArray(w * bh)
        val src = buf.array()
        for (j in 0 until bh) System.arraycopy(src, j * rb, a, j * w, w)
        bmp.recycle()
        return ClockMask(w, bh, lay.top, a)
    }
}
