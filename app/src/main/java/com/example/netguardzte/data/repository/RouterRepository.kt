package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.api.ZteRouterApi
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

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
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun extractJsonField(json: String, field: String): String {
        Regex(""""$field"\s*:\s*"([^"]*?)"""").find(json)?.let { return it.groupValues[1] }
        Regex(""""$field"\s*:\s*([0-9a-fA-F]+)""").find(json)?.let { return it.groupValues[1] }
        Regex(""""$field"\s*:\s*"?([^",}]+)"?""").find(json)?.let { return it.groupValues[1].trim() }
        return ""
    }

    private fun readCookies(response: Response<*>, debug: StringBuilder) {
        try {
            for (c in response.headers().values("Set-Cookie")) {
                val parts = c.split(";")[0].split("=", limit = 2)
                if (parts.size == 2) {
                    RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
                }
            }
            cookieDebug = "Cookies: ${RetrofitClient.getCookiesString()}"
            debug.appendLine(cookieDebug)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // جلب قيمة من الراوتر
    // ═══════════════════════════════════════════════════════════════

    private suspend fun fetchValue(
        api: ZteRouterApi,
        name: String,
        debug: StringBuilder
    ): String {
        // الطريقة 1: cmd
        try {
            val r = api.getGenericCmd(cmd = name)
            val body = r.body()?.string() ?: ""
            debug.appendLine("GET cmd=$name → ${body.take(120)}")
            val value = extractJsonField(body, name)
            if (value.isNotBlank()) return value
        } catch (e: Exception) {
            debug.appendLine("GET cmd=$name error: ${e.message}")
        }

        // الطريقة 2: nv
        try {
            val r = api.getNvParam(nv = name)
            val body = r.body()?.string() ?: ""
            debug.appendLine("GET nv=$name → ${body.take(120)}")
            val value = extractJsonField(body, name)
            if (value.isNotBlank()) return value
        } catch (e: Exception) {
            debug.appendLine("GET nv=$name error: ${e.message}")
        }

        debug.appendLine("⚠️ $name not found!")
        return ""
    }

    // ═══════════════════════════════════════════════════════════════
    // حساب AD — MD5(MD5(wa_inner_version + cr_version) + RD)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun computeAdParameter(api: ZteRouterApi, debug: StringBuilder): String {
        try {
            val waInner = fetchValue(api, "wa_inner_version", debug)
            val crVersion = fetchValue(api, "cr_version", debug)
            if (waInner.isBlank() || crVersion.isBlank()) {
                debug.appendLine("⚠️ No versions for AD")
                return ""
            }

            val rd = fetchValue(api, "RD", debug)
            if (rd.isBlank()) {
                debug.appendLine("⚠️ No RD for AD")
                return ""
            }

            val ad = md5(md5(waInner + crVersion) + rd)
            debug.appendLine("AD=$ad")
            return ad
        } catch (e: Exception) {
            debug.appendLine("AD error: ${e.message}")
            return ""
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // تسجيل الدخول — مع AD + كل طرق التشفير
    // ═══════════════════════════════════════════════════════════════

    suspend fun login(
        routerIp: String,
        username: String,
        password: String
    ): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                debug.appendLine("=== LOGIN START ===")
                debug.appendLine("Router: $routerIp")
                debug.appendLine("User: $username")

                // ═══ 1. جهز الاتصال ═══
                debug.appendLine("\n--- Setup connection ---")
                RetrofitClient.reset()
                RetrofitClient.setRouterAddress(routerIp)
                var api = RetrofitClient.getApi()

                // ═══ 2. LOGOUT لمسح الجلسات القديمة ═══
                debug.appendLine("\n--- Clear stale sessions ---")
                try { api.logout() } catch (_: Exception) {}

                // أعد الاتصال بعد الـ logout
                RetrofitClient.reset()
                RetrofitClient.setRouterAddress(routerIp)
                api = RetrofitClient.getApi()

                // ═══ 3. احصل على الكوكيز ═══
                debug.appendLine("\n--- Get cookies ---")
                try {
                    val mainPage = api.getMainPage()
                    debug.appendLine("Main page: ${mainPage.code()}")
                    readCookies(mainPage, debug)
                } catch (e: Exception) {
                    debug.appendLine("Main page error: ${e.message}")
                }

                // ═══ 4. احسب AD (مطلوب مع LOGIN) ═══
                debug.appendLine("\n--- Compute AD ---")
                val adValue = computeAdParameter(api, debug)
                debug.appendLine("AD for login: $adValue")

                // ═══ 5. احصل على LD للتشفير ═══
                debug.appendLine("\n--- Get LD ---")
                val ld = fetchValue(api, "LD", debug)
                debug.appendLine("LD: $ld")

                // ═══ 6. جهز طرق التشفير ═══
                debug.appendLine("\n--- Prepare encodings ---")
                val encodings = mutableListOf<Pair<String, String>>()

                if (ld.isNotBlank()) {
                    val hash = sha256(sha256(password) + ld)
                    debug.appendLine("SHA256+LD: $hash")
                    encodings.add("SHA256+LD" to hash)
                }

                val b64 = Base64.encodeToString(
                    password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                )
                debug.appendLine("Base64: $b64")
                encodings.add("Base64" to b64)
                encodings.add("Plain" to password)
                encodings.add("MD5" to md5(password))
                encodings.add("SHA256" to sha256(password))

                debug.appendLine("Will try ${encodings.size} encodings, all with AD=$adValue")

                // ═══ 7. جرب كل طريقة مع AD ═══
                for ((label, encodedPass) in encodings) {
                    debug.appendLine("\n=== Try: $label ===")

                    try {
                        // أعد الاتصال لكل محاولة
                        RetrofitClient.reset()
                        RetrofitClient.setRouterAddress(routerIp)
                        val freshApi = RetrofitClient.getApi()

                        // احصل على كوكيز نظيفة
                        try {
                            val freshPage = freshApi.getMainPage()
                            readCookies(freshPage, debug)
                        } catch (_: Exception) {}

                        // أرسل LOGIN مع password + AD
                        debug.appendLine("Sending: password=${encodedPass.take(20)}..., AD=$adValue")
                        val response = freshApi.login(
                            password = encodedPass,
                            ad = adValue
                        )
                        val body = response.body()?.string() ?: ""
                        debug.appendLine("Response: ${body.take(200)}")
                        readCookies(response, debug)

                        when {
                            body.contains("\"result\":\"0\"") || body.contains("\"result\":0") -> {
                                debug.appendLine("✅ LOGIN SUCCESS with $label!")
                                storage.saveCredentials(routerIp, username, password)
                                storage.setLoggedIn(true)
                                loginDebug = debug.toString()
                                return@withContext Result.success("تم الاتصال بالراوتر")
                            }
                            body.contains("\"result\":\"1\"") || body.contains("\"result\":1") -> {
                                debug.appendLine("⚠️ Duplicate, retrying...")
                                try { freshApi.logout() } catch (_: Exception) {}
                                Thread.sleep(1000)

                                // إعادة المحاولة
                                RetrofitClient.reset()
                                RetrofitClient.setRouterAddress(routerIp)
                                val retryApi = RetrofitClient.getApi()
                                try { retryApi.getMainPage() } catch (_: Exception) {}

                                // احسب AD جديد
                                val retryAd = computeAdParameter(retryApi, debug)
                                debug.appendLine("Retry AD: $retryAd")

                                val retryResp = retryApi.login(password = encodedPass, ad = retryAd)
                                val retryBody = retryResp.body()?.string() ?: ""
                                debug.appendLine("Retry: ${retryBody.take(200)}")
                                readCookies(retryResp, debug)

                                if (retryBody.contains("\"result\":\"0\"") || retryBody.contains("\"result\":0")) {
                                    debug.appendLine("✅ LOGIN SUCCESS after retry!")
                                    storage.saveCredentials(routerIp, username, password)
                                    storage.setLoggedIn(true)
                                    loginDebug = debug.toString()
                                    return@withContext Result.success("تم الاتصال بالراوتر")
                                }
                            }
                            body.contains("\"result\":\"3\"") || body.contains("\"result\":3") -> {
                                debug.appendLine("❌ Wrong password with $label")
                            }
                            body.contains("\"result\":\"5\"") || body.contains("\"result\":5") -> {
                                debug.appendLine("❌ Account locked with $label")
                            }
                            else -> {
                                debug.appendLine("❓ Unknown: ${body.take(100)}")
                            }
                        }
                    } catch (e: Exception) {
                        debug.appendLine("$label error: ${e.message}")
                    }
                }

                debug.appendLine("\n=== ALL FAILED ===")
                loginDebug = debug.toString()
                Result.failure(Exception("فشل تسجيل الدخول بكل الطرق"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطأ: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // التأكد من تسجيل الدخول
    // ═══════════════════════════════════════════

    private suspend fun ensureLoggedIn(api: ZteRouterApi, debug: StringBuilder) {
        try {
            val loginfoR = api.getGenericCmd(cmd = "loginfo")
            val loginfoBody = loginfoR.body()?.string() ?: ""
            debug.appendLine("loginfo: ${loginfoBody.take(100)}")

            if (loginfoBody.contains("\"loginfo\":\"ok\"") || loginfoBody.contains("\"loginfo\":1")) {
                debug.appendLine("✅ Already logged in")
                return
            }

            debug.appendLine("Not logged in, re-logging...")
            val result = login(storage.getRouterIp(), storage.getUsername(), storage.getPassword())
            debug.appendLine("Re-login: ${result.isSuccess}")
        } catch (e: Exception) {
            debug.appendLine("ensureLoggedIn error: ${e.message}")
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

                flushArpCache(debug)
                forceArpEntries(subnet, debug)

                var devices = readArpFromAllSources(debug)
                debug.appendLine("Found: ${devices.size}")

                if (devices.isEmpty()) {
                    devices = readFromRouterApi(debug)
                }

                if (devices.isNotEmpty()) {
                    lastWorkingCommand = "ARP"
                    lastRawResponse = "${devices.size} devices"
                    allCommandsDebug = debug.toString()
                    return@withContext Result.success(devices)
                }

                allCommandsDebug = debug.toString()
                Result.failure(Exception("لم يتم العثور على أجهزة"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("فشل: ${e.message}"))
        }
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

                ensureLoggedIn(api, debug)

                val currentAcl = readCurrentACL(api, debug)
                val currentBlackListRaw = currentAcl["BlackMacList"] ?: ""
                val existingMacs = currentBlackListRaw.split(";")
                    .map { it.trim().uppercase() }
                    .filter { it.isNotEmpty() && it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
                    .toMutableList()

                if (macUpper !in existingMacs) existingMacs.add(macUpper)
                val newBlackList = existingMacs.joinToString(";") + ";"

                val adValue = computeAdParameter(api, debug)

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
                        Thread.sleep(1500)
                        val verify = readCurrentACL(api, debug)
                        val verified = (verify["BlackMacList"] ?: "").uppercase().contains(macUpper)
                        debug.appendLine("Verified: $verified")

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
        } catch (e: Exception) {
            Result.failure(Exception("خطأ: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // إلغاء حظر
    // ═══════════════════════════════════════════════════════════════

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

                val adValue = computeAdParameter(api, debug)

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
        } catch (e: Exception) {
            Result.failure(Exception("خطأ: ${e.message}"))
        }
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
        } catch (e: Exception) {
            Result.failure(Exception("فشل: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // قراءة ACL
    // ═══════════════════════════════════════════

    private suspend fun readCurrentACL(api: ZteRouterApi, debug: StringBuilder): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val r = api.getGenericCmd(cmd = "queryDeviceAccessControlList")
            val body = r.body()?.string() ?: ""
            debug.appendLine("ACL raw: ${body.take(200)}")

            for (key in listOf("AclMode", "BlackMacList", "WhiteMacList", "WhiteNameList", "BlackNameList")) {
                Regex(""""$key"\s*:\s*"([^"]*?)"""").find(body)?.let { result[key] = it.groupValues[1] }
            }
        } catch (e: Exception) {
            debug.appendLine("ACL error: ${e.message}")
        }
        return result
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
                } catch (e: Exception) {
                    debug.appendLine("Error: ${e.message}")
                }

                ensureLoggedIn(api, debug)

                debug.appendLine("\n=== LD TEST ===")
                val ld = fetchValue(api, "LD", debug)
                debug.appendLine("LD=$ld")

                debug.appendLine("\n=== AD TEST ===")
                val ad = computeAdParameter(api, debug)
                debug.appendLine("AD=$ad")

                debug.appendLine("\n=== ACL TEST ===")
                val acl = readCurrentACL(api, debug)
                debug.appendLine("ACL=$acl")

                Result.success(debug.toString())
            }
        } catch (e: Exception) {
            Result.failure(Exception("Test: ${e.message}"))
        }
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
        RetrofitClient.reset()
    }

    // ═══════════════════════════════════════════
    // أدوات ARP
    // ═══════════════════════════════════════════

    private fun flushArpCache(debug: StringBuilder) {
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh flush dev wlan0")).waitFor() } catch (_: Exception) {}
    }

    private fun forceArpEntries(subnet: String, debug: StringBuilder) {
        try {
            for (i in 1..50) {
                try {
                    val s = java.net.Socket()
                    s.connect(java.net.InetSocketAddress("$subnet.$i", 80), 30)
                    s.close()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

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
                    if (b.length > 30) {
                        val d = parseDevices(b)
                        if (d.isNotEmpty()) return d
                    }
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
