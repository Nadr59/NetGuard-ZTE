package com.example.netguardzte.data.repository

import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.api.ZteRouterApi
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    // ═══════════════════════════════════════════
    // أدوات مساعدة
    // ═══════════════════════════════════════════

    private fun encodeParam(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun isSuccess(body: String): Boolean {
        return body.contains("\"result\":\"success\"") ||
                body.contains("\"result\":0") ||
                body.contains("\"result\":\"0\"")
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun extractJsonField(json: String, field: String): String {
        val pattern = Regex(""""$field"\s*:\s*"([^"]*)"""")
        pattern.find(json)?.let { return it.groupValues[1] }
        return ""
    }

    // ═══════════════════════════════════════════════════════════════
    // جلب معامل NV (LD أو RD) — من wr({nv:"LD"}) في service.js
    // ═══════════════════════════════════════════════════════════════

    private suspend fun fetchNvValue(api: ZteRouterApi, nvName: String, debug: StringBuilder): String {
        try {
            // الطريقة 1: استخدم getNvParam (nv parameter)
            val r = api.getNvParam(nv = nvName)
            val body = r.body()?.string() ?: ""
            debug.appendLine("NV $nvName: $body")
            val value = extractJsonField(body, nvName)
            if (value.isNotBlank()) return value

            // الطريقة 2: جرب cmd parameter
            val r2 = api.getGenericCmd(cmd = nvName)
            val body2 = r2.body()?.string() ?: ""
            debug.appendLine("CMD $nvName: $body2")
            val value2 = extractJsonField(body2, nvName)
            if (value2.isNotBlank()) return value2

        } catch (e: Exception) {
            debug.appendLine("fetchNv $nvName error: ${e.message}")
        }
        return ""
    }

    // ═══════════════════════════════════════════════════════════════
    // تشفير كلمة المرور — من service.js دالة de()
    // WEB_ATTR_IF_SUPPORT_SHA256 = 2:
    //   password = SHA256(SHA256(plainPassword) + LD)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun encodePasswordForLogin(
        api: ZteRouterApi,
        plainPassword: String,
        debug: StringBuilder
    ): String {
        // اجلب LD
        val ld = fetchNvValue(api, "LD", debug)
        debug.appendLine("LD=$ld")

        if (ld.isBlank()) {
            debug.appendLine("⚠️ LD empty, using Base64 fallback")
            return android.util.Base64.encodeToString(
                plainPassword.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
        }

        // التشفير الصحيح: SHA256(SHA256(password) + LD)
        val step1 = sha256(plainPassword)
        debug.appendLine("SHA256(pass)=$step1")

        val step2 = sha256(step1 + ld)
        debug.appendLine("SHA256(hash+LD)=$step2")

        return step2
    }

    // ═══════════════════════════════════════════════════════════════
    // حساب معامل AD — من service.js دالة i()
    // AD = MD5(MD5(wa_inner_version + cr_version) + RD)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun computeAdParameter(api: ZteRouterApi, debug: StringBuilder): String {
        try {
            // اجلب wa_inner_version و cr_version
            val verR = api.getGenericCmd(cmd = "wa_inner_version,cr_version")
            val verBody = verR.body()?.string() ?: ""
            debug.appendLine("Versions: ${verBody.take(100)}")

            val waInner = extractJsonField(verBody, "wa_inner_version")
            val crVersion = extractJsonField(verBody, "cr_version")
            debug.appendLine("wa_inner=$waInner, cr=$crVersion")

            if (waInner.isBlank() || crVersion.isBlank()) {
                debug.appendLine("⚠️ No version info")
                return ""
            }

            // اجلب RD
            val rd = fetchNvValue(api, "RD", debug)
            debug.appendLine("RD=$rd")

            if (rd.isBlank()) {
                debug.appendLine("⚠️ RD empty")
                return ""
            }

            // احسب: MD5(MD5(wa_inner + cr_version) + RD)
            val step1 = md5(waInner + crVersion)
            debug.appendLine("MD5(versions)=$step1")

            val ad = md5(step1 + rd)
            debug.appendLine("AD=$ad")

            return ad

        } catch (e: Exception) {
            debug.appendLine("AD error: ${e.message}")
            return ""
        }
    }

    // ═══════════════════════════════════════════
    // تسجيل الدخول — مصحح مع SHA256 + LD
    // ═══════════════════════════════════════════

    suspend fun login(
        routerIp: String,
        username: String,
        password: String
    ): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                RetrofitClient.reset()
                RetrofitClient.setRouterAddress(routerIp)
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()

                for (attempt in 1..3) {
                    debug.appendLine("=== Login attempt $attempt ===")
                    try {
                        // 1. زر الصفحة الرئيسية للحصول على الكوكيز
                        if (attempt == 1) {
                            try {
                                val mainPage = api.getMainPage()
                                debug.appendLine("Main page: ${mainPage.code()}")
                                readCookies(mainPage, debug)
                            } catch (e: Exception) {
                                debug.appendLine("Main page error: ${e.message}")
                            }
                        }

                        // 2. شفر كلمة المرور: SHA256(SHA256(pass) + LD)
                        debug.appendLine("Encoding password...")
                        val encodedPassword = encodePasswordForLogin(api, password, debug)
                        debug.appendLine("Encoded: ${encodedPassword.take(20)}...")

                        // 3. أرسل طلب LOGIN
                        val response = api.login(password = encodedPassword)
                        val body = response.body()?.string() ?: ""
                        debug.appendLine("Login response: ${body.take(200)}")
                        readCookies(response, debug)

                        // 4. تحقق من النتيجة
                        when {
                            body.contains("\"result\":\"0\"") || body.contains("\"result\":0") -> {
                                // نجح
                                storage.saveCredentials(routerIp, username, password)
                                storage.setLoggedIn(true)
                                loginDebug = debug.toString()
                                return@withContext Result.success("تم الاتصال بالراوتر")
                            }
                            body.contains("\"result\":\"3\"") || body.contains("\"result\":3") -> {
                                // كلمة مرور خاطئة
                                if (attempt == 3) {
                                    loginDebug = debug.toString()
                                    return@withContext Result.failure(Exception("كلمة المرور خاطئة"))
                                }
                                continue
                            }
                            body.contains("\"result\":\"5\"") || body.contains("\"result\":5") -> {
                                // مستخدم مكرر
                                loginDebug = debug.toString()
                                return@withContext Result.failure(Exception("مستخدم مسجل مسبقاً"))
                            }
                            else -> {
                                debug.appendLine("Unexpected result, trying again...")
                                if (attempt == 3) {
                                    loginDebug = debug.toString()
                                    return@withContext Result.failure(Exception("فشل: $body"))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        debug.appendLine("Error: ${e.message}")
                    }
                }

                loginDebug = debug.toString()
                Result.failure(Exception("فشل تسجيل الدخول"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطأ: ${e.message}"))
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

                for (d in devices) {
                    debug.appendLine("  ${d.ip} | ${d.mac} | ${d.hostname}")
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
    // حظر جهاز — مع تشفير AD الصحيح
    // ═══════════════════════════════════════════════════════════════

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()

                debug.appendLine("=== BLOCK $macUpper ===")

                // 1. تأكد من تسجيل الدخول
                debug.appendLine("\n--- Ensure logged in ---")
                ensureLoggedIn(api, debug)

                // 2. اقرأ القائمة الحالية
                debug.appendLine("\n--- Read ACL ---")
                val currentAcl = readCurrentACL(api, debug)
                val currentBlackListRaw = currentAcl["BlackMacList"] ?: ""
                val existingMacs = currentBlackListRaw
                    .split(";")
                    .map { it.trim().uppercase() }
                    .filter { it.isNotEmpty() && it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
                    .toMutableList()
                debug.appendLine("Existing: $existingMacs")

                // 3. أضف MAC
                if (macUpper !in existingMacs) existingMacs.add(macUpper)
                val newBlackList = existingMacs.joinToString(";") + ";"
                debug.appendLine("New list: $newBlackList")

                // 4. احسب AD
                debug.appendLine("\n--- Compute AD ---")
                val adValue = computeAdParameter(api, debug)
                debug.appendLine("AD final: $adValue")

                // 5. أرسل الحظر مع AD
                debug.appendLine("\n--- Send block ---")
                val body = buildString {
                    append("isTest=false")
                    append("&goformId=setDeviceAccessControlList")
                    append("&AclMode=2")
                    append("&BlackMacList=${encodeParam(newBlackList)}")
                    append("&WhiteMacList=")
                    append("&WhiteNameList=")
                    append("&BlackNameList=")
                    if (adValue.isNotBlank()) {
                        append("&AD=${encodeParam(adValue)}")
                    }
                }
                debug.appendLine("Body: $body")

                try {
                    val r = api.postRaw(
                        body.toRequestBody("application/x-www-form-urlencoded".toMediaType())
                    )
                    val responseBody = r.body()?.string() ?: ""
                    debug.appendLine("Response: $responseBody")

                    if (isSuccess(responseBody)) {
                        debug.appendLine("✅ SUCCESS!")
                        Thread.sleep(1500)
                        val verify = readCurrentACL(api, debug)
                        val verified = (verify["BlackMacList"] ?: "").uppercase().contains(macUpper)
                        debug.appendLine("Verified: $verified")

                        lastRawResponse = debug.toString()
                        allCommandsDebug = debug.toString()
                        return@withContext Result.success("تم حظر $macUpper")
                    }

                    debug.appendLine("❌ Failed: $responseBody")
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
    // التأكد من تسجيل الدخول — يعيد الدخول إذا لزم الأمر
    // ═══════════════════════════════════════════════════════════════

    private suspend fun ensureLoggedIn(api: ZteRouterApi, debug: StringBuilder) {
        try {
            val loginfoR = api.getGenericCmd(cmd = "loginfo")
            val loginfoBody = loginfoR.body()?.string() ?: ""
            debug.appendLine("loginfo: ${loginfoBody.take(100)}")

            if (loginfoBody.contains("\"loginfo\":\"ok\"")) {
                debug.appendLine("Already logged in")
                return
            }

            debug.appendLine("Not logged in, re-logging...")
            val password = storage.getPassword()
            val routerIp = storage.getRouterIp()

            // احصل على الكوكيز
            try { api.getMainPage() } catch (_: Exception) {}

            // شفر كلمة المرور
            val encodedPass = encodePasswordForLogin(api, password, debug)

            val loginR = api.login(password = encodedPass)
            val loginBody = loginR.body()?.string() ?: ""
            debug.appendLine("Re-login: ${loginBody.take(100)}")
            readCookies(loginR, debug)

        } catch (e: Exception) {
            debug.appendLine("ensureLoggedIn error: ${e.message}")
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

                val existingMacs = currentBlackListRaw
                    .split(";")
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

                if (isSuccess(responseBody)) {
                    Result.success("تم إلغاء حظر $macUpper")
                } else {
                    Result.failure(Exception("فشل: $responseBody"))
                }
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

                val macs = blackListRaw
                    .split(";")
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
            debug.appendLine("ACL: ${body.take(200)}")

            for (key in listOf("AclMode", "BlackMacList", "WhiteMacList", "WhiteNameList", "BlackNameList")) {
                Regex(""""$key"\s*:\s*"([^"]*)"""").find(body)?.let { result[key] = it.groupValues[1] }
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
                for (port in listOf(80)) {
                    try {
                        val s = java.net.Socket()
                        s.connect(java.net.InetSocketAddress("$subnet.$i", port), 30)
                        s.close()
                    } catch (_: Exception) {}
                }
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
            p.waitFor()
            p.destroy()
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

    private fun readCookies(response: Response<*>, debug: StringBuilder) {
        try {
            for (c in response.headers().values("Set-Cookie")) {
                val parts = c.split(";")[0].split("=", limit = 2)
                if (parts.size == 2) RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
            }
            cookieDebug = "Cookies: ${RetrofitClient.getCookiesString()}"
            debug.appendLine(cookieDebug)
        } catch (_: Exception) {}
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
