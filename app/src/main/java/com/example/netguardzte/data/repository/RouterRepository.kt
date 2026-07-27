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
import java.io.File
import java.io.InputStreamReader
import java.net.URLEncoder
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
    // مساعدة عامة
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
    // حظر جهاز — goformId: setDeviceAccessControlList (من service.js)
    // ═══════════════════════════════════════════════════════════════════

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()

                debug.appendLine("=== BLOCK $macUpper ===")

                // ─── الخطوة 1: اقرأ القائمة السوداء الحالية من الراوتر ───
                debug.appendLine("\n--- Step 1: Read current ACL ---")
                val currentAcl = readCurrentACL(api, debug)

                val currentAclMode = currentAcl["AclMode"] ?: "0"
                val currentBlackListRaw = currentAcl["BlackMacList"] ?: ""
                debug.appendLine("Current AclMode: $currentAclMode")
                debug.appendLine("Current BlackMacList: $currentBlackListRaw")

                // استخراج عناوين MAC الموجودة فعلياً
                val existingMacs = currentBlackListRaw
                    .split(";")
                    .map { it.trim().uppercase() }
                    .filter {
                        it.isNotEmpty() &&
                        it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
                    }
                    .toMutableList()

                debug.appendLine("Parsed existing MACs: $existingMacs")

                // ─── الخطوة 2: أضف MAC الجديد إذا لم يكن موجوداً ───
                debug.appendLine("\n--- Step 2: Add MAC to list ---")
                if (macUpper !in existingMacs) {
                    existingMacs.add(macUpper)
                    debug.appendLine("Added $macUpper to list")
                } else {
                    debug.appendLine("$macUpper already in list")
                }

                val newBlackList = existingMacs.joinToString(";") + ";"
                debug.appendLine("New BlackMacList: $newBlackList")

                // ─── الخطوة 3: أرسل أمر setDeviceAccessControlList ───
                // goformId الصحيح من service.js دوال l_() و p_()
                debug.appendLine("\n--- Step 3: Send block command ---")

                val body = buildString {
                    append("isTest=false")
                    append("&goformId=setDeviceAccessControlList")
                    append("&AclMode=2")
                    append("&BlackMacList=${encodeParam(newBlackList)}")
                    append("&WhiteMacList=")
                    append("&WhiteNameList=")
                    append("&BlackNameList=")
                }

                debug.appendLine("Request: POST /goform/goform_set_cmd_process")
                debug.appendLine("Body: $body")

                var commandSucceeded = false
                var responseBody = ""

                try {
                    val r = api.postRaw(
                        body.toRequestBody(
                            "application/x-www-form-urlencoded".toMediaType()
                        )
                    )
                    responseBody = r.body()?.string() ?: ""
                    debug.appendLine("Response: $responseBody")

                    if (isSuccess(responseBody)) {
                        debug.appendLine("✅ Command accepted by router")
                        commandSucceeded = true
                    } else {
                        debug.appendLine("❌ Command rejected: $responseBody")
                    }
                } catch (e: Exception) {
                    debug.appendLine("POST error: ${e.message}")
                }

                // ─── الخطوة 4: تحقق من القائمة بعد الإرسال ───
                debug.appendLine("\n--- Step 4: Verify ---")

                if (commandSucceeded) {
                    Thread.sleep(1500)
                    val verifyAcl = readCurrentACL(api, debug)
                    val verifyBlackList = verifyAcl["BlackMacList"] ?: ""
                    val verifyAclMode = verifyAcl["AclMode"] ?: "0"

                    debug.appendLine("Verified AclMode: $verifyAclMode")
                    debug.appendLine("Verified BlackMacList: $verifyBlackList")

                    val isInBlacklist = verifyBlackList.uppercase().contains(macUpper)
                    val modeIsBlacklist = verifyAclMode == "2"

                    debug.appendLine("MAC in list: $isInBlacklist")
                    debug.appendLine("Mode is blacklist: $modeIsBlacklist")

                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()

                    if (isInBlacklist && modeIsBlacklist) {
                        debug.appendLine("✅ BLOCK VERIFIED SUCCESSFULLY!")
                        return@withContext Result.success("تم حظر الجهاز $macUpper")
                    } else if (isInBlacklist) {
                        debug.appendLine("⚠️ MAC added but mode may not be correct")
                        return@withContext Result.success("تم حظر الجهاز (تحقق من الوضع)")
                    } else {
                        debug.appendLine("❌ MAC NOT found in blacklist after command")
                        return@withContext Result.failure(
                            Exception("تم الإرسال لكن التحقق فشل - MAC غير موجود في القائمة")
                        )
                    }
                } else {
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext Result.failure(
                        Exception("فشل الحظر: $responseBody")
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطأ: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // إلغاء حظر جهاز — goformId: setDeviceAccessControlList
    // ═══════════════════════════════════════════════════════════════════

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()

                debug.appendLine("=== UNBLOCK $macUpper ===")

                // ─── اقرأ القائمة الحالية ───
                debug.appendLine("\n--- Read current ACL ---")
                val currentAcl = readCurrentACL(api, debug)
                val currentBlackListRaw = currentAcl["BlackMacList"] ?: ""
                debug.appendLine("Current BlackMacList: $currentBlackListRaw")

                // استخراج عناوين MAC الموجودة
                val existingMacs = currentBlackListRaw
                    .split(";")
                    .map { it.trim().uppercase() }
                    .filter {
                        it.isNotEmpty() &&
                        it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
                    }
                    .toMutableList()

                debug.appendLine("Existing MACs: $existingMacs")

                // أزل MAC المطلوب
                val removed = existingMacs.remove(macUpper)
                debug.appendLine("Removed $macUpper: $removed")
                debug.appendLine("Remaining MACs: $existingMacs")

                // ─── حدد وضع ACL ───
                val newAclMode: String
                val newBlackList: String

                if (existingMacs.isEmpty()) {
                    // لا توجد أجهزة محظورة → عطّل القائمة
                    newAclMode = "0"
                    newBlackList = ""
                    debug.appendLine("No more blocked devices, disabling ACL")
                } else {
                    newAclMode = "2"
                    newBlackList = existingMacs.joinToString(";") + ";"
                    debug.appendLine("Keeping blacklist with: $newBlackList")
                }

                // ─── أرسل الأمر ───
                debug.appendLine("\n--- Send unblock command ---")

                val body = buildString {
                    append("isTest=false")
                    append("&goformId=setDeviceAccessControlList")
                    append("&AclMode=$newAclMode")
                    append("&BlackMacList=${encodeParam(newBlackList)}")
                    append("&WhiteMacList=")
                    append("&WhiteNameList=")
                    append("&BlackNameList=")
                }

                debug.appendLine("Body: $body")

                try {
                    val r = api.postRaw(
                        body.toRequestBody(
                            "application/x-www-form-urlencoded".toMediaType()
                        )
                    )
                    val responseBody = r.body()?.string() ?: ""
                    debug.appendLine("Response: $responseBody")

                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()

                    if (isSuccess(responseBody)) {
                        debug.appendLine("✅ UNBLOCK SUCCESS!")
                        return@withContext Result.success("تم إلغاء حظر الجهاز $macUpper")
                    } else {
                        debug.appendLine("❌ UNBLOCK FAILED")
                        return@withContext Result.failure(Exception("فشل إلغاء الحظر: $responseBody"))
                    }
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
    // جلب قائمة الأجهزة المحظورة — cmd: queryDeviceAccessControlList
    // ═══════════════════════════════════════════════════════════════════

    suspend fun getBlockedMacs(): Result<List<String>> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()

                debug.appendLine("=== GET BLOCKED MACS ===")
                val aclData = readCurrentACL(api, debug)
                val blackListRaw = aclData["BlackMacList"] ?: ""
                val aclMode = aclData["AclMode"] ?: "0"

                debug.appendLine("AclMode: $aclMode")
                debug.appendLine("BlackMacList: $blackListRaw")

                val macs = blackListRaw
                    .split(";")
                    .map { it.trim().uppercase() }
                    .filter {
                        it.isNotEmpty() &&
                        it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
                    }

                debug.appendLine("Blocked MACs: $macs")
                allCommandsDebug = debug.toString()

                Result.success(macs)
            }
        } catch (e: Exception) {
            Result.failure(Exception("فشل جلب القائمة: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // تبديل حالة الحظر (حظر/إلغاء) بطلب واحد
    // ═══════════════════════════════════════════════════════════════════

    suspend fun toggleBlock(mac: String): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()

                debug.appendLine("=== TOGGLE BLOCK $macUpper ===")

                // اقرأ الوضع الحالي
                val currentAcl = readCurrentACL(api, debug)
                val blackListRaw = currentAcl["BlackMacList"] ?: ""

                val existingMacs = blackListRaw
                    .split(";")
                    .map { it.trim().uppercase() }
                    .filter {
                        it.isNotEmpty() &&
                        it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
                    }
                    .toMutableList()

                val isCurrentlyBlocked = macUpper in existingMacs
                debug.appendLine("Currently blocked: $isCurrentlyBlocked")

                if (isCurrentlyBlocked) {
                    // أزل من القائمة
                    existingMacs.remove(macUpper)
                    debug.appendLine("Removing from blacklist")
                } else {
                    // أضف للقائمة
                    existingMacs.add(macUpper)
                    debug.appendLine("Adding to blacklist")
                }

                val newAclMode = if (existingMacs.isEmpty()) "0" else "2"
                val newBlackList = if (existingMacs.isEmpty()) ""
                    else existingMacs.joinToString(";") + ";"

                debug.appendLine("New AclMode: $newAclMode")
                debug.appendLine("New BlackMacList: $newBlackList")

                val body = buildString {
                    append("isTest=false")
                    append("&goformId=setDeviceAccessControlList")
                    append("&AclMode=$newAclMode")
                    append("&BlackMacList=${encodeParam(newBlackList)}")
                    append("&WhiteMacList=")
                    append("&WhiteNameList=")
                    append("&BlackNameList=")
                }

                try {
                    val r = api.postRaw(
                        body.toRequestBody(
                            "application/x-www-form-urlencoded".toMediaType()
                        )
                    )
                    val responseBody = r.body()?.string() ?: ""
                    debug.appendLine("Response: $responseBody")

                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()

                    if (isSuccess(responseBody)) {
                        val action = if (isCurrentlyBlocked) "إلغاء حظر" else "حظر"
                        debug.appendLine("✅ $action SUCCESS!")
                        return@withContext Result.success("تم $action الجهاز $macUpper")
                    } else {
                        return@withContext Result.failure(Exception("فشل: $responseBody"))
                    }
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
    // قراءة ACL الحالي من الراوتر
    // cmd: queryDeviceAccessControlList (من service.js دالة d_())
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun readCurrentACL(
        api: Any,
        debug: StringBuilder
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val r = RetrofitClient.getApi().getGenericCmd(
                cmd = "queryDeviceAccessControlList"
            )
            val body = r.body()?.string() ?: ""
            debug.appendLine("ACL raw response: $body")

            // استخراج القيم من JSON
            // مثال: {"AclMode":"2","BlackMacList":"AA:BB:CC:DD:EE:FF;","WhiteMacList":"","WhiteNameList":"","BlackNameList":""}
            for (key in listOf(
                "AclMode",
                "BlackMacList",
                "WhiteMacList",
                "WhiteNameList",
                "BlackNameList"
            )) {
                val pattern = Regex(""""$key"\s*:\s*"([^"]*)"""")
                pattern.find(body)?.let {
                    result[key] = it.groupValues[1]
                }
            }

            // إذا لم يتم العثور على AclMode، جرب نمط آخر
            if ("AclMode" !in result) {
                val altPattern = Regex("""AclMode[^:]*:\s*"?(\d)"?""")
                altPattern.find(body)?.let {
                    result["AclMode"] = it.groupValues[1]
                }
            }

            debug.appendLine("Parsed ACL: $result")
        } catch (e: Exception) {
            debug.appendLine("Read ACL error: ${e.message}")
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
                val cookies = RetrofitClient.getCookiesString()

                debug.appendLine("=== TEST CONNECTION ===")
                debug.appendLine("Router IP: $routerIp")

                // اختبار بسيط
                try {
                    val r = RetrofitClient.getApi().getGenericCmd(cmd = "Language")
                    debug.appendLine("Language: ${r.body()?.string()}")
                } catch (e: Exception) {
                    debug.appendLine("Language error: ${e.message}")
                }

                // اختبار ACL
                debug.appendLine("\n=== TEST ACL ===")
                try {
                    val aclData = readCurrentACL(RetrofitClient.getApi(), debug)
                    debug.appendLine("ACL Data: $aclData")
                } catch (e: Exception) {
                    debug.appendLine("ACL error: ${e.message}")
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
    // أدوات مساعدة لقراءة ARP والأجهزة
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
            val f = File("/proc/net/arp")
            if (!f.exists() || !f.canRead()) return emptyList()
            r = BufferedReader(InputStreamReader(File(f.absolutePath).inputStream()))
            r.readLine() // skip header
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
            for (cmd in listOf(
                "station_list",
                "wifi_station_list",
                "dhcp_list",
                "client_list"
            )) {
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
