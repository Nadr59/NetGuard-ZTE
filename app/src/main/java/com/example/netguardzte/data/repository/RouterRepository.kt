package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
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
    // أدوات مساعدة عامة
    // ═══════════════════════════════════════════

    private fun fetchUrl(url: String, cookies: String): String {
        return try {
            val request = Request.Builder().url(url).addHeader("Cookie", cookies).build()
            httpClient.newCall(request).execute().body?.string() ?: ""
        } catch (_: Exception) { "" }
    }

    private fun encodeParam(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun isSuccess(body: String): Boolean {
        return body.contains("\"result\":\"success\"") ||
                body.contains("\"result\":0") ||
                body.contains("\"result\":\"0\"") ||
                body.contains("successful")
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun extractJsonField(json: String, field: String): String {
        val pattern = Regex(""""$field"\s*:\s*"([^"]*)"""")
        pattern.find(json)?.let { return it.groupValues[1] }
        val pattern2 = Regex(""""$field"\s*:\s*([^,}\s]+)""")
        pattern2.find(json)?.let { return it.groupValues[1].trim('"') }
        return ""
    }

    // ═══════════════════════════════════════════
    // حساب معامل AD الأمني
    // ═══════════════════════════════════════════

    private suspend fun computeAdParameter(): String {
        val debug = StringBuilder()

        try {
            // الخطوة 1: احصل على wa_inner_version و cr_version
            val verResponse = RetrofitClient.getApi().getGenericCmd(
                cmd = "wa_inner_version,cr_version"
            )
            val verBody = verResponse.body()?.string() ?: ""
            debug.appendLine("Versions: $verBody")

            val waInner = extractJsonField(verBody, "wa_inner_version")
            val crVersion = extractJsonField(verBody, "cr_version")
            debug.appendLine("wa_inner=$waInner, cr=$crVersion")

            // الخطوة 2: احصل على RD
            val rdResponse = RetrofitClient.getApi().getGenericCmd(cmd = "RD")
            val rdBody = rdResponse.body()?.string() ?: ""
            debug.appendLine("RD raw: $rdBody")

            val rdValue = extractJsonField(rdBody, "RD")
            debug.appendLine("RD value=$rdValue")

            if (waInner.isBlank() && crVersion.isBlank()) {
                debug.appendLine("⚠️ No version info, AD will be empty")
                allCommandsDebug += "\n=== AD COMPUTE ===\n$debug"
                return ""
            }

            // الخطوة 3: احسب AD = MD5(MD5(wa_inner + cr_version) + RD)
            val step1 = md5(waInner + crVersion)
            debug.appendLine("Step1 hash: $step1")

            val ad = md5(step1 + rdValue)
            debug.appendLine("AD final: $ad")

            allCommandsDebug += "\n=== AD COMPUTE ===\n$debug"
            return ad

        } catch (e: Exception) {
            debug.appendLine("AD error: ${e.message}")
            allCommandsDebug += "\n=== AD COMPUTE ERROR ===\n$debug"
            return ""
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
                RetrofitClient.reset()
                RetrofitClient.setRouterAddress(routerIp)
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()

                val encodedPassword = Base64.encodeToString(
                    password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                )

                for (attempt in 1..3) {
                    debug.appendLine("=== Attempt $attempt ===")
                    try {
                        if (attempt == 1) {
                            try { api.getMainPage() } catch (_: Exception) {}
                        }

                        val response = api.login(password = encodedPassword)
                        val body = response.body()?.string() ?: ""
                        debug.appendLine("Body: ${body.take(200)}")
                        readCookies(response, debug)

                        if (body.contains("\"result\":\"3\"") || body.contains("\"result\":3")) {
                            if (attempt == 3) {
                                loginDebug = debug.toString()
                                return@withContext Result.failure(Exception("كلمة المرور خاطئة"))
                            }
                            continue
                        }

                        storage.saveCredentials(routerIp, username, password)
                        storage.setLoggedIn(true)
                        loginDebug = debug.toString()
                        return@withContext Result.success("تم الاتصال بالراوتر")
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

    // ═══════════════════════════════════════════════════════════════════
    // حظر جهاز — goformId: setDeviceAccessControlList
    // مع حساب AD الأمني
    // ═══════════════════════════════════════════════════════════════════

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()

                debug.appendLine("=== BLOCK $macUpper ===")

                // ─── الخطوة 1: اقرأ القائمة السوداء الحالية ───
                debug.appendLine("\n--- Step 1: Read ACL ---")
                val currentAcl = readCurrentACL(api, debug)
                val currentBlackListRaw = currentAcl["BlackMacList"] ?: ""
                debug.appendLine("Current BlackMacList: $currentBlackListRaw")

                val existingMacs = currentBlackListRaw
                    .split(";")
                    .map { it.trim().uppercase() }
                    .filter {
                        it.isNotEmpty() &&
                        it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
                    }
                    .toMutableList()
                debug.appendLine("Existing: $existingMacs")

                // ─── الخطوة 2: أضف MAC ───
                debug.appendLine("\n--- Step 2: Add MAC ---")
                if (macUpper !in existingMacs) existingMacs.add(macUpper)
                val newBlackList = existingMacs.joinToString(";") + ";"
                debug.appendLine("New list: $newBlackList")

                // ─── الخطوة 3: احسب AD ───
                debug.appendLine("\n--- Step 3: Compute AD ---")
                val adValue = computeAdParameter()
                debug.appendLine("AD=$adValue")

                // ─── الخطوة 4: جرب الحظر بثلاث طرق ───
                debug.appendLine("\n--- Step 4: Try block ---")

                // المحاولة 1: مع AD
                val result1 = tryBlockWithParams(api, newBlackList, adValue, debug, "with AD")

                if (result1) {
                    val verified = verifyBlock(api, macUpper, debug)
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext if (verified) {
                        Result.success("تم حظر $macUpper")
                    } else {
                        Result.success("تم الإرسال (لم يتحقق)")
                    }
                }

                // المحاولة 2: بدون AD
                debug.appendLine("\n--- Try without AD ---")
                val result2 = tryBlockWithParams(api, newBlackList, "", debug, "without AD")

                if (result2) {
                    val verified = verifyBlock(api, macUpper, debug)
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext if (verified) {
                        Result.success("تم حظر $macUpper")
                    } else {
                        Result.success("تم الإرسال (لم يتحقق)")
                    }
                }

                // المحاولة 3: إعادة تسجيل الدخول ثم المحاولة
                debug.appendLine("\n--- Try re-login ---")
                val result3 = tryReLoginAndBlock(api, macUpper, newBlackList, debug)

                if (result3) {
                    val verified = verifyBlock(api, macUpper, debug)
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext if (verified) {
                        Result.success("تم حظر $macUpper")
                    } else {
                        Result.success("تم الإرسال (لم يتحقق)")
                    }
                }

                // فشلت كل المحاولات
                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()
                return@withContext Result.failure(
                    Exception("فشل الحظر بعد كل المحاولات")
                )
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطأ: ${e.message}"))
        }
    }

    // ═══ محاولة حظر مع AD ═══
    private suspend fun tryBlockWithParams(
        api: com.example.netguardzte.data.api.ZteRouterApi,
        blackList: String,
        adValue: String,
        debug: StringBuilder,
        label: String
    ): Boolean {
        try {
            val body = buildString {
                append("isTest=false")
                append("&goformId=setDeviceAccessControlList")
                append("&AclMode=2")
                append("&BlackMacList=${encodeParam(blackList)}")
                append("&WhiteMacList=")
                append("&WhiteNameList=")
                append("&BlackNameList=")
                if (adValue.isNotBlank()) {
                    append("&AD=${encodeParam(adValue)}")
                }
            }

            debug.appendLine("[$label] Body: $body")

            val r = api.postRaw(
                body.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            )
            val responseBody = r.body()?.string() ?: ""
            debug.appendLine("[$label] Response: $responseBody")

            if (isSuccess(responseBody)) {
                debug.appendLine("[$label] ✅ SUCCESS!")
                return true
            }

            debug.appendLine("[$label] ❌ Failed")
            return false
        } catch (e: Exception) {
            debug.appendLine("[$label] Error: ${e.message}")
            return false
        }
    }

    // ═══ محاولة إعادة الدخول ثم الحظر ═══
    private suspend fun tryReLoginAndBlock(
        api: com.example.netguardzte.data.api.ZteRouterApi,
        mac: String,
        blackList: String,
        debug: StringBuilder
    ): Boolean {
        try {
            val password = storage.getPassword()
            val encodedPass = Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )

            val loginResponse = api.login(password = encodedPass)
            val loginBody = loginResponse.body()?.string() ?: ""
            debug.appendLine("Re-login: ${loginBody.take(100)}")

            // حفظ الكوكيز الجديدة
            for (c in loginResponse.headers().values("Set-Cookie")) {
                val parts = c.split(";")[0].split("=", limit = 2)
                if (parts.size == 2) {
                    RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
                }
            }

            // احسب AD جديد
            val newAd = computeAdParameter()
            debug.appendLine("New AD: $newAd")

            // جرب الحظر
            return tryBlockWithParams(api, blackList, newAd, debug, "after re-login")

        } catch (e: Exception) {
            debug.appendLine("Re-login error: ${e.message}")
            return false
        }
    }

    // ═══ التحقق من الحظر ═══
    private suspend fun verifyBlock(
        api: com.example.netguardzte.data.api.ZteRouterApi,
        mac: String,
        debug: StringBuilder
    ): Boolean {
        return try {
            Thread.sleep(1500)
            val verify = readCurrentACL(api, debug)
            val verifyBlackList = verify["BlackMacList"] ?: ""
            val verifyAclMode = verify["AclMode"] ?: "0"
            val isInList = verifyBlackList.uppercase().contains(mac)
            debug.appendLine("Verify: AclMode=$verifyAclMode, MAC in list=$isInList")
            isInList
        } catch (e: Exception) {
            debug.appendLine("Verify error: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // إلغاء حظر
    // ═══════════════════════════════════════════════════════════════════

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()

                debug.appendLine("=== UNBLOCK $macUpper ===")

                val currentAcl = readCurrentACL(api, debug)
                val currentBlackListRaw = currentAcl["BlackMacList"] ?: ""

                val existingMacs = currentBlackListRaw
                    .split(";")
                    .map { it.trim().uppercase() }
                    .filter {
                        it.isNotEmpty() &&
                        it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
                    }
                    .toMutableList()

                existingMacs.remove(macUpper)

                val newAclMode = if (existingMacs.isEmpty()) "0" else "2"
                val newBlackList = if (existingMacs.isEmpty()) ""
                    else existingMacs.joinToString(";") + ";"

                debug.appendLine("New AclMode: $newAclMode")
                debug.appendLine("New list: $newBlackList")

                // احسب AD
                val adValue = computeAdParameter()

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

                debug.appendLine("Body: $body")

                try {
                    val r = api.postRaw(
                        body.toRequestBody("application/x-www-form-urlencoded".toMediaType())
                    )
                    val responseBody = r.body()?.string() ?: ""
                    debug.appendLine("Response: $responseBody")

                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()

                    if (isSuccess(responseBody)) {
                        return@withContext Result.success("تم إلغاء حظر $macUpper")
                    }
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

    // ═══════════════════════════════════════════════════════════════════
    // جلب قائمة المحظورين
    // ═══════════════════════════════════════════════════════════════════

    suspend fun getBlockedMacs(): Result<List<String>> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()

                debug.appendLine("=== GET BLOCKED ===")
                val aclData = readCurrentACL(api, debug)
                val blackListRaw = aclData["BlackMacList"] ?: ""

                val macs = blackListRaw
                    .split(";")
                    .map { it.trim().uppercase() }
                    .filter {
                        it.isNotEmpty() &&
                        it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
                    }

                debug.appendLine("Blocked: $macs")
                allCommandsDebug = debug.toString()

                Result.success(macs)
            }
        } catch (e: Exception) {
            Result.failure(Exception("فشل: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // قراءة ACL من الراوتر
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun readCurrentACL(
        api: com.example.netguardzte.data.api.ZteRouterApi,
        debug: StringBuilder
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val r = api.getGenericCmd(cmd = "queryDeviceAccessControlList")
            val body = r.body()?.string() ?: ""
            debug.appendLine("ACL raw: $body")

            for (key in listOf(
                "AclMode", "BlackMacList", "WhiteMacList",
                "WhiteNameList", "BlackNameList"
            )) {
                val pattern = Regex(""""$key"\s*:\s*"([^"]*)"""")
                pattern.find(body)?.let { result[key] = it.groupValues[1] }
            }

            if ("AclMode" !in result) {
                val altPattern = Regex("""AclMode[^:]*:\s*"?(\d)"?""")
                altPattern.find(body)?.let { result["AclMode"] = it.groupValues[1] }
            }

            debug.appendLine("Parsed: $result")
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
                val routerIp = try { storage.getRouterIp() } catch (_: Exception) { "192.168.0.1" }

                debug.appendLine("=== TEST ===")
                debug.appendLine("Router: $routerIp")

                try {
                    val r = RetrofitClient.getApi().getGenericCmd(cmd = "Language")
                    debug.appendLine("Language: ${r.body()?.string()}")
                } catch (e: Exception) {
                    debug.appendLine("Error: ${e.message}")
                }

                debug.appendLine("\n=== ACL TEST ===")
                try {
                    val aclData = readCurrentACL(RetrofitClient.getApi(), debug)
                    debug.appendLine("ACL: $aclData")
                } catch (e: Exception) {
                    debug.appendLine("ACL error: ${e.message}")
                }

                debug.appendLine("\n=== AD TEST ===")
                try {
                    val ad = computeAdParameter()
                    debug.appendLine("AD: $ad")
                } catch (e: Exception) {
                    debug.appendLine("AD error: ${e.message}")
                }

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
    // أدوات ARP لاكتشاف الأجهزة
    // ═══════════════════════════════════════════

    private fun flushArpCache(debug: StringBuilder) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh flush dev wlan0"))
            p.waitFor()
        } catch (_: Exception) {}
    }

    private fun forceArpEntries(subnet: String, debug: StringBuilder) {
        try {
            for (i in 1..50) {
                for (port in listOf(80, 443)) {
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
        d = readArpFromCommand("arp -a"); if (d.isNotEmpty()) return d
        return readArpFromCommand("cat /proc/net/arp")
    }

    private fun readIpNeigh(debug: StringBuilder): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh"))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) {
                try {
                    if (!line.uppercase().contains("FAILED") &&
                        !line.uppercase().contains("INCOMPLETE")
                    ) {
                        parseArpLine(line)?.let { devices.add(it) }
                    }
                } catch (_: Exception) {}
                line = r.readLine()
            }
            p.waitFor()
        } catch (_: Exception) {}
        return devices
    }

    private fun readArpFromFile(): List<Device> {
        val devices = mutableListOf<Device>()
        var r: BufferedReader? = null
        try {
            val f = java.io.File("/proc/net/arp")
            if (!f.exists() || !f.canRead()) return emptyList()
            r = BufferedReader(InputStreamReader(f.inputStream()))
            r.readLine()
            var line = r.readLine()
            while (line != null) {
                try {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 4 &&
                        parts[3].uppercase() != "00:00:00:00:00:00" &&
                        parts[2] != "0x0"
                    ) {
                        devices.add(makeDevice(parts[0], parts[3].uppercase()))
                    }
                } catch (_: Exception) {}
                line = r.readLine()
            }
        } catch (_: Exception) {}
        finally { try { r?.close() } catch (_: Exception) {} }
        return devices
    }

    private fun readArpFromCommand(command: String): List<Device> {
        val devices = mutableListOf<Device>()
        var p: Process? = null
        try {
            p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) {
                try { parseArpLine(line)?.let { devices.add(it) } } catch (_: Exception) {}
                line = r.readLine()
            }
            p.waitFor()
        } catch (_: Exception) {}
        finally { try { p?.destroy() } catch (_: Exception) {} }
        return devices
    }

    private fun parseArpLine(line: String): Device? {
        val mac = Regex(
            "[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-]" +
            "[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}"
        ).find(line)?.value?.uppercase() ?: return null
        if (mac == "00:00:00:00:00:00") return null
        val ip = Regex("(\\d{1,3}\\.){3}\\d{1,3}").find(line)?.value ?: return null
        return makeDevice(ip, mac)
    }

    private suspend fun readFromRouterApi(debug: StringBuilder): List<Device> {
        try {
            val api = RetrofitClient.getApi()
            for (cmd in listOf("station_list", "wifi_station_list", "dhcp_list", "client_list")) {
                try {
                    val r = api.getGenericCmd(cmd = cmd)
                    val b = r.body()?.string() ?: ""
                    debug.appendLine("  [$cmd] -> ${b.take(100)}")
                    if (b.length > 30 && !b.contains("\"\":\"$cmd\"")) {
                        val d = parseDevices(b)
                        if (d.isNotEmpty()) return d
                    }
                } catch (e: Exception) {
                    debug.appendLine("  [$cmd] error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            debug.appendLine("  API error: ${e.message}")
        }
        return emptyList()
    }

    private fun makeDevice(ip: String, mac: String): Device {
        val rIp = try { storage.getRouterIp() } catch (_: Exception) { "" }
        return Device(
            mac = mac,
            ip = ip,
            hostname = nameFor(ip, mac),
            connectionType = if (ip == rIp) "Router" else "WiFi"
        )
    }

    private fun nameFor(ip: String, mac: String): String {
        val v = when {
            mac.startsWith("A4:83") || mac.startsWith("F0:18") || mac.startsWith("3C:2E") -> "Apple"
            mac.startsWith("CC:96") || mac.startsWith("58:48") || mac.startsWith("AC:CF") -> "Huawei"
            mac.startsWith("70:F9") || mac.startsWith("94:B8") || mac.startsWith("C0:BD") -> "Samsung"
            mac.startsWith("6C:B0") || mac.startsWith("54:FA") || mac.startsWith("AC:F7") -> "Xiaomi"
            mac.startsWith("58:7F") || mac.startsWith("74:51") || mac.startsWith("50:64") -> "Xiaomi"
            mac.startsWith("00:16") || mac.startsWith("50:C7") || mac.startsWith("EC:08") -> "TP-Link"
            mac.startsWith("88:66") || mac.startsWith("F4:F5") -> "Google"
            mac.startsWith("00:21") -> "ZTE"
            else -> ""
        }
        val s = ip.substringAfterLast(".")
        return when {
            v.isNotBlank() -> "$v ($s)"
            s == "1" -> "الراوتر"
            else -> "جهاز .$s"
        }
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

    private fun parseDevices(raw: String): List<Device> {
        try {
            val macs = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
                .findAll(raw)
                .map { it.value.uppercase() }
                .distinct()
                .toList()
            if (macs.isEmpty()) return emptyList()
            val ips = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
                .findAll(raw)
                .map { it.value }
                .toList()
            return macs.mapIndexed { i, mac ->
                Device(
                    mac = mac,
                    ip = ips.getOrNull(i) ?: "",
                    hostname = "جهاز ${i + 1}",
                    connectionType = "WiFi"
                )
            }
        } catch (_: Exception) { return emptyList() }
    }
}
