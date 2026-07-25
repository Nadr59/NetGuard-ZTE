  #!/bin/bash
echo "📁 إنشاء مشروع NetGuard ZTE..."

# ═══════════════════════════════════════
# إنشاء المجلدات
# ═══════════════════════════════════════
mkdir -p app/src/main/java/com/example/netguardzte/data/api/models
mkdir -p app/src/main/java/com/example/netguardzte/data/repository
mkdir -p app/src/main/java/com/example/netguardzte/data/local
mkdir -p app/src/main/java/com/example/netguardzte/domain/model
mkdir -p app/src/main/java/com/example/netguardzte/ui/screens/login
mkdir -p app/src/main/java/com/example/netguardzte/ui/screens/devices
mkdir -p app/src/main/java/com/example/netguardzte/ui/screens/blocked
mkdir -p app/src/main/java/com/example/netguardzte/ui/screens/settings
mkdir -p app/src/main/java/com/example/netguardzte/ui/components
mkdir -p app/src/main/java/com/example/netguardzte/ui/theme
mkdir -p app/src/main/java/com/example/netguardzte/ui/viewmodel
mkdir -p app/src/main/res/values
mkdir -p app/src/main/res/xml
mkdir -p gradle/wrapper
mkdir -p .github/workflows

echo "✅ المجلدات"

# ═══════════════════════════════════════
# build.gradle.kts (Project)
# ═══════════════════════════════════════
cat > build.gradle.kts << 'EOF'
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
EOF

# ═══════════════════════════════════════
# settings.gradle.kts
# ═══════════════════════════════════════
cat > settings.gradle.kts << 'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "NetGuardZTE"
include(":app")
EOF

# ═══════════════════════════════════════
# gradle.properties
# ═══════════════════════════════════════
cat > gradle.properties << 'EOF'
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
EOF

# ═══════════════════════════════════════
# gradle-wrapper.properties
# ═══════════════════════════════════════
cat > gradle/wrapper/gradle-wrapper.properties << 'EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF

# ═══════════════════════════════════════
# .gitignore
# ═══════════════════════════════════════
cat > .gitignore << 'EOF'
.gradle/
build/
*.iml
local.properties
.DS_Store
EOF

