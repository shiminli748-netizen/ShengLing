package com.shengling.app

import android.app.Application

class ShengLingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: ShengLingApp
            private set
    }
}
