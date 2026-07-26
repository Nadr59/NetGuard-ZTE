package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // ═══════════════════════════════════════════
    // تسجيل الدخول — نجرب عدة طرق
    // ═══════════════════════════════════════════
    suspend fun login(
        routerIp: String,
        username: String,
        password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            RetrofitClient.reset()
            RetrofitClient.setRouterAddress(routerIp)

            val api = RetrofitClient.getApi()
            val debug = StringBuilder()

            // ═══ الخطوة 1: اطلب الصفحة الرئيسية للحصول على Cookies ═══
            debug.appendLine("=== STEP 1: Get initial page ===")
            try {
                val initResponse = api.getGenericCmd(cmd = "multi_login")
                debug.appendLine("Init code: ${initResponse.code()}")
                readCookies(initResponse, debug)
            } catch (e: Exception) {
                debug.appendLine("Init error: ${e.message}")
            }

            // ═══ الخطوة 2: جرب كلمة المرور بعدة طرق ═══
            val passwordMethods = listOf(
                "base64" to Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                "plain" to password,
                "sha256" to sha256(password),
                "sha256_base64" to Base64.encodeToString(sha256(password).toByteArray(), Base64.NO_WRAP)
            )

            for ((method, encodedPass) in passwordMethods) {
                debug.appendLine("\n=== STEP 2: Try login with $method ===")
                debug.appendLine("Password ($method): ${encodedPass.take(20)}...")

                try {
                    val response = api.login(password = encodedPass)
                    val body = response.body()?.string() ?: ""

                    debug.appendLine("Login code: ${response.code()}")
                    debug.appendLine("Login body: ${body.take(200)}")

                    // ═══ اقرأ Cookies من الاستجابة ═══
                    readCookies(response, debug)

                    cookieDebug = "Cookies: ${RetrofitClient.getCookiesString()}"

                    // ═══ تحقق من النتيجة ═══
                    if (body.contains("\"result\":\"0\"") || body.contains("\"result\":0")) {
                        debug.appendLine("LOGIN SUCCESS with $method!")
                        loginDebug = debug.toString()
                        storage.saveCredentials(routerIp, username, password)
                        storage.setLoggedIn(true)
                        return@withContext Result.success("تم الاتصال ($method)")
                    }

                    if (body.contains("\"result\":\"3\"") || body.contains("\"result\":3")) {
                        debug.appendLine("WRONG PASSWORD with $method")
                        continue
                    }

                    // ═══ result: 1 قد يكون نجاح في بعض الإصدارات ═══
                    if (body.contains("\"result\":\"1\"") || body.contains("\"result\":1)) {
                        // تحقق: هل هناك Cookies صالحة؟
                        if (RetrofitClient.getSessionCookie() != null) {
                            debug.appendLine("result=1 but cookies exist - treating as success")
                            loginDebug = debug.toString()
                            storage.saveCredentials(routerIp, username, password)
                            storage.setLoggedIn(true)
                            return@withContext Result.success("تم الاتصال ($method)")
                        }
                        debug.appendLine("result=1 with no cookies - trying next method")
                        continue
                    }

                    // ═══ أي نتيجة أخرى مع Cookies = نجاح ═══
                    if (response.isSuccessful && RetrofitClient.getSessionCookie() != null) {
                        debug.appendLine("Success with cookies using $method")
                        loginDebug = debug.toString()
                        storage.saveCredentials(routerIp, username, password)
                        storage.setLoggedIn(true)
                        return@withContext Result.success("تم الاتصال ($method)")
                    }

                } catch (e: Exception) {
                    debug.appendLine("Error with $method: ${e.message}")
                }
            }

            // ═══ لم ينجح أي طريقة ═══
            debug.appendLine("\n=== ALL METHODS FAILED ===")
            loginDebug = debug.toString()
            Result.failure(Exception("فشل تسجيل الدخول (جربنا ${passwordMethods.size} طرق)\n\n$loginDebug"))

        } catch (e: Exception) {
            loginDebug = "Exception: ${e.message}"
            Result.failure(Exception("لا يمكن الوصول للراوتر: ${e.message}"))
        }
    }

    // ═══ قراءة Cookies من الاستجابة ═══
    private fun readCookies(response: retrofit2.Response<*>, debug: StringBuilder) {
        val setCookies = response.headers().values("Set-Cookie")
        debug.appendLine("Set-Cookie headers: ${setCookies.size}")
        for (cookieHeader in setCookies) {
            debug.appendLine("  Cookie: ${cookieHeader.take(80)}")
            val parts = cookieHeader.split(";")[0].split("=", limit = 2)
            if (parts.size == 2) {
                RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
            }
        }
        cookieDebug = "Cookies: ${RetrofitClient.getCookiesString()}"
        debug.appendLine("Stored cookies: ${RetrofitClient.getCookiesString()}")
    }

    // ═══ SHA-256 ═══
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════════════════════════════
    // جلب الأجهزة
    // ═══════════════════════════════════════════
    suspend fun getConnectedDevices(): Result<List<Device>> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val debug = StringBuilder()

            cookieDebug = "Cookies: ${RetrofitClient.getCookiesString()}"

            val commands = listOf(
                "station_list",
                "wifi_station_list",
                "wifi_client_list",
                "dhcp_list",
                "client_list",
                "lan_station_list",
                "active_user_list",
                "connected_devices",
                "wlan_station_list",
                "multi_stations_list",
                "user_list",
                "station_list_5g",
                "wds_station_list"
            )

            debug.appendLine("=== DEVICE SCAN ===")
            debug.appendLine("Cookies: ${RetrofitClient.getCookiesString()}")

            for (cmdName in commands) {
                try {
                    val response = api.getGenericCmd(cmd = cmdName)
                    val rawBody = if (response.isSuccessful) {
                        response.body()?.string() ?: ""
                    } else {
                        "HTTP ${response.code()}"
                    }

                    debug.appendLine("\n[$cmdName] → ${response.code()}: ${rawBody.take(100)}")

                    if (response.isSuccessful && hasRealData(rawBody, cmdName)) {
                        lastRawResponse = rawBody
                        lastWorkingCommand = cmdName

                        val devices = tryAllParsingMethods(rawBody)
                        if (devices.isNotEmpty()) {
                            allCommandsDebug = debug.toString()
                            return@withContext Result.success(devices)
                        }
                    } else if (response.code() == 401) {
                        debug.appendLine("  → 401 Unauthorized! Re-logging in...")
                        autoRelogin()
                        debug.appendLine("  → Re-login done. Cookies: ${RetrofitClient.getCookiesString()}")
                    }
                } catch (e: Exception) {
                    debug.appendLine("[$cmdName] → ERROR: ${e.message}")
                }
            }

            allCommandsDebug = debug.toString()

            if (lastRawResponse.isNotBlank()) {
                Result.failure(
                    Exception(
                        "لم يتم العثور على أجهزة\n" +
                        "الأمر: $lastWorkingCommand\n" +
                        "الاستجابة: ${lastRawResponse.take(200)}"
                    )
                )
            } else {
                Result.failure(Exception("لا توجد استجابة من الراوتر\n\n$allCommandsDebug"))
            }

        } catch (e: Exception) {
            Result.failure(Exception("فشل: ${e.message}"))
        }
    }

    private fun hasRealData(body: String, cmdName: String): Boolean {
        if (body.isBlank()) return false
        if (body == "{}") return false
        if (body == "[]") return false
        if (body == "{\"\":\"$cmdName\"}") return false
        if (body.contains("\"\":\"$cmdName\"") && body.length < 50) return false
        if (Regex("[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}").containsMatchIn(body)) return true
        if (Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(body)) return true
        if (body.length > 50) return true
        return false
    }

    private fun tryAllParsingMethods(rawBody: String): List<Device> {
        if (rawBody.isBlank()) return emptyList()

        try {
            val root = JsonParser.parseString(rawBody)

            if (root.isJsonObject) {
                val obj = root.asJsonObject
                for (key in obj.keySet()) {
                    val element = obj.get(key) ?: continue
                    val devices = tryParseDeviceArray(element)
                    if (devices.isNotEmpty()) return devices
                }
            }

            if (root.isJsonArray) {
                val devices = tryParseDeviceArray(root)
                if (devices.isNotEmpty()) return devices
            }
        } catch (_: Exception) {}

        return tryParseWithRegex(rawBody)
    }

    private fun tryParseDeviceArray(element: JsonElement): List<Device> {
        return when {
            element.isJsonArray -> parseJsonArray(element.asJsonArray)
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                val str = element.asString
                if (str.isBlank()) return emptyList()
                try {
                    val parsed = JsonParser.parseString(str)
                    if (parsed.isJsonArray) parseJsonArray(parsed.asJsonArray)
                    else emptyList()
                } catch (_: Exception) { emptyList() }
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val list = obj.get("station_list") ?: obj.get("devices") ?: obj.get("clients")
                if (list != null) tryParseDeviceArray(list)
                else {
                    val d = parseSingleDevice(obj)
                    if (d != null) listOf(d) else emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun parseJsonArray(array: JsonArray): List<Device> {
        val devices = mutableListOf<Device>()
        for (item in array) {
            if (item.isJsonObject) {
                val d = parseSingleDevice(item.asJsonObject)
                if (d != null) devices.add(d)
            }
        }
        return devices
    }

    private fun parseSingleDevice(obj: JsonObject): Device? {
        val mac = findMac(obj)
        if (mac.isBlank()) return null

        return Device(
            mac = mac.uppercase(),
            ip = findField(obj, "ip", "ip_addr", "ipAddress", "address"),
            hostname = findField(obj, "hostname", "name", "host_name", "device_name", "client_name")
                .ifBlank { "جهاز غير معروف" },
            connectionType = findField(obj, "conn_type", "wlan_type", "type", "connection")
                .ifBlank { "WiFi" }
        )
    }

    private fun findMac(obj: JsonObject): String {
        val fields = listOf("mac", "mac_addr", "mac_address", "MacAddress", "MAC", "hwaddr")
        for (f in fields) {
            val v = getFieldStr(obj, f)
            if (v.isNotBlank() && isValidMac(v)) return v
        }
        for (key in obj.keySet()) {
            val v = getFieldStr(obj, key)
            if (isValidMac(v)) return v
        }
        return ""
    }

    private fun findField(obj: JsonObject, vararg names: String): String {
        for (n in names) {
            val v = getFieldStr(obj, n)
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun getFieldStr(obj: JsonObject, field: String): String {
        return try {
            val el = obj.get(field) ?: return ""
            when {
                el.isJsonNull -> ""
                el.isJsonPrimitive -> el.asString
                else -> el.toString()
            }
        } catch (_: Exception) { "" }
    }

    private fun isValidMac(v: String): Boolean {
        return Regex("[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}")
            .matches(v.trim())
    }

    private fun tryParseWithRegex(raw: String): List<Device> {
        val macPattern = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
        val ipPattern = Regex("(\\d{1,3}\\.){3}\\d{1,3}")

        val macs = macPattern.findAll(raw).map { it.value.uppercase() }.distinct().toList()
        if (macs.isEmpty()) return emptyList()

        val ips = ipPattern.findAll(raw).map { it.value }.toList()

        return macs.mapIndexed { i, mac ->
            Device(mac = mac, ip = ips.getOrNull(i) ?: "", hostname = "جهاز ${i + 1}", connectionType = "Unknown")
        }
    }

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val api = RetrofitClient.getApi()
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")
                val response = api.setMacFilter(macList = newList)

                if (response.isSuccessful) Result.success("تم حظر الجهاز")
                else if (response.code() == 401) {
                    autoRelogin()
                    val retry = RetrofitClient.getApi().setMacFilter(macList = newList)
                    if (retry.isSuccessful) Result.success("تم الحظر")
                    else Result.failure(Exception("فشل: ${retry.code()}"))
                } else Result.failure(Exception("فشل: ${response.code()}"))
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val api = RetrofitClient.getApi()
                val newList = currentBlockedList.filter { it.uppercase() != mac.uppercase() }.joinToString(";")
                val response = if (newList.isEmpty()) api.disableMacFilter() else api.setMacFilter(macList = newList)

                if (response.isSuccessful) Result.success("تم إلغاء الحظر")
                else if (response.code() == 401) {
                    autoRelogin()
                    val ra = RetrofitClient.getApi()
                    val r = if (newList.isEmpty()) ra.disableMacFilter() else ra.setMacFilter(macList = newList)
                    if (r.isSuccessful) Result.success("تم إلغاء الحظر") else Result.failure(Exception("فشل"))
                } else Result.failure(Exception("فشل: ${response.code()}"))
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun getBlockedMacs(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().getMacFilterList()
            if (response.isSuccessful) {
                val body = response.body()?.string() ?: ""
                Result.success(Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}").findAll(body).map { it.value.uppercase() }.toList())
            } else Result.success(emptyList())
        } catch (_: Exception) { Result.success(emptyList()) }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try { RetrofitClient.getApi().logout() } catch (_: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.reset()
    }

    private suspend fun autoRelogin() {
        try {
            val encoded = Base64.encodeToString(storage.getPassword().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            RetrofitClient.setRouterAddress(storage.getRouterIp())
            val response = RetrofitClient.getApi().login(password = encoded)
            val setCookies = response.headers().values("Set-Cookie")
            for (cookieHeader in setCookies) {
                val parts = cookieHeader.split(";")[0].split("=", limit = 2)
                if (parts.size == 2) {
                    RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
                }
            }
        } catch (_: Exception) {}
    }
}
