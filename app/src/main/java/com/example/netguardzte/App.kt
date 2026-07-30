package com.example.netguardzte

import android.app.Application
import com.example.netguardzte.data.api.RouterCommandExecutor

class App : Application() {

    companion object {
        @JvmStatic
        var lastCrashText: String = ""
    }

    lateinit var commandExecutor: RouterCommandExecutor
        private set

    override fun onCreate() {
        super.onCreate()
        commandExecutor = RouterCommandExecutor(this)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            android.util.Log.e("CRASH", throwable.message, throwable)
        }
    }
}
