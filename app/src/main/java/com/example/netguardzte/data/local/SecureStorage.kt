package com.example.netguardzte.data.local

import android.content.Context
import android.content.SharedPreferences

class SecureStorage(context: Context) {

    // بديل بسيط بدون EncryptedSharedPreferences
    // يمكنك تفعيل التشفير لاحقاً بعد إضافة dependency
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "netguard_prefs",
        Context.MODE_PRIVATE
    )

    fun saveCredentials(ip: String, username: String, password: String) {
        prefs.edit()
            .putString("router_ip", ip)
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    fun getRouterIp(): String {
        return prefs.getString("router_ip", "192.168.0.1") ?: "192.168.0.1"
    }

    fun getUsername(): String {
        return prefs.getString("username", "admin") ?: "admin"
    }

    fun getPassword(): String {
        return prefs.getString("password", "") ?: ""
    }

    fun isLoggedIn(): Boolean {
        return prefs.getBoolean("logged_in", false)
    }

    fun setLoggedIn(loggedIn: Boolean) {
        prefs.edit().putBoolean("logged_in", loggedIn).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
