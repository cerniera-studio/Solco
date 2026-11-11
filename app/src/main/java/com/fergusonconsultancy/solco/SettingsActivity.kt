package com.fergusonconsultancy.solco

import android.app.Activity
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Solco's one screen. The wallpaper live at the top, choices as painted tiles, two switches, and
 * one main action. Options appear only where they apply; diagnostics fold away at the foot.
 */
class SettingsActivity : Activity() {

    private lateinit var preview: PreviewView
    private val styleTiles = ArrayList<Pair<Style, PictureTile>>()
    private val finishTiles = ArrayList<Pair<Finish, PictureTile>>()
    private lateinit var styleNote: TextView
    private lateinit var troncoCard: LinearLayout
    private lateinit var troncoCount: TextView
    private lateinit var lunaNote: TextView
    private lateinit var action: TextView
    private lateinit var diag: TextView
    private lateinit var diagHead: TextView
    private lateinit var crashCard: LinearLayout
    private lateinit var crashText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (scroll, col) = screen()
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            col.setPadding(px(20), px(28) + bars.top, px(20), px(40) + bars.bottom)
            insets
        }

        col.addView(label("Solco", 46f, Palette.IVORY, display = true))
        col.addView(label("Grain and light that move only when something moves them.", 14.5f, Palette.MUTED).apply {
            setPadding(0, px(8), 0, px(22))
        })

        // A failure, if one was recorded: said plainly, with its report one tap from being shared
        crashText = label("", 13f, Palette.MUTED)
        crashCard = card().apply {
            setPadding(px(18), px(16), px(18), px(16))
            addView(label("Solco hit a problem", 16f, Palette.IVORY, 500))
            addView(crashText.apply { setPadding(0, px(6), 0, px(14)) })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("Copy report") { copyReport() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("Dismiss", primary = false) { Crash.clear(this@SettingsActivity); syncCrash() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(10) })
            })
        }
        col.addView(crashCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(20) })

        // The wallpaper, live, in this screen's own shape
        val bounds = windowManager.currentWindowMetrics.bounds
        val aspect = bounds.height().toFloat() / bounds.width().coerceAtLeast(1)
        preview = PreviewView(this, aspect)
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(preview, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        col.addView(label("Touch the grain.", 12.5f, Palette.MUTED).apply {
            gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, px(12), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        // Style: each one a different force
        col.addView(eyebrow("Style"))
        // Two rows of two: each style large enough to read its grain
        Style.values().toList().chunked(2).forEachIndexed { r, pair ->
            val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, s ->
                val t = PictureTile(this, s.title, Prefs.style(this) == s, 168,
                    { cv, w, h -> paintStill(this, cv, w, h, s, Prefs.finish(this)) }) { pickStyle(s) }
                styleTiles += s to t
                styleRow.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = px(12) })
            }
            col.addView(styleRow, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = px(14) })
        }
        styleNote = label(Prefs.style(this).note, 13.5f, Palette.MUTED).apply { setPadding(px(2), px(14), 0, 0) }
        col.addView(styleNote)

        // Tronco's record, only when Tronco is chosen
        troncoCount = label("", 13f, Palette.CHAMPAGNE, 500)
        troncoCard = card().apply {
            addView(row("Rings so far", "One a day, from the day Solco was first set. Today's is still growing.", troncoCount))
        }
        col.addView(troncoCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })

        // Finish
        col.addView(eyebrow("Finish"))
        // Two rows of three
        Finish.values().toList().chunked(3).forEachIndexed { r, trio ->
            val finishRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            trio.forEachIndexed { i, f ->
                val t = PictureTile(this, f.title, Prefs.finish(this) == f, 132,
                    { cv, w, h -> paintStill(this, cv, w, h, Prefs.style(this), f) }) { pickFinish(f) }
                finishTiles += f to t
                finishRow.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = px(10) })
            }
            col.addView(finishRow, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = px(12) })
        }

        // Light and motion
        col.addView(eyebrow("Light and motion"))
        lunaNote = label("", 13f, Palette.MUTED)
        col.addView(card().apply {
            addView(row("True moon phase", "At night the disc shows tonight's moon instead of a full circle.",
                Toggle(this@SettingsActivity, Prefs.luna(this@SettingsActivity)) { Prefs.setLuna(this@SettingsActivity, it); repaintAll() }))
            addView(View(this@SettingsActivity).apply { setBackgroundColor(Palette.HAIRLINE) }, LinearLayout.LayoutParams(-1, px(1)))
            addView(row("Concentrato", "A fast fold leaves the bands emptier for a moment, then they fill back in.",
                Toggle(this@SettingsActivity, Prefs.concentrato(this@SettingsActivity)) { Prefs.setConcentrato(this@SettingsActivity, it) }))
        })
        col.addView(lunaNote.apply { setPadding(px(4), px(10), 0, 0) })

        // The one main action
        action = button("Set as wallpaper") { setWallpaper() }
        col.addView(action, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(30) })

        // Sipario, the second wallpaper in this app
        col.addView(eyebrow("Also here"))
        col.addView(card().apply {
            addView(row("Sipario", "Five scenes. Folded, a quiet one; unfold, and the rest of the story.",
                label("Open", 14f, Palette.CHAMPAGNE, 500)))
            setOnClickListener { startActivity(Intent(this@SettingsActivity, SiparioActivity::class.java)) }
        })

        // Diagnostics, folded away
        val head = eyebrow("Diagnostics  +").apply { setTextColor(Palette.MUTED) }
        diagHead = head
        diag = label("", 12.5f, Palette.MUTED).apply { visibility = View.GONE; setPadding(px(4), 0, 0, 0) }
        head.setOnClickListener {
            diag.visibility = if (diag.visibility == View.GONE) View.VISIBLE else View.GONE
            head.text = if (diag.visibility == View.GONE) "DIAGNOSTICS  +" else "DIAGNOSTICS  −"
            fillDiag()
        }
        col.addView(head)
        col.addView(diag)

        setContentView(scroll)
        window.decorView.setBackgroundColor(Palette.GROUND)
        syncTronco()
    }

    override fun onResume() {
        super.onResume()
        val ours = runCatching { WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName }.getOrDefault(false)
        action.text = if (ours) "Solco is your wallpaper" else "Set as wallpaper"
        action.background = if (ours) rounded(Palette.RAISED, px(22).toFloat(), Palette.HAIRLINE, px(1)) else rounded(Palette.CHAMPAGNE, px(22).toFloat())
        action.setTextColor(if (ours) Palette.IVORY else Palette.GROUND)
        val sky = Sky.now()
        lunaNote.text = if (sky.day) "Tonight: ${Sky.phaseName(Sky.moonPhase(System.currentTimeMillis() + 12 * 3_600_000L))}."
        else "Now: ${Sky.phaseName(sky.phase)}."
        syncTronco()
        fillDiag()
        syncCrash()
    }

    private val version by lazy { runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?" }

    private fun syncCrash() {
        // A report from an earlier build describes a fault that build may already have fixed
        Crash.report(this)?.let { if (!it.startsWith("Solco $version\n")) Crash.clear(this) }
        val r = Crash.report(this)
        crashCard.visibility = if (r == null) View.GONE else View.VISIBLE
        if (r != null) {
            crashText.text = r.lines().getOrNull(2)?.let { "$it. Copy the report and send it over, then try again." } ?: "Copy the report and send it over."
            // Diagnostics open themselves when something went wrong
            diag.visibility = View.VISIBLE
            diagHead.text = "DIAGNOSTICS  \u2212"
            fillDiag()
        }
    }

    private fun copyReport() {
        val text = (Crash.report(this) ?: "") + "\n\n" + diag.text
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Solco report", text))
    }

    private fun pickStyle(s: Style) {
        if (Prefs.style(this) == s) return
        Prefs.setStyle(this, s)
        styleTiles.forEach { (k, t) -> t.refresh(k == s, false) }
        finishTiles.forEach { (_, t) -> t.refresh(t.chosen, true) }
        styleNote.text = s.note
        syncTronco()
        preview.rebuild(); preview.stir(1)
    }

    private fun pickFinish(f: Finish) {
        if (Prefs.finish(this) == f) return
        Prefs.setFinish(this, f)
        finishTiles.forEach { (k, t) -> t.refresh(k == f, false) }
        styleTiles.forEach { (_, t) -> t.refresh(t.chosen, true) }
        preview.rebuild()
    }

    private fun repaintAll() {
        styleTiles.forEach { (_, t) -> t.refresh(t.chosen, true) }
        finishTiles.forEach { (_, t) -> t.refresh(t.chosen, true) }
        preview.rebuild()
    }

    private fun syncTronco() {
        val on = Prefs.style(this) == Style.TRONCO
        troncoCard.visibility = if (on) View.VISIBLE else View.GONE
        if (on) { val n = Growth.days(this); troncoCount.text = if (n == 1) "1 day" else "$n days" }
    }

    private fun fillDiag() {
        val p = Prefs.of(this)
        val hinge = p.contains("hinge_sensor").let { has ->
            if (!has) (getSystemService(SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null
            else p.getBoolean("hinge_sensor", false)
        }
        diag.text = listOf(
            "Cover screen: " + (p.getString("seen_cover", null) ?: "not drawn yet"),
            "Main screen: " + (p.getString("seen_main", null) ?: "not drawn yet"),
            "Hinge sensor: " + if (hinge) "present" else "absent",
            "Last fold: " + (p.getString("last_fold", null) ?: "none yet"),
            "Drawn on the cover: " + (Crash.get(this, "drew_cover") ?: "never"),
            "Drawn on the main screen: " + (Crash.get(this, "drew_main") ?: "never"),
            "GPU: " + (Crash.get(this, "gpu") ?: "not seen yet"),
            "GPU problem: " + (Crash.get(this, "gl_error") ?: "none"),
            "Tronco record began: " + Growth.start(this),
            "Version: $version",
        ).joinToString("\n")
    }

    private fun setWallpaper() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, SolcoWallpaperService::class.java)))
        } catch (e: Exception) {
            runCatching { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
        }
    }
}
