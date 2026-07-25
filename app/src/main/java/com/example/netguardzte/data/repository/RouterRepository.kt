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

    // ═══ للتشخيص: آخر استجابة خام ═══
    var lastRawResponse: String = ""
        private set
    var lastWorkingCommand: String = ""
        private set

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
                if (body.contains("\"result\":\"3\"") || body.contains("\"result\":3")) {
                    Result.failure(Exception("كلمة المرور خاطئة"))
                } else {
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

    // ═══════════════════════════════════════════
    // جلب الأجهزة — نجرب عدة أوامر
    // ═══════════════════════════════════════════
    suspend fun getConnectedDevices(): Result<List<Device>> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()

            // ═══ قائمة الأوامر الممكنة ═══
            val commands = listOf(
                "station_list" to { api.getStationList() },
                "dhcp_list" to { api.getDhcpList() },
                "client_list" to { api.getClientList() },
                "lan_station_list" to { api.getLanStationList() },
                "wifi_client_list" to { api.getWifiClientList() },
            )

            for ((cmdName, apiCall) in commands) {
                try {
                    val response = apiCall()
                    if (response.isSuccessful) {
                        val rawBody = response.body()?.string() ?: ""

                        // حفظ للتشخيص
                        if (rawBody.isNotBlank() && rawBody != "{}" && rawBody != "[]") {
                            lastRawResponse = rawBody
                            lastWorkingCommand = cmdName
                        }

                        val devices = tryAllParsingMethods(rawBody, cmdName)
                        if (devices.isNotEmpty()) {
                            return@withContext Result.success(devices)
                        }
                    } else if (response.code() == 401) {
                        autoRelogin()
                        continue
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            // ═══ إذا لم نجد أجهزة من أي أمر ═══
            if (lastRawResponse.isNotBlank()) {
                Result.failure(
                    Exception("لم يتم العثور على أجهزة.\nالأمر: $lastWorkingCommand\nالاستجابة: ${lastRawResponse.take(200)}")
                )
            } else {
                Result.failure(Exception("لا توجد استجابة من الراوتر"))
            }

        } catch (e: Exception) {
            Result.failure(Exception("فشل جلب الأجهزة: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // تحليل الاستجابة — عدة طرق
    // ═══════════════════════════════════════════
    private fun tryAllParsingMethods(rawBody: String, cmdName: String): List<Device> {
        if (rawBody.isBlank()) return emptyList()

        try {
            val root = JsonParser.parseString(rawBody)

            // ═══ الطريقة 1: البحث عن مصفوفة بأي اسم ═══
            if (root.isJsonObject) {
                val obj = root.asJsonObject

                // ابحث عن أي حقل يحتوي مصفوفة
                for (key in obj.keySet()) {
                    val element = obj.get(key) ?: continue
                    val devices = tryParseDeviceArray(element)
                    if (devices.isNotEmpty()) return devices
                }

                // ابحث عن حقل station_list كنص
                val stationList = obj.get("station_list")
                if (stationList != null) {
                    val devices = tryParseDeviceElement(stationList)
                    if (devices.isNotEmpty()) return devices
                }
            }

            // ═══ الطريقة 2: المصفوفة مباشرة ═══
            if (root.isJsonArray) {
                val devices = tryParseDeviceArray(root)
                if (devices.isNotEmpty()) return devices
            }

        } catch (_: Exception) {}

        // ═══ الطريقة 3: Regex للبحث عن MAC في النص ═══
        return tryParseWithRegex(rawBody)
    }

    private fun tryParseDeviceElement(element: JsonElement): List<Device> {
        return when {
            element.isJsonArray -> tryParseDeviceArray(element)
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                val str = element.asString
                if (str.isBlank()) return emptyList()
                try {
                    val parsed = JsonParser.parseString(str)
                    if (parsed.isJsonArray) tryParseDeviceArray(parsed)
                    else emptyList()
                } catch (_: Exception) { emptyList() }
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val list = obj.get("station_list") ?: obj.get("devices") ?: obj.get("clients")
                if (list != null) tryParseDeviceElement(list) else emptyList()
            }
            else -> emptyList()
        }
    }

    private fun tryParseDeviceArray(element: JsonElement): List<Device> {
        if (!element.isJsonArray) return emptyList()

        val devices = mutableListOf<Device>()
        for (item in element.asJsonArray) {
            if (!item.isJsonObject) continue
            val obj = item.asJsonObject
            val mac = findMacInObject(obj)
            if (mac.isNotBlank()) {
                devices.add(
                    Device(
                        mac = mac.uppercase(),
                        ip = findField(obj, "ip", "ip_addr", "ipAddress", "address"),
                        hostname = findField(obj, "hostname", "name", "host_name", "device_name", "client_name")
                            .ifBlank { "جهاز غير معروف" },
                        connectionType = findField(obj, "conn_type", "wlan_type", "type", "connection")
                            .ifBlank { "WiFi" }
                    )
                )
            }
        }
        return devices
    }

    // ═══ البحث عن MAC بأي اسم حقل ═══
    private fun findMacInObject(obj: JsonObject): String {
        val macFields = listOf("mac", "mac_addr", "mac_address", "MacAddress", "MAC", "hwaddr", "hw_addr")
        for (field in macFields) {
            val value = getFieldAsString(obj, field)
            if (value.isNotBlank() && isValidMac(value)) return value
        }

        // ابحث في كل الحقول
        for (key in obj.keySet()) {
            val value = getFieldAsString(obj, key)
            if (isValidMac(value)) return value
        }

        return ""
    }

    private fun findField(obj: JsonObject, vararg names: String): String {
        for (name in names) {
            val value = getFieldAsString(obj, name)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun getFieldAsString(obj: JsonObject, field: String): String {
        return try {
            val element = obj.get(field) ?: return ""
            when {
                element.isJsonNull -> ""
                element.isJsonPrimitive -> element.asString
                else -> element.toString()
            }
        } catch (_: Exception) { "" }
    }

    private fun isValidMac(value: String): Boolean {
        return Regex("[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}").matches(value.trim())
    }

    // ═══ استخراج MAC بالـ Regex من النص الخام ═══
    private fun tryParseWithRegex(raw: String): List<Device> {
        val macPattern = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
        val ipPattern = Regex("(\\d{1,3}\\.){3}\\d{1,3}")

        val macs = macPattern.findAll(raw).map { it.value.uppercase() }.distinct().toList()
        if (macs.isEmpty()) return emptyList()

        val ips = ipPattern.findAll(raw).map { it.value }.toList()

        return macs.mapIndexed { index, mac ->
            Device(
                mac = mac,
                ip = ips.getOrNull(index) ?: "",
                hostname = "جهاز ${index + 1}",
                connectionType = "Unknown"
            )
        }
    }

    // ═══ حظر / إلغاء حظر ═══
    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val api = RetrofitClient.getApi()
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")
                val response = api.setMacFilter(macList = newList)
                if (response.isSuccessful) Result.success("تم حظر الجهاز")
                else if (response.code() == 401) { autoRelogin(); retryBlock(mac, currentBlockedList) }
                else Result.failure(Exception("فشل: ${response.code()}"))
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val api = RetrofitClient.getApi()
                val newList = currentBlockedList.filter { it.uppercase() != mac.uppercase() }.joinToString(";")
                val response = if (newList.isEmpty()) api.disableMacFilter() else api.setMacFilter(macList = newList)
                if (response.isSuccessful) Result.success("تم إلغاء الحظر")
                else if (response.code() == 401) { autoRelogin(); retryUnblock(mac, currentBlockedList) }
                else Result.failure(Exception("فشل: ${response.code()}"))
            } catch (e: Exception) { Result.failure(e) }
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
        RetrofitClient.setSessionCookie(null)
    }

    private suspend fun autoRelogin() {
        try {
            val encoded = Base64.encodeToString(storage.getPassword().toByteArray(), Base64.NO_WRAP)
            RetrofitClient.setRouterAddress(storage.getRouterIp())
            RetrofitClient.getApi().login(password = encoded)
        } catch (_: Exception) {}
    }

    private suspend fun retryBlock(mac: String, list: List<String>): Result<String> {
        return try {
            val r = RetrofitClient.getApi().setMacFilter(macList = (list + mac.uppercase()).joinToString(";"))
            if (r.isSuccessful) Result.success("تم الحظر") else Result.failure(Exception("فشل"))
        } catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun retryUnblock(mac: String, list: List<String>): Result<String> {
        return try {
            val nl = list.filter { it.uppercase() != mac.uppercase() }.joinToString(";")
            val r = if (nl.isEmpty()) RetrofitClient.getApi().disableMacFilter() else RetrofitClient.getApi().setMacFilter(macList = nl)
            if (r.isSuccessful) Result.success("تم إلغاء الحظر") else Result.failure(Exception("فشل"))
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun parseBlockedMacs(json: String): List<String> {
        return try {
            Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}").findAll(json).map { it.value.uppercase() }.toList()
        } catch (_: Exception) { emptyList() }
    }
}
