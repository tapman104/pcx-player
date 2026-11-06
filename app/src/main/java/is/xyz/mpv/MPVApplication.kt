package `is`.xyz.mpv

import android.app.Application

class MPVApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
    }
}