# ═══════════════════════════════════════
# .github/workflows/build.yml
# ═══════════════════════════════════════
cat > .github/workflows/build.yml << 'EOF'
name: Build APK
on:
  push:
    branches: [ main ]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v3
      - run: gradle wrapper --gradle-version 8.5
      - run: ./gradlew assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: netguard-zte-debug
          path: app/build/outputs/apk/debug/*.apk
EOF

# ═══════════════════════════════════════
# app/build.gradle.kts
# ═══════════════════════════════════════
cat > app/build.gradle.kts << 'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.netguardzte"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.netguardzte"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Retrofit + OkHttp + Gson
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
EOF

# ═══════════════════════════════════════
# network_security_config.xml
# ═══════════════════════════════════════
cat > app/src/main/res/xml/network_security_config.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">192.168.0.1</domain>
        <domain includeSubdomains="true">192.168.1.1</domain>
        <domain includeSubdomains="true">192.168.8.1</domain>
    </domain-config>
</network-security-config>
EOF

# ═══════════════════════════════════════
# AndroidManifest.xml
# ═══════════════════════════════════════
cat > app/src/main/AndroidManifest.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:usesCleartextTraffic="true"
        android:networkSecurityConfig="@xml/network_security_config"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>
</manifest>
EOF

# ═══════════════════════════════════════
# strings.xml
# ═══════════════════════════════════════
cat > app/src/main/res/values/strings.xml << 'EOF'
<resources>
    <string name="app_name">NetGuard ZTE</string>
</resources>
EOF

echo "✅ ملفات البناء"

# ═══════════════════════════════════════
# Device.kt
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/domain/model/Device.kt << 'EOF'
package com.example.netguardzte.domain.model

data class Device(
    val mac: String,
    val ip: String,
    val hostname: String,
    val connectionType: String,
    val isBlocked: Boolean = false
)
EOF

# ═══════════════════════════════════════
# API Models
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/data/api/models/LoginRequest.kt << 'EOF'
package com.example.netguardzte.data.api.models

data class LoginResponse(
    val result: String? = null
)
EOF

cat > app/src/main/java/com/example/netguardzte/data/api/models/DeviceResponse.kt << 'EOF'
package com.example.netguardzte.data.api.models

import com.google.gson.annotations.SerializedName

data class StationListResponse(
    @SerializedName("station_list")
    val stationList: List<StationInfo>? = null
)

data class StationInfo(
    val mac: String = "",
    val ip: String = "",
    val hostname: String = "",
    @SerializedName("conn_type")
    val connType: String = "WiFi"
)
EOF

cat > app/src/main/java/com/example/netguardzte/data/api/models/MacFilterResponse.kt << 'EOF'
package com.example.netguardzte.data.api.models

data class MacFilterResponse(
    val result: String? = null
)
EOF

cat > app/src/main/java/com/example/netguardzte/data/api/models/ErrorResponse.kt << 'EOF'
package com.example.netguardzte.data.api.models

import com.google.gson.JsonObject
EOF

echo "✅ Domain + API Models"

# ═══════════════════════════════════════
# ZteRouterApi.kt
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/data/api/ZteRouterApi.kt << 'EOF'
package com.example.netguardzte.data.api

import com.example.netguardzte.data.api.models.StationListResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ZteRouterApi {

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun login(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "LOGIN",
        @Field("password") password: String
    ): Response<ResponseBody>

    @GET("goform/goform_get_cmd_process")
    suspend fun getStationList(
        @Query("cmd") cmd: String = "station_list",
        @Query("multimode") multimode: String = "0"
    ): Response<StationListResponse>

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun setMacFilter(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "SET_WIFI_MAC_FILTER",
        @Field("mac_filter_enabled") enabled: String = "1",
        @Field("mac_filter_mode") mode: String = "0",
        @Field("mac_filter_list") macList: String
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun disableMacFilter(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "SET_WIFI_MAC_FILTER",
        @Field("mac_filter_enabled") enabled: String = "0"
    ): Response<ResponseBody>

    @GET("goform/goform_get_cmd_process")
    suspend fun getMacFilterList(
        @Query("cmd") cmd: String = "mac_filter_list"
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun logout(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "LOGOUT"
    ): Response<ResponseBody>
}
EOF

# ═══════════════════════════════════════
# RetrofitClient.kt
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/data/api/RetrofitClient.kt << 'EOF'
package com.example.netguardzte.data.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var currentBaseUrl: String = "http://192.168.0.1/"
    private var sessionCookie: String? = null
    private var retrofit: Retrofit? = null
    private var api: ZteRouterApi? = null

    fun setRouterAddress(ip: String) {
        currentBaseUrl = "http://$ip/"
        retrofit = null
        api = null
    }

    fun setSessionCookie(cookie: String?) {
        sessionCookie = cookie
        retrofit = null
        api = null
    }

    fun getSessionCookie(): String? = sessionCookie

    fun getApi(): ZteRouterApi {
        if (api == null) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                    sessionCookie?.let {
                        request.addHeader("Cookie", "zsid=$it")
                    }
                    chain.proceed(request.build())
                }
                .cookieJar(object : CookieJar {
                    private val cookies = mutableMapOf<String, List<Cookie>>()

                    override fun saveFromResponse(url: HttpUrl, cks: List<Cookie>) {
                        cookies[url.host] = cks
                        for (cookie in cks) {
                            if (cookie.name == "zsid") {
                                sessionCookie = cookie.value
                            }
                        }
                    }

                    override fun loadForRequest(url: HttpUrl): List<Cookie> {
                        return cookies[url.host] ?: emptyList()
                    }
                })
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            api = retrofit!!.create(ZteRouterApi::class.java)
        }
        return api!!
    }
}
EOF

echo "✅ API Layer"

# ═══════════════════════════════════════
# SecureStorage.kt
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/data/local/SecureStorage.kt << 'EOF'
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
EOF

# ═══════════════════════════════════════
# RouterRepository.kt
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/data/repository/RouterRepository.kt << 'EOF'
package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RouterRepository(private val storage: SecureStorage) {

    // ═══ تسجيل الدخول ═══
    suspend fun login(
        routerIp: String,
        username: String,
        password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            RetrofitClient.setRouterAddress(routerIp)

            val encodedPassword = Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )

            val api = RetrofitClient.getApi()
            val response = api.login(password = encodedPassword)

            if (response.isSuccessful) {
                val body = response.body()?.string() ?: ""
                if (body.contains("\"result\":\"0\"") || body.contains("\"result\":0")) {
                    storage.saveCredentials(routerIp, username, password)
                    storage.setLoggedIn(true)
                    Result.success("تم تسجيل الدخول بنجاح")
                } else if (body.contains("\"result\":\"3\"") || body.contains("\"result\":3")) {
                    Result.failure(Exception("كلمة المرور خاطئة"))
                } else {
                    // بعض الراوترات لا تُرجع result صريح — نعتبر النجاح كافياً
                    storage.saveCredentials(routerIp, username, password)
                    storage.setLoggedIn(true)
                    Result.success("تم الاتصال بالراوتر")
                }
            } else {
                Result.failure(Exception("فشل الاتصال: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("لا يمكن الوصول للراوتر: ${e.message}"))
        }
    }

    // ═══ جلب الأجهزة المتصلة ═══
    suspend fun getConnectedDevices(): Result<List<Device>> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val response = api.getStationList()

            if (response.isSuccessful) {
                val body = response.body()
                val stations = body?.stationList ?: emptyList()

                val devices = stations.map { station ->
                    Device(
                        mac = station.mac.uppercase(),
                        ip = station.ip,
                        hostname = station.hostname.ifBlank { "Unknown Device" },
                        connectionType = station.connType.ifBlank { "WiFi" }
                    )
                }
                Result.success(devices)
            } else if (response.code() == 401) {
                autoRelogin()
                retryGetDevices()
            } else {
                Result.failure(Exception("خطأ: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("فشل جلب الأجهزة: ${e.message}"))
        }
    }

    // ═══ حظر جهاز (إضافة MAC للقائمة السوداء) ═══
    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val api = RetrofitClient.getApi()
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")

                val response = api.setMacFilter(macList = newList)

                if (response.isSuccessful) {
                    Result.success("تم حظر الجهاز $mac")
                } else if (response.code() == 401) {
                    autoRelogin()
                    retryBlock(mac, currentBlockedList)
                } else {
                    Result.failure(Exception("فشل الحظر: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(Exception("فشل الحظر: ${e.message}"))
            }
        }

    // ═══ إلغاء حظر جهاز ═══
    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val api = RetrofitClient.getApi()
                val newList = currentBlockedList
                    .filter { it.uppercase() != mac.uppercase() }
                    .joinToString(";")

                val response = if (newList.isEmpty()) {
                    api.disableMacFilter()
                } else {
                    api.setMacFilter(macList = newList)
                }

                if (response.isSuccessful) {
                    Result.success("تم إلغاء حظر $mac")
                } else if (response.code() == 401) {
                    autoRelogin()
                    retryUnblock(mac, currentBlockedList)
                } else {
                    Result.failure(Exception("فشل إلغاء الحظر: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(Exception("فشل إلغاء الحظر: ${e.message}"))
            }
        }

    // ═══ جلب قائمة الحظر ═══
    suspend fun getBlockedMacs(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val response = api.getMacFilterList()

            if (response.isSuccessful) {
                val body = response.body()?.string() ?: ""
                val macs = parseBlockedMacs(body)
                Result.success(macs)
            } else {
                Result.success(emptyList())
            }
        } catch (_: Exception) {
            Result.success(emptyList())
        }
    }

    // ═══ تسجيل الخروج ═══
    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            api.logout()
        } catch (_: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.setSessionCookie(null)
    }

    // ═══ إعادة تسجيل الدخول التلقائي ═══
    private suspend fun autoRelogin() {
        try {
            val ip = storage.getRouterIp()
            val password = storage.getPassword()
            val encodedPassword = Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            RetrofitClient.setRouterAddress(ip)
            RetrofitClient.getApi().login(password = encodedPassword)
        } catch (_: Exception) {}
    }

    private suspend fun retryGetDevices(): Result<List<Device>> {
        return try {
            val response = RetrofitClient.getApi().getStationList()
            if (response.isSuccessful) {
                val devices = response.body()?.stationList?.map {
                    Device(it.mac.uppercase(), it.ip, it.hostname.ifBlank { "Unknown" }, it.connType)
                } ?: emptyList()
                Result.success(devices)
            } else {
                Result.failure(Exception("انتهت الجلسة"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun retryBlock(mac: String, list: List<String>): Result<String> {
        return try {
            val newList = (list + mac.uppercase()).joinToString(";")
            val response = RetrofitClient.getApi().setMacFilter(macList = newList)
            if (response.isSuccessful) Result.success("تم الحظر") else Result.failure(Exception("فشل"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun retryUnblock(mac: String, list: List<String>): Result<String> {
        return try {
            val newList = list.filter { it.uppercase() != mac.uppercase() }.joinToString(";")
            val response = if (newList.isEmpty()) {
                RetrofitClient.getApi().disableMacFilter()
            } else {
                RetrofitClient.getApi().setMacFilter(macList = newList)
            }
            if (response.isSuccessful) Result.success("تم إلغاء الحظر") else Result.failure(Exception("فشل"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseBlockedMacs(json: String): List<String> {
        return try {
            val macPattern = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
            macPattern.findAll(json).map { it.value.uppercase() }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
EOF

echo "✅ Repository"

# ═══════════════════════════════════════
# Theme
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/ui/theme/Color.kt << 'EOF'
package com.example.netguardzte.ui.theme

import androidx.compose.ui.graphics.Color

val Green700 = Color(0xFF2E7D32)
val Green500 = Color(0xFF4CAF50)
val Green100 = Color(0xFFC8E6C9)
val Blue900 = Color(0xFF0D47A1)
val Red500 = Color(0xFFF44336)
val Red100 = Color(0xFFFFCDD2)
val Orange500 = Color(0xFFFF9800)
val Gray50 = Color(0xFFFAFAFA)
val Gray100 = Color(0xFFF5F5F5)
val Gray600 = Color(0xFF757575)
val DarkSurface = Color(0xFF1E1E1E)
val DarkBackground = Color(0xFF121212)
EOF

cat > app/src/main/java/com/example/netguardzte/ui/theme/Theme.kt << 'EOF'
package com.example.netguardzte.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Green700,
    onPrimary = Color.White,
    primaryContainer = Green100,
    secondary = Blue900,
    surface = Color.White,
    surfaceVariant = Gray100,
    background = Gray50,
    error = Red500
)

private val Dark = darkColorScheme(
    primary = Green500,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1B5E20),
    secondary = Color(0xFF64B5F6),
    surface = DarkSurface,
    surfaceVariant = Color(0xFF2C2C2C),
    background = DarkBackground,
    error = Color(0xFFEF5350)
)

@Composable
fun NetGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
EOF

echo "✅ Theme"

# ═══════════════════════════════════════
# NetGuardViewModel.kt
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/ui/viewmodel/NetGuardViewModel.kt << 'EOF'
package com.example.netguardzte.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.data.repository.RouterRepository
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NetGuardUiState(
    val currentScreen: String = "login",
    val routerIp: String = "192.168.0.1",
    val username: String = "admin",
    val password: String = "",
    val isLoggingIn: Boolean = false,
    val loginError: String? = null,
    val devices: List<Device> = emptyList(),
    val blockedMacs: List<String> = emptyList(),
    val isLoadingDevices: Boolean = false,
    val deviceError: String? = null,
    val message: String? = null,
    val showBlockDialog: Device? = null,
    val showUnblockDialog: String? = null
)

class NetGuardViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = SecureStorage(application)
    private val repository = RouterRepository(storage)

    private val _uiState = MutableStateFlow(NetGuardUiState())
    val uiState: StateFlow<NetGuardUiState> = _uiState.asStateFlow()

    init {
        val ip = storage.getRouterIp()
        val username = storage.getUsername()
        _uiState.value = _uiState.value.copy(
            routerIp = ip,
            username = username,
            currentScreen = if (storage.isLoggedIn() && storage.hasCredentials()) "devices" else "login"
        )
        if (storage.isLoggedIn()) {
            loadDevices()
        }
    }

    // ═══════════════════════════════════════
    // تسجيل الدخول
    // ═══════════════════════════════════════
    fun onRouterIpChanged(ip: String) {
        _uiState.value = _uiState.value.copy(routerIp = ip, loginError = null)
    }

    fun onUsernameChanged(username: String) {
        _uiState.value = _uiState.value.copy(username = username, loginError = null)
    }

    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(password = password, loginError = null)
    }

    fun login() {
        val s = _uiState.value
        if (s.password.isBlank()) {
            _uiState.value = s.copy(loginError = "أدخل كلمة المرور")
            return
        }

        _uiState.value = s.copy(isLoggingIn = true, loginError = null)

        viewModelScope.launch {
            val result = repository.login(s.routerIp, s.username, s.password)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoggingIn = false,
                        currentScreen = "devices",
                        password = ""
                    )
                    loadDevices()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoggingIn = false,
                        loginError = e.message
                    )
                }
            )
        }
    }

    // ═══════════════════════════════════════
    // الأجهزة
    // ═══════════════════════════════════════
    fun loadDevices() {
        _uiState.value = _uiState.value.copy(isLoadingDevices = true, deviceError = null)

        viewModelScope.launch {
            val devicesResult = repository.getConnectedDevices()
            val blockedResult = repository.getBlockedMacs()

            val devices = devicesResult.getOrNull() ?: emptyList()
            val blocked = blockedResult.getOrNull() ?: emptyList()

            val devicesWithBlockStatus = devices.map { device ->
                device.copy(isBlocked = blocked.any { it.uppercase() == device.mac.uppercase() })
            }

            _uiState.value = _uiState.value.copy(
                isLoadingDevices = false,
                devices = devicesWithBlockStatus,
                blockedMacs = blocked,
                deviceError = devicesResult.exceptionOrNull()?.message
            )
        }
    }

    // ═══════════════════════════════════════
    // حظر / إلغاء حظر
    // ═══════════════════════════════════════
    fun onBlockClicked(device: Device) {
        _uiState.value = _uiState.value.copy(showBlockDialog = device)
    }

    fun onBlockConfirmed() {
        val device = _uiState.value.showBlockDialog ?: return
        _uiState.value = _uiState.value.copy(showBlockDialog = null)

        viewModelScope.launch {
            val result = repository.blockDevice(device.mac, _uiState.value.blockedMacs)
            result.fold(
                onSuccess = { msg ->
                    _uiState.value = _uiState.value.copy(message = msg)
                    loadDevices()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(message = "فشل الحظر: ${e.message}")
                }
            )
        }
    }

    fun onBlockCancelled() {
        _uiState.value = _uiState.value.copy(showBlockDialog = null)
    }

    fun onUnblockClicked(mac: String) {
        _uiState.value = _uiState.value.copy(showUnblockDialog = mac)
    }

    fun onUnblockConfirmed() {
        val mac = _uiState.value.showUnblockDialog ?: return
        _uiState.value = _uiState.value.copy(showUnblockDialog = null)

        viewModelScope.launch {
            val result = repository.unblockDevice(mac, _uiState.value.blockedMacs)
            result.fold(
                onSuccess = { msg ->
                    _uiState.value = _uiState.value.copy(message = msg)
                    loadDevices()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(message = "فشل إلغاء الحظر: ${e.message}")
                }
            )
        }
    }

    fun onUnblockCancelled() {
        _uiState.value = _uiState.value.copy(showUnblockDialog = null)
    }

    // ═══════════════════════════════════════
    // التنقل
    // ═══════════════════════════════════════
    fun navigateTo(screen: String) {
        _uiState.value = _uiState.value.copy(currentScreen = screen)
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
        }
        _uiState.value = NetGuardUiState(
            routerIp = storage.getRouterIp(),
            username = storage.getUsername()
        )
    }

    fun onMessageShown() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
EOF

echo "✅ ViewModel"

# ═══════════════════════════════════════
# DeviceCard.kt
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/ui/components/DeviceCard.kt << 'EOF'
package com.example.netguardzte.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netguardzte.domain.model.Device

@Composable
fun DeviceCard(
    device: Device,
    onBlock: () -> Unit,
    onUnblock: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isBlocked)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ═══ أيقونة الجهاز ═══
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (device.isBlocked)
                            MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                        else
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        device.isBlocked -> Icons.Default.Block
                        device.hostname.contains("phone", true) -> Icons.Default.PhoneAndroid
                        device.connectionType.contains("wifi", true) -> Icons.Default.Wifi
                        else -> Icons.Default.Computer
                    },
                    contentDescription = null,
                    tint = if (device.isBlocked)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // ═══ معلومات الجهاز ═══
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.hostname,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (device.isBlocked) MaterialTheme.colorScheme.error else Color.Unspecified
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "IP: ${device.ip}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "MAC: ${device.mac}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (device.isBlocked) {
                    Text(
                        text = "⛔ محظور",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ═══ زر الحظر / إلغاء الحظر ═══
            IconButton(
                onClick = { if (device.isBlocked) onUnblock() else onBlock() }
            ) {
                Icon(
                    imageVector = if (device.isBlocked) Icons.Default.CheckCircle else Icons.Default.Block,
                    contentDescription = if (device.isBlocked) "إلغاء الحظر" else "حظر",
                    tint = if (device.isBlocked) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
EOF

echo "✅ Components"

# ═══════════════════════════════════════
# LoginScreen.kt
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/ui/screens/login/LoginScreen.kt << 'EOF'
package com.example.netguardzte.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(
    routerIp: String,
    username: String,
    password: String,
    isLoggingIn: Boolean,
    error: String?,
    onRouterIpChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "📡", fontSize = 56.sp)

        Spacer(Modifier.height(8.dp))

        Text(
            text = "NetGuard ZTE",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "إدارة أجهزة الشبكة",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = routerIp,
            onValueChange = onRouterIpChanged,
            label = { Text("عنوان الراوتر") },
            placeholder = { Text("192.168.0.1") },
            leadingIcon = { Icon(Icons.Default.Router, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            )
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChanged,
            label = { Text("اسم المستخدم") },
            leadingIcon = { Icon(Icons.Default.Person, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChanged,
            label = { Text("كلمة المرور") },
            leadingIcon = { Icon(Icons.Default.Lock, null) },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null
                    )
                }
            },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onLogin() })
        )

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onLogin,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !isLoggingIn && password.isNotBlank()
        ) {
            if (isLoggingIn) {
                CircularProgressIndicator(
                    Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("جاري الاتصال...")
            } else {
                Text("تسجيل الدخول", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "تأكد من اتصالك بنفس شبكة الراوتر WiFi",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
EOF

# ═══════════════════════════════════════
# DevicesScreen.kt
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/ui/screens/devices/DevicesScreen.kt << 'EOF'
package com.example.netguardzte.ui.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netguardzte.domain.model.Device
import com.example.netguardzte.ui.components.DeviceCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    devices: List<Device>,
    isLoading: Boolean,
    error: String?,
    showBlockDialog: Device?,
    onRefresh: () -> Unit,
    onBlock: (Device) -> Unit,
    onUnblock: (String) -> Unit,
    onBlockConfirmed: () -> Unit,
    onBlockCancelled: () -> Unit,
    onNavigateToBlocked: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الأجهزة المتصلة", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToBlocked) {
                        Icon(Icons.Default.Block, "المحظورون")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, "تحديث")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("جاري البحث عن الأجهزة...")
                    }
                }

                error != null -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("⚠️", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = error,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRefresh) {
                            Text("إعادة المحاولة")
                        }
                    }
                }

                devices.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("📡", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "لا توجد أجهزة متصلة",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                text = "${devices.size} جهاز متصل",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                        }
                        items(items = devices, key = { it.mac }) { device ->
                            DeviceCard(
                                device = device,
                                onBlock = { onBlock(device) },
                                onUnblock = { onUnblock(device.mac) }
                            )
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }

    // ═══ مربع حوار تأكيد الحظر ═══
    showBlockDialog?.let { device ->
        AlertDialog(
            onDismissRequest = onBlockCancelled,
            icon = {
                Icon(
                    Icons.Default.Block, null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("حظر الجهاز؟", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("هل تريد حظر هذا الجهاز من الشبكة؟")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${device.hostname}\nIP: ${device.ip}\nMAC: ${device.mac}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onBlockConfirmed) {
                    Text("حظر", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onBlockCancelled) {
                    Text("إلغاء")
                }
            }
        )
    }
}
EOF

# ═══════════════════════════════════════
# BlockedScreen.kt
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/ui/screens/blocked/BlockedScreen.kt << 'EOF'
package com.example.netguardzte.ui.screens.blocked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.AlertDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedScreen(
    blockedMacs: List<String>,
    showUnblockDialog: String?,
    onBack: () -> Unit,
    onUnblock: (String) -> Unit,
    onUnblockConfirmed: () -> Unit,
    onUnblockCancelled: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الأجهزة المحظورة", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    titleContentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (blockedMacs.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("✅", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "لا توجد أجهزة محظورة",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "${blockedMacs.size} جهاز محظور",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                    }
                    items(items = blockedMacs, key = { it }) { mac ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "⛔ محظور",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = mac,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                }
                                IconButton(onClick = { onUnblock(mac) }) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        "إلغاء الحظر",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showUnblockDialog?.let { mac ->
        AlertDialog(
            onDismissRequest = onUnblockCancelled,
            title = { Text("إلغاء الحظر؟", fontWeight = FontWeight.Bold) },
            text = { Text("هل تريد السماح لـ $mac بالاتصال مجدداً؟") },
            confirmButton = {
                TextButton(onClick = onUnblockConfirmed) {
                    Text("إلغاء الحظر")
                }
            },
            dismissButton = {
                TextButton(onClick = onUnblockCancelled) {
                    Text("إلغاء")
                }
            }
        )
    }
}
EOF

echo "✅ Screens"

# ═══════════════════════════════════════
# MainActivity.kt
# ═══════════════════════════════════════
cat > app/src/main/java/com/example/netguardzte/MainActivity.kt << 'EOF'
package com.example.netguardzte

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.netguardzte.ui.screens.blocked.BlockedScreen
import com.example.netguardzte.ui.screens.devices.DevicesScreen
import com.example.netguardzte.ui.screens.login.LoginScreen
import com.example.netguardzte.ui.theme.NetGuardTheme
import com.example.netguardzte.ui.viewmodel.NetGuardViewModel
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetGuardTheme {
                val vm: NetGuardViewModel = viewModel()
                val s by vm.uiState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(s.message) {
                    s.message?.let {
                        snackbarHostState.showSnackbar(it)
                        vm.onMessageShown()
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) {
                    when (s.currentScreen) {
                        "login" -> LoginScreen(
                            routerIp = s.routerIp,
                            username = s.username,
                            password = s.password,
                            isLoggingIn = s.isLoggingIn,
                            error = s.loginError,
                            onRouterIpChanged = { vm.onRouterIpChanged(it) },
                            onUsernameChanged = { vm.onUsernameChanged(it) },
                            onPasswordChanged = { vm.onPasswordChanged(it) },
                            onLogin = { vm.login() }
                        )

                        "devices" -> DevicesScreen(
                            devices = s.devices,
                            isLoading = s.isLoadingDevices,
                            error = s.deviceError,
                            showBlockDialog = s.showBlockDialog,
                            onRefresh = { vm.loadDevices() },
                            onBlock = { vm.onBlockClicked(it) },
                            onUnblock = { vm.onUnblockClicked(it) },
                            onBlockConfirmed = { vm.onBlockConfirmed() },
                            onBlockCancelled = { vm.onBlockCancelled() },
                            onNavigateToBlocked = { vm.navigateTo("blocked") },
                            onNavigateToSettings = { vm.navigateTo("settings") }
                        )

                        "blocked" -> BlockedScreen(
                            blockedMacs = s.blockedMacs,
                            showUnblockDialog = s.showUnblockDialog,
                            onBack = { vm.navigateTo("devices") },
                            onUnblock = { vm.onUnblockClicked(it) },
                            onUnblockConfirmed = { vm.onUnblockConfirmed() },
                            onUnblockCancelled = { vm.onUnblockCancelled() }
                        )
                    }
                }
            }
        }
    }
}
EOF

echo "✅ MainActivity"

# ═══════════════════════════════════════
# التحقق النهائي
# ═══════════════════════════════════════
echo ""
echo "═══════════════════════════════════════════"
echo "✅ تم إنشاء جميع الملفات!"
echo "═══════════════════════════════════════════"
echo ""
echo "📁 ملفات Kotlin:"
find app/src -name "*.kt" | sort
echo ""
echo "📋 ملفات XML:"
find app/src -name "*.xml" | sort
