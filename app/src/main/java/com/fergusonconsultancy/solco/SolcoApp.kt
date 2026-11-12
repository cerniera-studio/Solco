package com.fergusonconsultancy.solco

import android.app.Application
import com.cerniera.kit.Crash

/** Keeps a record of any crash (through the Kit), shown on the next open of Settings with Share and Copy. */
class SolcoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this, "Solco")
    }
}
