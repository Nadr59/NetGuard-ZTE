package com.example.netguardzte

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd_HH-mm-ss", Locale.US
                ).format(Date())

                val crashDir = File(getExternalFilesDir(null), "crashes")
                crashDir.mkdirs()
                val crashFile = File(crashDir, "crash_$timestamp.txt")

                val writer = PrintWriter(crashFile)
                writer.println("Time: $timestamp")
                writer.println("Exception: ${throwable.javaClass.name}")
                writer.println("Message: ${throwable.message}")
                writer.println("\nStack Trace:")
                throwable.printStackTrace(writer)

                var cause = throwable.cause
                var depth = 0
                while (cause != null && depth < 10) {
                    writer.println("\nCaused by: ${cause.javaClass.name}")
                    writer.println("Message: ${cause.message}")
                    cause.printStackTrace(writer)
                    cause = cause.cause
                    depth++
                }
                writer.close()

                val prefs = getSharedPreferences("crash_log", MODE_PRIVATE)
                prefs.edit().putString("last_crash", crashFile.readText()).apply()
            } catch (_: Exception) {}

            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
