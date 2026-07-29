package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.api.ZteRouterApi
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.security.MessageDigest

class RouterRepository(private val storage: SecureStorage) {

    var lastRawResponse: String = ""
        private set
    var lastWorkingCommand: String = ""
        private set
    var loginDebug: String = ""
        private set
    var cookieDebug: String = ""
        private set
    var allCommandsDebug: String = ""
        private set

    // ═══ مخزن الكوكيز المشترك ═══
    private val sharedCookieStore = mutableMapOf<String, String>()

    // ═══════════════════════════════════════════
    // أدوات مساعدة
    // ═══════════════════════════════════════════

    private fun encodeParam(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun isSuccess(body: String): Boolean {
        return body.contains("\"result\":\"success\"") ||
                body.contains("\"result\":0") ||
                body.contains("\"result\":\"0\"")
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun extractJsonField(json: String, field: String): String {
        Regex(""""$field"\s*:\s*"([^"]*?)"""").find(json)?.let { return it.groupValues[1] }
        Regex(""""$field"\s*:\s*"?([^",}]+)"?""").find(json)?.let { return it.groupValues[1].trim() }
        return ""
    }

    // ═══════════════════════════════════════════
    // إنشاء OkHttp مع كوكيز مشتركة
    // ═══════════════════════════════════════════

    private fun createRawClient(debug: StringBuilder): okhttp3.OkHttpClient {
        return okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .cookieJar(object : okhttp3.CookieJar {
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                    for (c in cookies) {
                        sharedCookieStore[c.name] = c.value
                    }
                }
                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                    return sharedCookieStore.map { (n, v) ->
                        okhttp3.Cookie.Builder().domain(url.host).path("/").name(n).value(v).build()
                    }
                }
            })
            .build()
    }

    private fun syncCookiesToRetrofit() {
        for ((name, value) in sharedCookieStore) {
            RetrofitClient.setSessionCookie(name, value)
        }
    }

    // ═══════════════════════════════════════════
    // تسجيل الدخول
    // ═══════════════════════════════════════════
        
                 suspend fun login(
        routerIp: String,
        username: String,
        password: String
    ): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                debug.appendLine("=== LOGIN FIX ===")
                debug.appendLine("Router: $routerIp")

                RetrofitClient.reset()
                RetrofitClient.setRouterAddress(routerIp)
                val client = RetrofitClient.getHttpClient()
                val api = RetrofitClient.getApi()
                val base = "http://$routerIp"

                // ═══ 1. حمّل صفحة الدخول ═══
                debug.appendLine("\n--- Login page ---")
                try {
                    val req = okhttp3.Request.Builder().url("$base/m/index.html").build()
                    client.newCall(req).execute()
                    debug.appendLine("Loaded")
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                // ═══ 2. اجلب LD ═══
                debug.appendLine("\n--- LD ---")
                var ld = ""
                try {
                    val req = okhttp3.Request.Builder()
                        .url("$base/goform/goform_get_cmd_process?cmd=LD")
                        .header("X-Requested-With", "XMLHttpRequest").build()
                    val body = client.newCall(req).execute().body?.string() ?: ""
                    debug.appendLine("LD response: $body")
                    ld = Regex(""""LD"\s*:\s*"([^"]*?)"""").find(body)?.groupValues?.getOrNull(1) ?: ""
                } catch (e: Exception) { debug.appendLine("LD error: ${e.message}") }
                debug.appendLine("LD: '$ld'")

                // ═══ 3. شفر: SHA256(password + LD) ═══
                val encodedPass = if (ld.isNotBlank()) sha256(password + ld)
                    else Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                debug.appendLine("Method: ${if (ld.isNotBlank()) "SHA256(pass+LD)" else "Base64"}")
                debug.appendLine("Encoded: $encodedPass")

                // ═══ 4. أرسل LOGIN ═══
                // من service.js: LOGIN لا يحتاج AD
                // isForce = true (وليس 1!)
                debug.appendLine("\n--- LOGIN (isForce=true) ---")
                val formBody = okhttp3.FormBody.Builder()
                    .add("isTest", "false")
                    .add("goformId", "LOGIN")
                    .add("password", encodedPass)
                    .add("isForce", "true")  // ← "true" وليس "1"!
                    .build()

                val req = okhttp3.Request.Builder()
                    .url("$base/goform/goform_set_cmd_process")
                    .post(formBody)
                    .header("Referer", "$base/m/index.html")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build()

                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                debug.appendLine("Response: $body")
                debug.appendLine("Cookies: ${RetrofitClient.getCookiesString()}")

                when {
                    body.contains("\"result\":\"0\"") || body.contains("\"result\":0") -> {
                        debug.appendLine("✅ LOGIN SUCCESS!")
                        storage.saveCredentials(routerIp, username, password)
                        storage.setLoggedIn(true)
                        loginDebug = debug.toString()
                        return@withContext Result.success("تم الاتصال بالراوتر")
                    }
                    body.contains("\"result\":\"1\"") || body.contains("\"result\":1") -> {
                        debug.appendLine("⚠️ Session still busy even with isForce=true")

                        // جرب ACL مباشرة
                        try {
                            val aclReq = okhttp3.Request.Builder()
                                .url("$base/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList")
                                .header("X-Requested-With", "XMLHttpRequest").build()
                            val aclBody = client.newCall(aclReq).execute().body?.string() ?: ""
                            debug.appendLine("ACL: $aclBody")

                            if (aclBody.contains("AclMode") || aclBody.contains("BlackMacList") || aclBody.length > 30) {
                                debug.appendLine("✅ ACL works! Session is usable!")
                                storage.saveCredentials(routerIp, username, password)
                                storage.setLoggedIn(true)
                                loginDebug = debug.toString()
                                return@withContext Result.success("تم الاتصال بالراوتر")
                            }
                        } catch (_: Exception) {}

                        loginDebug = debug.toString()
                        return@withContext Result.failure(
                            Exception("جلسة أخرى نشطة - أطفئ الراوتر 10 ثواني ثم شغّله")
                        )
                    }
                    body.contains("\"result\":\"3\"") || body.contains("\"result\":3") -> {
                        debug.appendLine("❌ Wrong password")
                        loginDebug = debug.toString()
                        return@withContext Result.failure(Exception("كلمة المرور خاطئة"))
                    }
                    else -> {
                        debug.appendLine("❓ Unknown: $body")
                        loginDebug = debug.toString()
                        return@withContext Result.failure(Exception("استجابة غير متوقعة: $body"))
                    }
                }
            }
        } catch (e: Exception) { Result.failure(Exception("خطأ: ${e.message}")) }
    }                    
            
                            
                
