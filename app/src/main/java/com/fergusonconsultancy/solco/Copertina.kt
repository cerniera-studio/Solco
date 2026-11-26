package com.fergusonconsultancy.solco

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.cerniera.kit.Card
import com.cerniera.kit.Row
import com.cerniera.kit.Segmented
import com.cerniera.kit.primary
import com.cerniera.kit.px

/** How strongly the sleeve's colours take the sky (26 Nov 2026). */
enum class Strength(val title: String, val amount: Float) {
    OFF("Off", 0f),
    TOUCH("A touch", .35f),
    HALF("Half", .65f),
    FULL("Full", 1f),
}

/**
 * Copertina (26 Nov 2026, from App Ideas 26 Nov): while music plays, the grain and the disc borrow
 * the colours of the sleeve, and go back to the finish's own when it stops. Fold Widgets reads the
 * artwork (it holds the player's session for the music widget) and shares two colours at
 * content://com.cerniera.copertina/now; Solco reads them there when told they changed, and on each
 * refresh in case it was asleep. Only colours cross: no title, no artwork, no player controls.
 *
 * Each grain keeps its finish's lightness and takes the sleeve's hue, so the grain stays legible on
 * the ground whatever the record. Step 1 (claude/copertina-design.md) judges the colours; the dye
 * spreading out from the sun comes once they are right.
 */
object Copertina {
    private val URI: Uri = Uri.parse("content://com.cerniera.copertina/now")
    const val CHANGED = "com.cerniera.COPERTINA_CHANGED"

    /** The sleeve's two colours now (0xRRGGBB), or null for the finish's own. Set by [read]. */
    @Volatile var colours: IntArray? = null
        private set
    @Volatile var amount = 0f
        private set

    /** Show me in Settings: a sample sleeve on the preview, until [until]. */
    @Volatile var sample: IntArray? = null
    @Volatile var until = 0L

    fun strength(c: Context) = runCatching { Strength.valueOf(Prefs.of(c).getString("copertina", null) ?: "HALF") }.getOrDefault(Strength.HALF)
    fun setStrength(c: Context, s: Strength) = Prefs.of(c).edit().putString("copertina", s.name).apply()

    /** What Fold Widgets shares: the two colours while something with coloured artwork plays. */
    private fun shared(c: Context): IntArray? = runCatching {
        c.contentResolver.query(URI, null, null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) return@use null
            if (cur.getInt(cur.getColumnIndexOrThrow("playing")) != 1) return@use null
            val ia = cur.getColumnIndexOrThrow("a"); val ib = cur.getColumnIndexOrThrow("b")
            if (cur.isNull(ia)) return@use null
            val a = cur.getInt(ia)
            intArrayOf(a, if (cur.isNull(ib)) a else cur.getInt(ib))
        }
    }.getOrNull()   // Fold Widgets not installed, an older build, or no access yet: the finish's own

    /** Reads the colours and strength for the next build. Returns a key that changes when either does. */
    fun read(c: Context): String {
        val s = sample?.takeIf { System.currentTimeMillis() < until }
        val k = strength(c)
        colours = if (k == Strength.OFF && s == null) null else s ?: shared(c)
        amount = if (s != null) maxOf(k.amount, Strength.HALF.amount) else k.amount
        return colours?.let { "%06X%06X@%.2f".format(it[0], it[1], amount) } ?: ""
    }

    /**
     * Grain colour [i] of [f], tinted when a sleeve is in. The third grain colour takes the second
     * colour: it is the darker one in most finishes, where a hue shows best (the palest grain stays
     * near ivory whatever the hue, as it should).
     */
    fun grain(f: Finish, i: Int): Int {
        val p = colours ?: return f.grain[i]
        return CopertinaRules.tint(f.grain[i], p[if (i == 2) 1 else 0], amount)
    }

    /** The disc's colour, tinted by the sleeve's first colour. */
    fun disc(f: Finish, day: Boolean): Int {
        val base = if (day) f.discDay else f.discNight
        val p = colours ?: return base
        return CopertinaRules.tint(base, p[0], amount * .8f)
    }

    /** Five made-up sleeves for Show me: a spread of what records look like, not any one record. */
    val SAMPLES = listOf(
        intArrayOf(0x2E4A8C, 0xE0A040),   // night blue, amber
        intArrayOf(0xE0407A, 0x7A4AE0),   // rose, violet
        intArrayOf(0xE07830, 0x2A8C8C),   // desert orange, teal
        intArrayOf(0x3C7A3C, 0xC8B060),   // forest, straw
        intArrayOf(0xC02030, 0x5A1018),   // crimson, oxblood: a one-colour sleeve
    )
}

/**
 * The colour rule, free of Android so it can be checked off the phone: take the sleeve's hue and a
 * moderated saturation at the grain's own lightness, then go [amount] of the way there.
 */
object CopertinaRules {
    fun tint(base: Int, sleeve: Int, amount: Float): Int {
        if (amount <= 0f) return base
        val b = hsl(base); val s = hsl(sleeve)
        val target = rgb(s[0], s[1].coerceIn(.28f, .78f), b[2])
        val t = amount.coerceIn(0f, 1f)
        fun ch(c: Int, sh: Int) = (c shr sh) and 0xFF
        fun mix(sh: Int) = (ch(base, sh) + (ch(target, sh) - ch(base, sh)) * t).toInt().coerceIn(0, 255)
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** 0xRRGGBB to hue (0..360), saturation and lightness (0..1). */
    fun hsl(c: Int): FloatArray {
        val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2f; val d = mx - mn
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - Math.abs(2f * l - 1f))
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, s, l)
    }

    fun rgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val x = c * (1f - Math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun k(v: Float) = ((v + m) * 255f + .5f).toInt().coerceIn(0, 255)
        return (k(r) shl 16) or (k(g) shl 8) or k(b)
    }
}

/** Settings → Copertina: how strong, and Show me (five made-up sleeves on the preview, in turn). */
fun Activity.copertinaCard(show: () -> Unit): View {
    val a = this
    val card = Card(a)
    card.row(Row(a, "Copertina", "While music plays, the grain and the sun take the colours of the sleeve, then return to the finish when it stops. The colours come from Fold Widgets, which reads the artwork.", null))
    card.addView(LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.px(18), 0, a.px(18), a.px(4))
        addView(Segmented(a, Strength.values().map { it.title }, Copertina.strength(a).ordinal) { Copertina.setStrength(a, Strength.values()[it]) })
    }, LinearLayout.LayoutParams(-1, -2))
    card.addView(FrameLayout(a).apply {
        setPadding(a.px(18), a.px(14), a.px(18), a.px(18))
        addView(a.primary("Show me", filled = false) { show() }, FrameLayout.LayoutParams(-1, -2))
    }, LinearLayout.LayoutParams(-1, -2))
    return card
}
