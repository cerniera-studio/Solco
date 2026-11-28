package com.fergusonconsultancy.solco

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.opengl.GLES20
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * A still of what Solco draws, for the family's glass (27 Nov 2026).
 *
 * Android gives no app a picture of a live wallpaper, so over Solco the widgets' glass finishes had
 * nothing to frost or read: Vetro, Cristallo and Goccia looked alike, and words could lose their
 * contrast where a ring of light grain passed behind them (Mechack's Dorso screenshots). Solco now
 * reads back a frame now and then (after the scene is built, then at most every ten minutes per
 * screen), keeps it at 1080 px on its longest side, and shares it with Cerniera's apps through
 * [StillProvider]. Fold Widgets is told, so it and its followers redraw over the new still.
 *
 * Never while locked or in the preview.
 */
object Still {
    private const val EVERY_MS = 10 * 60_000L
    private val last = HashMap<Boolean, Long>()
    private val io = Executors.newSingleThreadExecutor()

    fun file(c: Context, cover: Boolean) = File(c.filesDir, "still-${if (cover) "cover" else "main"}.png")
    private fun prefs(c: Context) = c.getSharedPreferences("solco_still", Context.MODE_PRIVATE)
    fun stamp(c: Context, cover: Boolean) = prefs(c).getLong(if (cover) "cover" else "main", 0L)

    /** Called on the GL thread after a frame is drawn. [fresh]: the scene was just built. */
    fun maybe(c: Context, w: Int, h: Int, cover: Boolean, fresh: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val prev = last[cover]
        if (!fresh && prev != null && now - prev < EVERY_MS) return
        last[cover] = now
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val app = c.applicationContext
        io.execute {
            runCatching {
                buf.rewind()
                val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf) }
                // GL reads bottom row first: flip, and shrink to 1080 px on the longest side
                val k = minOf(1f, 1080f / maxOf(w, h))
                val still = Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { setScale(k, -k) }, true)
                raw.recycle()
                val f = file(app, cover); val tmp = File(app.filesDir, "still.tmp")
                tmp.outputStream().use { still.compress(Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(f)
                prefs(app).edit().putLong(if (cover) "cover" else "main", System.currentTimeMillis()).apply()
                // Fold Widgets redraws over it and tells its followers
                app.sendBroadcast(Intent("com.cerniera.SOLCO_STILL").setPackage("com.fergusonconsultancy.foldwidgets"))
                android.util.Log.i("Solco", "still saved for the ${if (cover) "cover" else "main"} screen, ${still.width}x${still.height}")
            }.onFailure { android.util.Log.w("Solco", "still not saved", it) }
        }
    }
}

/** content://com.fergusonconsultancy.solco.still: /still (the versions), /still/main and /still/cover (the pictures). Family only. */
class StillProvider : ContentProvider() {
    override fun onCreate() = true

    private fun family() {
        val p = callingPackage
        if (p != context?.packageName && !com.cerniera.kit.FamilyRules.isFamily(p)) throw SecurityException("Shared with Cerniera's apps only")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        family()
        return MatrixCursor(arrayOf("main", "cover")).apply {
            addRow(arrayOf<Any>(if (Still.file(c, false).exists()) Still.stamp(c, false) else 0L, if (Still.file(c, true).exists()) Still.stamp(c, true) else 0L))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        family()
        val f = when (uri.lastPathSegment) { "main" -> Still.file(c, false); "cover" -> Still.file(c, true); else -> return null }
        return if (f.exists()) ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) else null
    }

    override fun getType(uri: Uri) = "image/png"
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
