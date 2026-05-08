package com.dmahony.e220chat

import android.app.Application

class E220ChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        E220NotificationManager.createChannels(this)
    }
}
