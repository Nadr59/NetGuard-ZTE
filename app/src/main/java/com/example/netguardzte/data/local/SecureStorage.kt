package com.example.netguardzte.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "netguard_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(routerIp: String, username: String, password: String) {
        prefs.edit()
            .putString(KEY_ROUTER_IP, routerIp)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .putBoolean(KEY_LOGGED_IN, false)
            .apply()
    }

    fun getRouterIp(): String = prefs.getString(KEY_ROUTER_IP, "192.168.0.1") ?: "192.168.0.1"
    fun getUsername(): String = prefs.getString(KEY_USERNAME, "admin") ?: "admin"
    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""

    fun setLoggedIn(loggedIn: Boolean) {
        prefs.edit().putBoolean(KEY_LOGGED_IN, loggedIn).apply()
    }

    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    fun hasCredentials(): Boolean = prefs.contains(KEY_PASSWORD) && getPassword().isNotEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ROUTER_IP = "router_ip"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LOGGED_IN = "is_logged_in"
    }
}
