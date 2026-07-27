package com.example.netguardzte

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class App : Application() {
    companion object {
        var lastCrashText: String = ""
    }

    override fun onCreate() {
        super.onCreate()

        // احتفظ بآخر خطأ في الذاكرة أيضاً
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val trace = sw.toString()

                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.US
                ).format(Date())

                val fullCrash = buildString {
                    appendLine("=== CRASH ===")
                    appendLine("Time: $timestamp")
                    appendLine("Exception: ${throwable.javaClass.name}")
                    appendLine("Message: ${throwable.message}")
                    appendLine()
                    appendLine(trace)

                    var cause = throwable.cause
                    var depth = 0
                    while (cause != null && depth < 5) {
                        appendLine()
                        appendLine("Caused by: ${cause.javaClass.name}")
                        appendLine("Message: ${cause.message}")
                        val sw2 = StringWriter()
                        cause.printStackTrace(PrintWriter(sw2))
                        appendLine(sw2.toString())
                        cause = cause.cause
                        depth++
                    }
                }

                // احفظ في الذاكرة
                lastCrashText = fullCrash

                // احفظ في SharedPreferences بشكل متزامن
                val prefs = getSharedPreferences("crash_log", MODE_PRIVATE)
                prefs.edit()
                    .putString("last_crash", fullCrash)
                    .commit() // commit() متزامن - يضمن الحفظ قبل الموت

                // احفظ في ملف
                try {
                    val dir = getExternalFilesDir(null)
                    if (dir != null) {
                        val file = java.io.File(dir, "last_crash.txt")
                        file.writeText(fullCrash)
                    }
                } catch (_: Exception) {}

            } catch (_: Exception) {}

            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