    // ═══════════════════════════════════════════
    // التأكد من تسجيل الدخول
    // ═══════════════════════════════════════════

    private suspend fun ensureLoggedIn(api: ZteRouterApi, debug: StringBuilder): Boolean {
        try {
            debug.appendLine("\n--- ensureLoggedIn ---")

            // حاول قراءة ACL
            val aclR = api.getGenericCmd(cmd = "queryDeviceAccessControlList")
            val aclBody = aclR.body()?.string() ?: ""
            debug.appendLine("ACL: ${aclBody.take(200)}")

            if (aclBody.contains("AclMode") || aclBody.contains("BlackMacList")) {
                debug.appendLine("✅ Authenticated")
                return true
            }

            // غير مسجل - أعد الدخول
            debug.appendLine("Not authenticated, re-logging...")
            val result = login(storage.getRouterIp(), storage.getUsername(), storage.getPassword())
            debug.appendLine("Re-login: ${result.isSuccess}")

            syncCookiesToRetrofit()
            debug.appendLine("Retrofit cookies after re-login: ${RetrofitClient.getCookiesString()}")

            if (result.isSuccess) {
                val aclR2 = api.getGenericCmd(cmd = "queryDeviceAccessControlList")
                val aclBody2 = aclR2.body()?.string() ?: ""
                debug.appendLine("ACL after re-login: ${aclBody2.take(200)}")
                return aclBody2.contains("AclMode") || aclBody2.contains("BlackMacList") || aclBody2.length > 30
            }

            return false
        } catch (e: Exception) {
            debug.appendLine("ensureLoggedIn error: ${e.message}")
            return false
        }
    }

    // ═══════════════════════════════════════════
    // جلب الأجهزة المتصلة
    // ═══════════════════════════════════════════

