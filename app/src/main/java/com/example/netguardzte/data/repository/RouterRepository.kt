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

    private fun readCookies(response: Response<*>, debug: StringBuilder) {
        try {
            for (c in response.headers().values("Set-Cookie")) {
                val parts = c.split(";")[0].split("=", limit = 2)
                if (parts.size == 2) {
                    RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
                }
            }
            val cookies = RetrofitClient.getCookiesString()
            cookieDebug = "Cookies: $cookies"
            debug.appendLine(cookieDebug)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════
    // جلب قيمة من الراوتر
    // ═══════════════════════════════════════════════

    private suspend fun fetchValue(api: ZteRouterApi, name: String, debug: StringBuilder): String {
        try {
            val r = api.getGenericCmd(cmd = name)
            val body = r.body()?.string() ?: ""
            debug.appendLine("GET $name → ${body.take(120)}")
            val value = extractJsonField(body, name)
            if (value.isNotBlank()) return value
        } catch (e: Exception) {
            debug.appendLine("GET $name error: ${e.message}")
        }
        return ""
    }

    // ═══════════════════════════════════════════════
    // حساب AD
    // ═══════════════════════════════════════════════

    private suspend fun computeAd(api: ZteRouterApi, debug: StringBuilder): String {
        try {
            val waInner = fetchValue(api, "wa_inner_version", debug)
            val crVersion = fetchValue(api, "cr_version", debug)
            val rd = fetchValue(api, "RD", debug)

            if (waInner.isBlank() || crVersion.isBlank() || rd.isBlank()) {
                debug.appendLine("⚠️ Missing data for AD")
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
    // تسجيل الدخول
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

                // ═══ 1. مسح الجلسات القديمة ═══
                debug.appendLine("\n--- Force logout ---")
                try {
                    RetrofitClient.reset()
                    RetrofitClient.setRouterAddress(routerIp)
                    val api = RetrofitClient.getApi()

                    // أرسل LOGOUT بدون مصادقة
                    val logoutR = api.logout()
                    debug.appendLine("Logout: ${logoutR.body()?.string()}")
                    readCookies(logoutR, debug)

                    // انتظر ثانيتين
                    Thread.sleep(2000)
                } catch (e: Exception) {
                    debug.appendLine("Logout error: ${e.message}")
                }

                // ═══ 2. اتصال نظيف ═══
                RetrofitClient.reset()
                RetrofitClient.setRouterAddress(routerIp)
                val api = RetrofitClient.getApi()

                // ═══ 3. احصل على الكوكيز ═══
                debug.appendLine("\n--- Get cookies ---")
                try {
                    val mainPage = api.getMainPage()
                    debug.appendLine("Main page: ${mainPage.code()}")
                    readCookies(mainPage, debug)
                } catch (e: Exception) {
                    debug.appendLine("Main page error: ${e.message}")
                }

                // ═══ 4. احسب AD ═══
                debug.appendLine("\n--- Compute AD ---")
                val adValue = computeAd(api, debug)

                // ═══ 5. احصل على LD ═══
                debug.appendLine("\n--- Get LD ---")
                val ld = fetchValue(api, "LD", debug)

                // ═══ 6. جهز طرق التشفير — Base64 أولاً! ═══
                debug.appendLine("\n--- Prepare encodings ---")
                val encodings = mutableListOf<Pair<String, String>>()

                val b64 = Base64.encodeToString(
                    password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                )
                encodings.add("Base64" to b64)
                debug.appendLine("Base64: $b64")

                encodings.add("Plain" to password)

                if (ld.isNotBlank()) {
                    val hash = sha256(sha256(password) + ld)
                    encodings.add("SHA256+LD" to hash)
                    debug.appendLine("SHA256+LD: $hash")
                }

                encodings.add("MD5" to md5(password))
                encodings.add("SHA256" to sha256(password))

                // ═══ 7. جرب كل طريقة مع isForce ═══
                for ((label, encodedPass) in encodings) {
                    debug.appendLine("\n=== Try: $label ===")

                    try {
                        debug.appendLine("Sending: pass=${encodedPass.take(30)}... AD=$adValue isForce=1")

                        val response = api.login(
                            password = encodedPass,
                            ad = adValue,
                            isForce = "1"
                        )
                        val body = response.body()?.string() ?: ""
                        debug.appendLine("Response: $body")
                        readCookies(response, debug)

                        when {
                            // ═══ نجاح ═══
                            body.contains("\"result\":\"0\"") || body.contains("\"result\":0") -> {
                                debug.appendLine("✅ LOGIN SUCCESS with $label!")

                                // تحقق
                                debug.appendLine("\n--- Verify ---")
                                val aclR = api.getGenericCmd(cmd = "queryDeviceAccessControlList")
                                val aclBody = aclR.body()?.string() ?: ""
                                debug.appendLine("ACL: ${aclBody.take(200)}")

                                if (aclBody.contains("AclMode") || aclBody.length > 30) {
                                    debug.appendLine("✅ VERIFIED!")
                                }

                                storage.saveCredentials(routerIp, username, password)
                                storage.setLoggedIn(true)
                                loginDebug = debug.toString()
                                return@withContext Result.success("تم الاتصال بالراوتر")
                            }

                            // ═══ result:1 = مسجل مسبقاً ═══
                            body.contains("\"result\":\"1\"") || body.contains("\"result\":1") -> {
                                debug.appendLine("⚠️ Duplicate session with $label")

                                // جرب تحقق مباشرة (الجلسة قد تكون صالحة)
                                val aclR = api.getGenericCmd(cmd = "queryDeviceAccessControlList")
                                val aclBody = aclR.body()?.string() ?: ""
                                debug.appendLine("ACL check: ${aclBody.take(200)}")

                                if (aclBody.contains("AclMode") || aclBody.contains("BlackMacList")) {
                                    debug.appendLine("✅ Session is valid despite result:1!")
                                    storage.saveCredentials(routerIp, username, password)
                                    storage.setLoggedIn(true)
                                    loginDebug = debug.toString()
                                    return@withContext Result.success("تم الاتصال بالراوتر")
                                }

                                debug.appendLine("Session not valid, trying next encoding...")
                            }

                            // ═══ result:3 = كلمة مرور خاطئة ═══
                            body.contains("\"result\":\"3\"") || body.contains("\"result\":3") -> {
                                debug.appendLine("❌ Wrong password with $label")
                            }

                            else -> {
                                debug.appendLine("❓ Unknown: ${body.take(100)}")
                            }
                        }
                    } catch (e: Exception) {
                        debug.appendLine("$label error: ${e.message}")
                    }
                }

                // ═══ 8. فشلت كل المحاولات — جرب بدون isForce ═══
                debug.appendLine("\n=== Try without isForce ===")
                for ((label, encodedPass) in encodings.take(2)) {
                    try {
                        debug.appendLine("\n--- $label (no force) ---")

                        RetrofitClient.reset()
                        RetrofitClient.setRouterAddress(routerIp)
                        val freshApi = RetrofitClient.getApi()
                        try { freshApi.getMainPage() } catch (_: Exception) {}

                        val freshAd = computeAd(freshApi, debug)
                        val response = freshApi.login(password = encodedPass, ad = freshAd)
                        val body = response.body()?.string() ?: ""
                        debug.appendLine("Response: $body")
                        readCookies(response, debug)

                        if (body.contains("\"result\":\"0\"") || body.contains("\"result\":0")) {
                            debug.appendLine("✅ SUCCESS without force!")
                            storage.saveCredentials(routerIp, username, password)
                            storage.setLoggedIn(true)
                            loginDebug = debug.toString()
                            return@withContext Result.success("تم الاتصال بالراوتر")
                        }

                        if (body.contains("\"result\":\"1\"") || body.contains("\"result\":1")) {
                            // تحقق مرة أخرى
                            val aclR = freshApi.getGenericCmd(cmd = "queryDeviceAccessControlList")
                            val aclBody = aclR.body()?.string() ?: ""
                            debug.appendLine("ACL: ${aclBody.take(200)}")
                            if (aclBody.contains("AclMode") || aclBody.contains("BlackMacList")) {
                                debug.appendLine("✅ ACL works despite result:1!")
                                storage.saveCredentials(routerIp, username, password)
                                storage.setLoggedIn(true)
                                loginDebug = debug.toString()
                                return@withContext Result.success("تم الاتصال بالراوتر")
                            }
                        }
                    } catch (e: Exception) {
                        debug.appendLine("Error: ${e.message}")
                    }
                }

                debug.appendLine("\n=== ALL FAILED ===")
                debug.appendLine("Hint: تأكد أنك غير مسجل في المتصفح")
                loginDebug = debug.toString()
                Result.failure(Exception("فشل الدخول - أغلق صفحة الراوتر في المتصفح أولاً"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطأ: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // التأكد من تسجيل الدخول
    // ═══════════════════════════════════════════

    private suspend fun ensureLoggedIn(api: ZteRouterApi, debug: StringBuilder): Boolean {
        try {
            // اختبر مباشرة بقراءة ACL
            val aclR = api.getGenericCmd(cmd = "queryDeviceAccessControlList")
            val aclBody = aclR.body()?.string() ?: ""
            debug.appendLine("ACL check: ${aclBody.take(200)}")

            if (aclBody.contains("AclMode") || aclBody.contains("BlackMacList")) {
                debug.appendLine("✅ Authenticated")
                return true
            }

            // غير مسجل — أعد الدخول
            debug.appendLine("Not authenticated, re-logging...")
            val result = login(storage.getRouterIp(), storage.getUsername(), storage.getPassword())
            debug.appendLine("Re-login: ${result.isSuccess}")
            return result.isSuccess
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

                try {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh flush dev wlan0")).waitFor()
                } catch (_: Exception) {}

                for (i in 1..50) {
                    try {
                        val s = java.net.Socket()
                        s.connect(java.net.InetSocketAddress("$subnet.$i", 80), 30)
                        s.close()
                    } catch (_: Exception) {}
                }

                var devices = readArpFromAllSources(debug)
                debug.appendLine("Found: ${devices.size}")

                if (devices.isEmpty()) {
                    devices = readFromRouterApi(debug)
                }

                if (devices.isNotEmpty()) {
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

                val authenticated = ensureLoggedIn(api, debug)
                if (!authenticated) {
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext Result.failure(Exception("غير مسجل دخول - أغلق المتصفح أولاً"))
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

                debug.appendLine("\n=== Cookies ===")
                debug.appendLine("Current: ${RetrofitClient.getCookiesString()}")

                debug.appendLine("\n=== AD TEST ===")
                val ad = computeAd(api, debug)

                debug.appendLine("\n=== ACL TEST ===")
                val acl = readCurrentACL(api, debug)
                debug.appendLine("ACL=$acl")

                debug.appendLine("\n=== LD TEST ===")
                val ld = fetchValue(api, "LD", debug)

                debug.appendLine("\n=== AUTH TEST ===")
                val loginfo = api.getGenericCmd(cmd = "loginfo")
                debug.appendLine("loginfo: ${loginfo.body()?.string()}")

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
