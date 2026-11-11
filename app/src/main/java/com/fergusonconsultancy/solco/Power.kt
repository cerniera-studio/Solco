package com.fergusonconsultancy.solco

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** The battery, for Marea: its level from 0 to 1 and whether it is charging. */
object Power {
    fun read(c: Context): Pair<Float, Boolean> {
        val i = runCatching { c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return 1f to false
        return from(i)
    }

    fun from(i: Intent): Pair<Float, Boolean> {
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val level = if (lvl < 0 || scale <= 0) 1f else lvl / scale.toFloat()
        return level to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL && i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }
}
