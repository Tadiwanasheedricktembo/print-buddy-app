package com.tadiwaprintbuddy.app

import android.app.Application

class TadiwaPrintBuddyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager(this).applyTheme()
    }
}
