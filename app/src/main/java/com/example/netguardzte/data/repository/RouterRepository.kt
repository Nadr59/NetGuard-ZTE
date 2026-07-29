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
                debug.appendLine("=== LOGIN v5 ===")
                debug.appendLine("Router: $routerIp")

                sharedCookieStore.clear()
                val client = createRawClient(debug)
                val base = "http://$routerIp"

                // ═══ 1. GET الصفحة الرئيسية ═══
                debug.appendLine("\n--- Main page ---")
                try {
                    val mainReq = okhttp3.Request.Builder()
                        .url("$base/")
                        .header("Accept", "text/html,application/xhtml+xml,*/*")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                        .build()
                    val mainResp = client.newCall(mainReq).execute()
                    val mainHtml = mainResp.body?.string() ?: ""
                    debug.appendLine("Status: ${mainResp.code}, HTML: ${mainHtml.length}")
                    debug.appendLine("HTML: ${mainHtml.take(300)}")
                    debug.appendLine("Cookies: ${sharedCookieStore.entries.joinToString { "${it.key}=${it.value.take(15)}" }}")
                } catch (e: Exception) { debug.appendLine("Main page error: ${e.message}") }

                // ═══ 2. GET config.js ═══
                debug.appendLine("\n--- config.js ---")
                var sha256Attr = ""
                try {
                    val configReq = okhttp3.Request.Builder().url("$base/config.js").build()
                    val configBody = client.newCall(configReq).execute().body?.string() ?: ""
                    debug.appendLine("config.js: ${configBody.take(300)}")
                    sha256Attr = Regex("""WEB_ATTR_IF_SUPPORT_SHA256\s*=\s*(\d+)""").find(configBody)?.groupValues?.getOrNull(1) ?: ""
                    debug.appendLine("SHA256 attr: '$sha256Attr'")
                } catch (e: Exception) { debug.appendLine("config.js error: ${e.message}") }

                // ═══ 3. GET LD ═══
                debug.appendLine("\n--- LD ---")
                var ld = ""
                for (method in listOf("$base/goform/goform_get_cmd_process?nv=LD", "$base/goform/goform_get_cmd_process?cmd=LD")) {
                    try {
                        val req = okhttp3.Request.Builder().url(method).header("X-Requested-With", "XMLHttpRequest").build()
                        val body = client.newCall(req).execute().body?.string() ?: ""
                        debug.appendLine("$method → $body")
                        ld = Regex(""""LD"\s*:\s*"([^"]*?)"""").find(body)?.groupValues?.getOrNull(1) ?: ""
                        if (ld.isNotBlank()) break
                    } catch (e: Exception) { debug.appendLine("LD error: ${e.message}") }
                }
                debug.appendLine("LD: '$ld'")

                // ═══ 4. GET wa_inner + cr_version + RD ═══
                debug.appendLine("\n--- Versions ---")
                var waInner = ""; var crVersion = ""; var rd = ""
                try {
                    val req = okhttp3.Request.Builder()
                        .url("$base/goform/goform_get_cmd_process?cmd=wa_inner_version,cr_version,RD")
                        .header("X-Requested-With", "XMLHttpRequest").build()
                    val body = client.newCall(req).execute().body?.string() ?: ""
                    debug.appendLine("Response: $body")
                    waInner = Regex(""""wa_inner_version"\s*:\s*"([^"]*?)"""").find(body)?.groupValues?.getOrNull(1) ?: ""
                    crVersion = Regex(""""cr_version"\s*:\s*"([^"]*?)"""").find(body)?.groupValues?.getOrNull(1) ?: ""
                    rd = Regex(""""RD"\s*:\s*"([^"]*?)"""").find(body)?.groupValues?.getOrNull(1) ?: ""
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                val ad = if (waInner.isNotBlank() && crVersion.isNotBlank() && rd.isNotBlank()) md5(md5(waInner + crVersion) + rd) else ""
                debug.appendLine("wa=$waInner cr=$crVersion RD=$rd AD=$ad")

                // ═══ 5. جهز التشفير ═══
                debug.appendLine("\n--- Encodings ---")
                val encodings = mutableListOf<Pair<String, String>>()
                val b64 = Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val sha256P = sha256(password)
                val md5P = md5(password)

                encodings.add("Base64" to b64)
                encodings.add("Plain" to password)
                if (ld.isNotBlank()) {
                    encodings.add("SHA256_SHA256_LD" to sha256(sha256P + ld))
                    encodings.add("SHA256_passLD" to sha256(password + ld))
                    encodings.add("MD5_MD5_LD" to md5(md5P + ld))
                }
                encodings.add("SHA256" to sha256P)
                encodings.add("MD5" to md5P)

                for ((l, v) in encodings) debug.appendLine("  $l: ${v.take(50)}")

                // ═══ 6. جرب كل طريقة ═══
                for ((label, encodedPass) in encodings) {
                    debug.appendLine("\n=== Try: $label ===")
                    debug.appendLine("Cookies: ${sharedCookieStore.entries.joinToString { "${it.key}=${it.value.take(10)}" }}")

                    try {
                        val formBody = okhttp3.FormBody.Builder()
                            .add("isTest", "false")
                            .add("goformId", "LOGIN")
                            .add("password", encodedPass)
                            .add("AD", ad).build()

                        val req = okhttp3.Request.Builder()
                            .url("$base/goform/goform_set_cmd_process")
                            .post(formBody)
                            .header("Referer", "$base/index.html")
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Accept", "application/json, text/javascript, */*; q=0.01").build()

                        val resp = client.newCall(req).execute()
                        val body = resp.body?.string() ?: ""
                        debug.appendLine("Response: $body")
                        debug.appendLine("Cookies after: ${sharedCookieStore.entries.joinToString { "${it.key}=${it.value.take(10)}" }}")

                        when {
                            body.contains("\"result\":\"0\"") || body.contains("\"result\":0") -> {
                                debug.appendLine("✅ SUCCESS with $label!")
                                syncCookiesToRetrofit()
                                verifySession(client, base, debug)
                                storage.saveCredentials(routerIp, username, password)
                                storage.setLoggedIn(true)
                                loginDebug = debug.toString()
                                return@withContext Result.success("تم الاتصال ($label)")
                            }
                            body.contains("\"result\":\"1\"") || body.contains("\"result\":1") -> {
                                debug.appendLine("⚠️ Session exists")
                                syncCookiesToRetrofit()
                                val aclBody = verifySession(client, base, debug)
                                debug.appendLine("ACL: ${aclBody.take(200)}")
                                if (aclBody.contains("AclMode") || aclBody.contains("BlackMacList") || aclBody.length > 30) {
                                    debug.appendLine("✅ Session valid!")
                                    storage.saveCredentials(routerIp, username, password)
                                    storage.setLoggedIn(true)
                                    loginDebug = debug.toString()
                                    return@withContext Result.success("تم الاتصال ($label)")
                                }
                            }
                            body.contains("\"result\":\"3\"") || body.contains("\"result\":3") -> debug.appendLine("❌ Wrong password")
                            else -> debug.appendLine("❓ Unknown: ${body.take(100)}")
                        }
                    } catch (e: Exception) { debug.appendLine("$label error: ${e.message}") }
                }

                debug.appendLine("\n=== ALL FAILED ===")
                loginDebug = debug.toString()
                Result.failure(Exception("فشل الدخول"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطأ: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // التحقق من الجلسة
    // ═══════════════════════════════════════════

    private fun verifySession(client: okhttp3.OkHttpClient, base: String, debug: StringBuilder): String {
        return try {
            val req = okhttp3.Request.Builder()
                .url("$base/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$base/index.html").build()
            val body = client.newCall(req).execute().body?.string() ?: ""
            debug.appendLine("ACL verify: ${body.take(200)}")
            body
        } catch (e: Exception) {
            debug.appendLine("ACL verify error: ${e.message}")
            ""
        }
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