    suspend fun getConnectedDevices(): Result<List<Device>> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val routerIp = try { storage.getRouterIp() } catch (_: Exception) { "192.168.0.1" }
                val subnet = routerIp.substringBeforeLast(".")

                debug.appendLine("=== DEVICE SCAN ===")

                try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh flush dev wlan0")).waitFor() } catch (_: Exception) {}

                for (i in 1..50) {
                    try {
                        val s = java.net.Socket()
                        s.connect(java.net.InetSocketAddress("$subnet.$i", 80), 30)
                        s.close()
                    } catch (_: Exception) {}
                }

                var devices = readArpFromAllSources(debug)
                debug.appendLine("Found: ${devices.size}")

                if (devices.isEmpty()) devices = readFromRouterApi(debug)

                if (devices.isNotEmpty()) {
                    allCommandsDebug = debug.toString()
                    return@withContext Result.success(devices)
                }

                allCommandsDebug = debug.toString()
                Result.failure(Exception("لم يتم العثور على أجهزة"))
            }
        } catch (e: Exception) { Result.failure(Exception("فشل: ${e.message}")) }
    }

    // ═══════════════════════════════════════════════════════════════
    // حظر جهاز
    // ═══════════════════════════════════════════════════════════════

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()

                debug.appendLine("=== BLOCK $macUpper ===")

                val authenticated = ensureLoggedIn(api, debug)
                if (!authenticated) {
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext Result.failure(Exception("غير مسجل دخول"))
                }

                val currentAcl = readCurrentACL(api, debug)
                val currentBlackListRaw = currentAcl["BlackMacList"] ?: ""
                val existingMacs = currentBlackListRaw.split(";")
                    .map { it.trim().uppercase() }
                    .filter { it.isNotEmpty() && it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
                    .toMutableList()

                if (macUpper !in existingMacs) existingMacs.add(macUpper)
                val newBlackList = existingMacs.joinToString(";") + ";"

                val adValue = computeAd(api, debug)

                val body = buildString {
                    append("isTest=false")
                    append("&goformId=setDeviceAccessControlList")
                    append("&AclMode=2")
                    append("&BlackMacList=${encodeParam(newBlackList)}")
                    append("&WhiteMacList=")
                    append("&WhiteNameList=")
                    append("&BlackNameList=")
                    if (adValue.isNotBlank()) append("&AD=${encodeParam(adValue)}")
                }

                debug.appendLine("Body: $body")

                try {
                    val r = api.postRaw(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    val responseBody = r.body()?.string() ?: ""
                    debug.appendLine("Response: $responseBody")

                    if (isSuccess(responseBody)) {
                        debug.appendLine("✅ BLOCK SUCCESS!")
                        lastRawResponse = debug.toString()
                        allCommandsDebug = debug.toString()
                        return@withContext Result.success("تم حظر $macUpper")
                    }

                    debug.appendLine("❌ BLOCK FAILED")
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext Result.failure(Exception("فشل: $responseBody"))
                } catch (e: Exception) {
                    debug.appendLine("Error: ${e.message}")
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext Result.failure(Exception("خطأ: ${e.message}"))
                }
            }
        } catch (e: Exception) { Result.failure(Exception("خطأ: ${e.message}")) }
    }

    // ═══════════════════════════════════════════
    // إلغاء حظر
    // ═══════════════════════════════════════════

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()

                debug.appendLine("=== UNBLOCK $macUpper ===")
                ensureLoggedIn(api, debug)

                val currentAcl = readCurrentACL(api, debug)
                val currentBlackListRaw = currentAcl["BlackMacList"] ?: ""

                val existingMacs = currentBlackListRaw.split(";")
                    .map { it.trim().uppercase() }
                    .filter { it.isNotEmpty() && it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
                    .toMutableList()

                existingMacs.remove(macUpper)
                val newAclMode = if (existingMacs.isEmpty()) "0" else "2"
                val newBlackList = if (existingMacs.isEmpty()) "" else existingMacs.joinToString(";") + ";"

                val adValue = computeAd(api, debug)

                val body = buildString {
                    append("isTest=false")
                    append("&goformId=setDeviceAccessControlList")
                    append("&AclMode=$newAclMode")
                    append("&BlackMacList=${encodeParam(newBlackList)}")
                    append("&WhiteMacList=")
                    append("&WhiteNameList=")
                    append("&BlackNameList=")
                    if (adValue.isNotBlank()) append("&AD=${encodeParam(adValue)}")
                }

                val r = api.postRaw(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                val responseBody = r.body()?.string() ?: ""

                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (isSuccess(responseBody)) Result.success("تم إلغاء حظر $macUpper")
                else Result.failure(Exception("فشل: $responseBody"))
            }
        } catch (e: Exception) { Result.failure(Exception("خطأ: ${e.message}")) }
    }

    // ═══════════════════════════════════════════
    // جلب قائمة المحظورين
    // ═══════════════════════════════════════════

    suspend fun getBlockedMacs(): Result<List<String>> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                ensureLoggedIn(api, debug)

                val aclData = readCurrentACL(api, debug)
                val blackListRaw = aclData["BlackMacList"] ?: ""

                val macs = blackListRaw.split(";")
                    .map { it.trim().uppercase() }
                    .filter { it.isNotEmpty() && it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }

                allCommandsDebug = debug.toString()
                Result.success(macs)
            }
        } catch (e: Exception) { Result.failure(Exception("فشل: ${e.message}")) }
    }

    // ═══════════════════════════════════════════
    // قراءة ACL
    // ═══════════════════════════════════════════

    private suspend fun readCurrentACL(api: ZteRouterApi, debug: StringBuilder): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val r = api.getGenericCmd(cmd = "queryDeviceAccessControlList")
            val body = r.body()?.string() ?: ""
            debug.appendLine("ACL: ${body.take(200)}")
            for (key in listOf("AclMode", "BlackMacList", "WhiteMacList")) {
                Regex(""""$key"\s*:\s*"([^"]*?)"""").find(body)?.let { result[key] = it.groupValues[1] }
            }
        } catch (e: Exception) { debug.appendLine("ACL error: ${e.message}") }
        return result
    }

    // ═══════════════════════════════════════════
    // حساب AD
    // ═══════════════════════════════════════════

    private suspend fun computeAd(api: ZteRouterApi, debug: StringBuilder): String {
        try {
            val waInner = fetchValue(api, "wa_inner_version", debug)
            val crVersion = fetchValue(api, "cr_version", debug)
            val rd = fetchValue(api, "RD", debug)
            if (waInner.isBlank() || crVersion.isBlank() || rd.isBlank()) return ""
            val ad = md5(md5(waInner + crVersion) + rd)
            debug.appendLine("AD=$ad")
            return ad
        } catch (e: Exception) { debug.appendLine("AD error: ${e.message}"); return "" }
    }

    private suspend fun fetchValue(api: ZteRouterApi, name: String, debug: StringBuilder): String {
        try {
            val r = api.getGenericCmd(cmd = name)
            val body = r.body()?.string() ?: ""
            val value = extractJsonField(body, name)
            if (value.isNotBlank()) return value
        } catch (_: Exception) {}
        return ""
    }

    // ═══════════════════════════════════════════
    // اختبار الاتصال
    // ═══════════════════════════════════════════

    suspend fun testRouterConnection(): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val api = RetrofitClient.getApi()

                debug.appendLine("=== TEST ===")
                try {
                    val r = api.getGenericCmd(cmd = "Language")
                    debug.appendLine("Language: ${r.body()?.string()}")
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                debug.appendLine("\nCookies: ${RetrofitClient.getCookiesString()}")
                debug.appendLine("Shared: ${sharedCookieStore.entries.joinToString { "${it.key}=${it.value.take(15)}" }}")

                debug.appendLine("\n=== AD TEST ===")
                computeAd(api, debug)

                debug.appendLine("\n=== ACL TEST ===")
                readCurrentACL(api, debug)

                Result.success(debug.toString())
            }
        } catch (e: Exception) { Result.failure(Exception("Test: ${e.message}")) }
    }

    // ═══════════════════════════════════════════
    // تسجيل الخروج
    // ═══════════════════════════════════════════

    suspend fun logout() {
        try {
            withContext(Dispatchers.IO) {
                try { RetrofitClient.getApi().logout() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        storage.setLoggedIn(false)
        sharedCookieStore.clear()
        RetrofitClient.reset()
    }

    // ═══════════════════════════════════════════
    // ARP أدوات
    // ═══════════════════════════════════════════

    private suspend fun readArpFromAllSources(debug: StringBuilder): List<Device> {
        var d = readIpNeigh(debug); if (d.isNotEmpty()) return d
        d = readArpFromFile(); if (d.isNotEmpty()) return d
        return readArpFromCommand("cat /proc/net/arp")
    }

    private fun readIpNeigh(debug: StringBuilder): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh"))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) {
                if (!line.uppercase().contains("FAILED") && !line.uppercase().contains("INCOMPLETE")) {
                    parseArpLine(line)?.let { devices.add(it) }
                }
                line = r.readLine()
            }
            p.waitFor()
        } catch (_: Exception) {}
        return devices
    }

    private fun readArpFromFile(): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val f = java.io.File("/proc/net/arp")
            if (!f.exists() || !f.canRead()) return emptyList()
            val r = BufferedReader(InputStreamReader(f.inputStream()))
            r.readLine()
            var line = r.readLine()
            while (line != null) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4 && parts[3].uppercase() != "00:00:00:00:00:00" && parts[2] != "0x0") {
                    devices.add(makeDevice(parts[0], parts[3].uppercase()))
                }
                line = r.readLine()
            }
            r.close()
        } catch (_: Exception) {}
        return devices
    }

    private fun readArpFromCommand(command: String): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) {
                parseArpLine(line)?.let { devices.add(it) }
                line = r.readLine()
            }
            p.waitFor(); p.destroy()
        } catch (_: Exception) {}
        return devices
    }

    private fun parseArpLine(line: String): Device? {
        val mac = Regex("[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}")
            .find(line)?.value?.uppercase() ?: return null
        if (mac == "00:00:00:00:00:00") return null
        val ip = Regex("(\\d{1,3}\\.){3}\\d{1,3}").find(line)?.value ?: return null
        return makeDevice(ip, mac)
    }

    private suspend fun readFromRouterApi(debug: StringBuilder): List<Device> {
        try {
            val api = RetrofitClient.getApi()
            for (cmd in listOf("station_list", "wifi_station_list", "dhcp_list")) {
                try {
                    val r = api.getGenericCmd(cmd = cmd)
                    val b = r.body()?.string() ?: ""
                    debug.appendLine("  [$cmd]: ${b.take(100)}")
                    if (b.length > 30) { val d = parseDevices(b); if (d.isNotEmpty()) return d }
                } catch (e: Exception) { debug.appendLine("  [$cmd] error: ${e.message}") }
            }
        } catch (e: Exception) { debug.appendLine("API error: ${e.message}") }
        return emptyList()
    }

    private fun makeDevice(ip: String, mac: String): Device {
        val rIp = try { storage.getRouterIp() } catch (_: Exception) { "" }
        return Device(mac = mac, ip = ip, hostname = nameFor(ip, mac), connectionType = if (ip == rIp) "Router" else "WiFi")
    }

    private fun nameFor(ip: String, mac: String): String {
        val v = when {
            mac.startsWith("A4:83") || mac.startsWith("F0:18") -> "Apple"
            mac.startsWith("CC:96") || mac.startsWith("58:48") -> "Huawei"
            mac.startsWith("70:F9") || mac.startsWith("94:B8") -> "Samsung"
            mac.startsWith("6C:B0") || mac.startsWith("54:FA") -> "Xiaomi"
            mac.startsWith("00:21") -> "ZTE"
            else -> ""
        }
        val s = ip.substringAfterLast(".")
        return when { v.isNotBlank() -> "$v ($s)"; s == "1" -> "الراوتر"; else -> "جهاز .$s" }
    }

    private fun parseDevices(raw: String): List<Device> {
        try {
            val macs = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}").findAll(raw).map { it.value.uppercase() }.distinct().toList()
            if (macs.isEmpty()) return emptyList()
            val ips = Regex("(\\d{1,3}\\.){3}\\d{1,3}").findAll(raw).map { it.value }.toList()
            return macs.mapIndexed { i, mac -> Device(mac = mac, ip = ips.getOrNull(i) ?: "", hostname = "جهاز ${i + 1}", connectionType = "WiFi") }
        } catch (_: Exception) { return emptyList() }
    }
}
