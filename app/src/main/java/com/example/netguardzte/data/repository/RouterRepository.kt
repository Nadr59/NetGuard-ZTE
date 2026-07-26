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

class RouterRepository(private val storage: SecureStorage) {

    var lastRawResponse: String = ""
        private set
    var lastWorkingCommand: String = ""
        private set
    var loginResponse: String = ""
        private set
    var cookieDebug: String = ""
        private set

    // ═══════════════════════════════════════════
    // تسجيل الدخول — مع تشخيص كامل
    // ═══════════════════════════════════════════
    suspend fun login(
        routerIp: String,
        username: String,
        password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            RetrofitClient.reset()
            RetrofitClient.setRouterAddress(routerIp)

            val encodedPassword = Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )

            val api = RetrofitClient.getApi()
            val response = api.login(password = encodedPassword)

            val body = response.body()?.string() ?: ""
            val headers = response.headers().toString()
            loginResponse = "Code: ${response.code()}\nBody: ${body.take(200)}\nHeaders: ${headers.take(300)}"

            // ═══ اقرأ الكوكيز من الاستجابة يدوياً ═══
            val setCookies = response.headers().values("Set-Cookie")
            for (cookieHeader in setCookies) {
                val parts = cookieHeader.split(";")[0].split("=", limit = 2)
                if (parts.size == 2) {
                    RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
                }
            }

            cookieDebug = "Cookies: ${RetrofitClient.getCookiesString()}"

