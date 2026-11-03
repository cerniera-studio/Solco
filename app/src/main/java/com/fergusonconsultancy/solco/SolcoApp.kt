package com.fergusonconsultancy.solco

import android.app.Application

/** Keeps a record of any crash, so it can be read and shared from Settings on the phone. */
class SolcoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
    }
}
