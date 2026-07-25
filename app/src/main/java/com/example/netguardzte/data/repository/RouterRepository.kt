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

    suspend fun getConnectedDevices(): Result<List<Device>> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()

            val commands = listOf(
                "station_list",
                "dhcp_list",
                "client_list",
                "lan_station_list",
                "wifi_client_list"
            )

            for (cmdName in commands) {
                try {
                    val response = api.getGenericCmd(cmd = cmdName)

                    if (response.isSuccessful) {
                        val rawBody = response.body()?.string() ?: ""

                        if (rawBody.isNotBlank() && rawBody != "{}" && rawBody != "[]") {
                            lastRawResponse = rawBody
                            lastWorkingCommand = cmdName
                        }

                        val devices = tryAllParsingMethods(rawBody)
                        if (devices.isNotEmpty()) {
                            return@withContext Result.success(devices)
                        }
                    } else if (response.code() == 401) {
                        autoReloginRelay()
                        continue
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            if (lastRawResponse.isNotBlank()) {
                Result.failure(
                    Exception(
                        "لم يتم العثور على أجهزة.\n" +
                        "الأمر: $lastWorkingCommand\n" +
                        "الاستجابة: ${lastRawResponse.take(300)}"
                    )
                )
            } else {
                Result.failure(Exception("لا توجد استجابة من الراوتر"))
            }

        } catch (e: Exception) {
            Result.failure(Exception("فشل جلب الأجهزة: ${e.message}"))
        }
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
                val list = obj.get("station_list")
                    ?: obj.get("devices")
                    ?: obj.get("clients")
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

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val api = RetrofitClient.getApi()
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")
                val response = api.setMacFilter(macList = newList)

                if (response.isSuccessful) {
                    Result.success("تم حظر الجهاز")
                } else if (response.code() == 401) {
                    autoReloginRelay()
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

                val response = if (newList.isEmpty()) {
                    api.disableMacFilter()
                } else {
                    api.setMacFilter(macList = newList)
                }

                if (response.isSuccessful) {
                    Result.success("تم إلغاء الحظر")
                } else if (response.code() == 401) {
                    autoReloginRelay()
                    val retryApi = RetrofitClient.getApi()
                    val retry = if (newList.isEmpty()) retryApi.disableMacFilter()
                    else retryApi.setMacFilter(macList = newList)
                    if (retry.isSuccessful) Result.success("تم إلغاء الحظر")
                    else Result.failure(Exception("فشل: ${retry.code()}"))
                } else {
                    Result.failure(Exception("فشل إلغاء الحظر: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(Exception("فشل إلغاء الحظر: ${e.message}"))
            }
        }

    suspend fun getBlockedMacs(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().getMacFilterList()
            if (response.isSuccessful) {
                val body = response.body()?.string() ?: ""
                Result.success(parseBlockedMacs(body))
            } else {
                Result.success(emptyList())
            }
        } catch (_: Exception) {
            Result.success(emptyList())
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            RetrofitClient.getApi().logout()
        } catch (_: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.setSessionCookie(null)
    }

    private suspend fun autoReloginRelay() {
        autoRelogin()
    }

    private suspend fun autoRelogin() {
        try {
            val ip = storage.getRouterIp()
            val password = storage.getPassword()
            val encoded = Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            RetrofitClient.setRouterAddress(ip)
            RetrofitClient.getApi().login(password = encoded)
        } catch (_: Exception) {}
    }

    private fun parseBlockedMacs(json: String): List<String> {
        return try {
            Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
                .findAll(json)
                .map { it.value.uppercase() }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
