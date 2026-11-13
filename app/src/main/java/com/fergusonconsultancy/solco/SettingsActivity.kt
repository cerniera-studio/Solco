package com.fergusonconsultancy.solco

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import com.cerniera.kit.endValue
import com.cerniera.kit.eyebrow
import com.cerniera.kit.primary
import com.cerniera.kit.px
import com.cerniera.kit.rounded
import com.cerniera.kit.text

/**
 * Solco's one screen, on the Kit. The wallpaper live with its one main action, choices as painted
 * tiles, two switches. Options appear only where they apply; diagnostics and the doctrine fold
 * away at the foot. Laid out for every shape of the phone by [Stage].
 */
class SettingsActivity : Activity() {

    private lateinit var preview: PreviewView
    private val styleTiles = ArrayList<Pair<Style, PictureTile>>()
    private val finishTiles = ArrayList<Pair<Finish, PictureTile>>()
    private lateinit var styleNote: TextView
    private lateinit var troncoCard: View
    private lateinit var troncoCount: TextView
    private lateinit var lunaNote: TextView
    private lateinit var action: TextView
    private lateinit var diag: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A crash from the last run is shown first, with Share, Copy and Try again
        if (Crash.show(this, "Solco")) return
        // Solco's settings stay in Visconti, whatever the phone's light: the wallpaper is its own light
        Kit.dark = true

        val screen = Screen(this, "Solco", "Live wallpaper")
        screen.add(text("Grain and light that move only when something moves them.", 14.5f, Kit.MUTED).apply {
            setPadding(px(4), px(8), 0, px(14))
        })
        val stage = Stage(this, screen)

        // The wallpaper, live, in this screen's own shape (turned, if the screen is)
        val bounds = windowManager.currentWindowMetrics.bounds
        val aspect = bounds.height().toFloat() / bounds.width().coerceAtLeast(1)
        preview = PreviewView(this, aspect, display?.rotation ?: 0)
        stage.add(preview)
        stage.add(text("Touch the grain.", 12.5f, Kit.MUTED).apply { gravity = Gravity.CENTER_HORIZONTAL }, px(12))
        // The one main action, under the wallpaper it sets, so it is in reach in every shape
        action = primary("Set as wallpaper") { setWallpaper() }
        stage.add(action, px(16), fill = true)
        val root = stage.build()