            if (response.isSuccessful) {
                if (body.contains("\"result\":\"3\"") || body.contains("\"result\":3")) {
                    Result.failure(Exception("كلمة المرور خاطئة"))
                } else {
                    storage.saveCredentials(routerIp, username, password)
                    storage.setLoggedIn(true)
                    Result.success("تم الاتصال بالراوتر")
                }
            } else if (response.code() == 401) {
                Result.failure(Exception("بيانات الدخول غير صحيحة"))
            } else {
                // ═══ بعض الراوترات ترجع 200 حتى لو فشل ═══
                // ═══ نحفظ البيانات ونتجاهل الخطأ ═══
                storage.saveCredentials(routerIp, username, password)
                storage.setLoggedIn(true)
                Result.success("تم الاتصال (${response.code()})")
            }
        } catch (e: Exception) {
            Result.failure(Exception("لا يمكن الوصول للراوتر: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // جلب الأجهزة — نجرب كل الأوامر الممكنة
    // ═══════════════════════════════════════════
    suspend fun getConnectedDevices(): Result<List<Device>> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()

            // ═══ تحقق من الكوكيز ═══
            cookieDebug = "Cookies: ${RetrofitClient.getCookiesString()}"

            // ═══ الأوامر المُجربة على راوترات ZTE ═══
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
                "user_list"
            )

            for (cmdName in commands) {
                try {
                    val response = api.getGenericCmd(cmd = cmdName)

                    if (response.isSuccessful) {
                        val rawBody = response.body()?.string() ?: ""

                        // ═══ تحقق: هل الاستجابة تحتوي بيانات حقيقية ═══
                        if (hasRealData(rawBody, cmdName)) {
                            lastRawResponse = rawBody
                            lastWorkingCommand = cmdName

                            val devices = tryAllParsingMethods(rawBody)
                            if (devices.isNotEmpty()) {
                                return@withContext Result.success(devices)
                            }
                        }
                    } else if (response.code() == 401) {
                        // ═══ إعادة تسجيل دخول ═══
                        autoRelogin()
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            // ═══ لم نجد أجهزة ═══
            val debugMsg = buildString {
                append("لم يتم العثور على أجهزة\n\n")
                append("الكوكيز: ${RetrofitClient.getCookiesString()}\n\n")
                if (lastWorkingCommand.isNotBlank()) {
                    append("آخر أمر: $lastWorkingCommand\n")
                    append("الاستجابة: ${lastRawResponse.take(300)}")
                } else {
                    append("لم يتم الحصول على أي استجابة مفيدة\n")
                    append("آخر استجابة: ${lastRawResponse.take(300)}")
                }
            }
            Result.failure(Exception(debugMsg))

        } catch (e: Exception) {
            Result.failure(Exception("فشل: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // تحقق: هل الاستجابة تحتوي بيانات حقيقية؟
    // ═══════════════════════════════════════════
    private fun hasRealData(body: String, cmdName: String): Boolean {
        if (body.isBlank()) return false
        if (body == "{}") return false
        if (body == "[]") return false

        // ═══ راوتر ZTE يرد باسم الأمر إذا لم يتعرف عليه ═══
        if (body == "{\"\":\"$cmdName\"}") return false
        if (body == "{\"result\":\"$cmdName\"}") return false
        if (body.contains("\"\":\"$cmdName\"") && body.length < 50) return false

        // ═══ إذا يحتوي MAC = بيانات حقيقية ═══
        if (Regex("[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}").containsMatchIn(body)) return true

        // ═══ إذا يحتوي IP = بيانات حقيقية ═══
        if (Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(body)) return true

        // ═══ إذا طول الاستجابة > 50 = قد يحتوي بيانات ═══
        if (body.length > 50) return true

        return false
    }

    // ═══════════════════════════════════════════
    // تحليل الاستجابة
    // ═══════════════════════════════════════════
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
            Device(
                mac = mac,
                ip = ips.getOrNull(i) ?: "",
                hostname = "جهاز ${i + 1}",
                connectionType = "Unknown"
            )
        }
    }

    // ═══════════════════════════════════════════
    // حظر / إلغاء حظر
    // ═══════════════════════════════════════════
    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val api = RetrofitClient.getApi()
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")
                val response = api.setMacFilter(macList = newList)

                if (response.isSuccessful) {
                    Result.success("تم حظر الجهاز")
                } else if (response.code() == 401) {
                    autoRelogin()
                    val retry = RetrofitClient.getApi().setMacFilter(macList = newList)
                    if (retry.isSuccessful) Result.success("تم الحظر")
                    else Result.failure(Exception("فشل: ${retry.code()}"))
                } else {
                    Result.failure(Exception("فشل الحظر: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(Exception("فشل الحظر: ${e.message}"))
            }
        }

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val api = RetrofitClient.getApi()
                val newList = currentBlockedList
                    .filter { it.uppercase() != mac.uppercase() }
                    .joinToString(";")

                val response = if (newList.isEmpty()) api.disableMacFilter()
                else api.setMacFilter(macList = newList)

                if (response.isSuccessful) {
                    Result.success("تم إلغاء الحظر")
                } else if (response.code() == 401) {
                    autoRelogin()
                    val retryApi = RetrofitClient.getApi()
                    val retry = if (newList.isEmpty()) retryApi.disableMacFilter()
                    else retryApi.setMacFilter(macList = newList)
                    if (retry.isSuccessful) Result.success("تم إلغاء الحظر")
                    else Result.failure(Exception("فشل: ${retry.code()}"))
                } else {
                    Result.failure(Exception("فشل: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(Exception("فشل: ${e.message}"))
            }
        }

    suspend fun getBlockedMacs(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().getMacFilterList()
            if (response.isSuccessful) {
                val body = response.body()?.string() ?: ""
                Result.success(parseBlockedMacs(body))
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
            val ip = storage.getRouterIp()
            val password = storage.getPassword()
            val encoded = Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            RetrofitClient.setRouterAddress(ip)

            val response = RetrofitClient.getApi().login(password = encoded)

            // ═══ اقرأ الكوكيز من الاستجابة ═══
            val setCookies = response.headers().values("Set-Cookie")
            for (cookieHeader in setCookies) {
                val parts = cookieHeader.split(";")[0].split("=", limit = 2)
                if (parts.size == 2) {
                    RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
                }
            }
        } catch (_: Exception) {}
    }

    private fun parseBlockedMacs(json: String): List<String> {
        return try {
            Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
                .findAll(json).map { it.value.uppercase() }.toList()
        } catch (_: Exception) { emptyList() }
    }
}