        // Style: each one a different force. Two rows of two, each large enough to read its grain
        screen.section("Style")
        Style.values().toList().chunked(2).forEachIndexed { r, pair ->
            val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, s ->
                val t = PictureTile(this, s.title, Prefs.style(this) == s, 168,
                    { cv, w, h -> paintStill(this, cv, w, h, s, Prefs.finish(this)) }) { pickStyle(s) }
                styleTiles += s to t
                styleRow.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = px(12) })
            }
            screen.add(styleRow, if (r > 0) px(14) else 0)
        }
        styleNote = text(Prefs.style(this).note, 13.5f, Kit.MUTED).apply { setPadding(px(2), px(14), 0, 0) }
        screen.add(styleNote)

        // Tronco's record, only when Tronco is chosen
        troncoCount = endValue("", Kit.CHAMPAGNE)
        troncoCard = Card(this).apply {
            row(Row(this@SettingsActivity, "Rings so far", "One a day, from the day Solco was first set. Today's is still growing.", troncoCount))
        }
        screen.add(troncoCard, px(16))

        // Finish: two rows of three
        screen.section("Finish")
        Finish.values().toList().chunked(3).forEachIndexed { r, trio ->
            val finishRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            trio.forEachIndexed { i, f ->
                val t = PictureTile(this, f.title, Prefs.finish(this) == f, 132,
                    { cv, w, h -> paintStill(this, cv, w, h, Prefs.style(this), f) }) { pickFinish(f) }
                finishTiles += f to t
                finishRow.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = px(10) })
            }
            screen.add(finishRow, if (r > 0) px(12) else 0)
        }

        // Light and motion
        screen.section("Light and motion")
        lunaNote = text("", 13f, Kit.MUTED)
        screen.add(Card(this).apply {
            row(Row(this@SettingsActivity, "True moon phase", "At night the disc shows tonight's moon instead of a full circle.",
                Toggle(this@SettingsActivity, Prefs.luna(this@SettingsActivity)) { Prefs.setLuna(this@SettingsActivity, it); repaintAll() }))
        })
        screen.add(lunaNote.apply { setPadding(px(4), px(10), 0, 0) })
        // Concentrato and its choices; Show me plays a fast fold on the wallpaper above
        screen.add(concentratoCard("A fast fold leaves the bands emptier for a moment, then they fill back in.") { showFlick() }, px(16))

        // Sipario, the second wallpaper in this app
        screen.section("Also here")
        screen.add(Card(this).apply {
            row(Row(this@SettingsActivity, "Sipario", "Five scenes. Folded, a quiet one; unfold, and the rest of the story.",
                endValue("Open", Kit.CHAMPAGNE)) { startActivity(Intent(this@SettingsActivity, SiparioActivity::class.java)) })
        })

        // Diagnostics and the doctrine, folded away
        diag = text("", 12.5f, Kit.MUTED)
        screen.add(Fold(this, "Diagnostics", "What the wallpaper has seen on this phone").apply { body.addView(diag) }, px(30))
        screen.add(doctrineCard(DOCTRINE_NOTES), px(12))

        setContentView(root)
        window.decorView.setBackgroundColor(Kit.GROUND)
        stage.record(root, preview, action)
        syncTronco()
    }

    override fun onResume() {
        super.onResume()
        if (!::action.isInitialized) return
        val ours = runCatching { WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName }.getOrDefault(false)
        action.text = if (ours) "Solco is your wallpaper" else "Set as wallpaper"
        action.background = if (ours) rounded(0, px(28).toFloat(), Kit.HAIRLINE, px(1)) else rounded(Kit.CHAMPAGNE, px(28).toFloat())
        action.setTextColor(if (ours) Kit.INK else Kit.GROUND)
        val sky = Sky.now()
        lunaNote.text = if (sky.day) "Tonight: ${Sky.phaseName(Sky.moonPhase(System.currentTimeMillis() + 12 * 3_600_000L))}."
        else "Now: ${Sky.phaseName(sky.phase)}."
        syncTronco()
        fillDiag()
    }

    private val version by lazy { runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?" }

    /** Plays a fast fold on the live wallpaper, scrolling it into view first when the page has moved past it. */
    private fun showFlick() {
        var v: View = preview
        var top = 0
        while (v.parent is View && v.parent !is android.widget.ScrollView) { top += v.top; v = v.parent as View }
        (v.parent as? android.widget.ScrollView)?.let { sv -> if (sv.scrollY > top) { sv.smoothScrollTo(0, (top - px(24)).coerceAtLeast(0)); preview.postDelayed({ preview.flick() }, 380); return } }
        preview.flick()
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
            "Fold log (newest first):\n" + (p.getString("fold_log", null) ?: "nothing yet"),
            "Drawn on the cover: " + (Gpu.get(this, "drew_cover") ?: "never"),
            "Drawn on the main screen: " + (Gpu.get(this, "drew_main") ?: "never"),
            "GPU: " + (Gpu.get(this, "gpu") ?: "not seen yet"),
            "GPU problem: " + (Gpu.get(this, "gl_error") ?: "none"),
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

    companion object {
        /** How Solco answers each principle, under the doctrine at the foot of Settings. */
        val DOCTRINE_NOTES = listOf(
            "The monolithic membrane" to "The grain is one body: a finger pushes the grain under it, and the grain around it yields and follows, then everything springs home.",
            "Concentrato" to "A fast fold leaves the bands emptier for a moment, so only the swing's trajectory reads; they fill back in as the grain settles.",
            "Giulietta" to "Nothing on the wallpaper is a control. The sun or moon, the battery's tide and the day's ring are read from the grain itself.",
            "Nothing moves at rest" to "Frames are drawn only when a fold, a finger, the minute, the battery or a tilt moves something.",
        )
    }
}
