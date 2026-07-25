package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.api.models.StationInfo
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RouterRepository(private val storage: SecureStorage) {

    private val gson = Gson()

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
                if (body.contains("\"result\":\"0\"") || body.contains("\"result\":0")) {
                    storage.saveCredentials(routerIp, username, password)
                    storage.setLoggedIn(true)
                    Result.success("تم تسجيل الدخول بنجاح")
                } else if (body.contains("\"result\":\"3\"") || body.contains("\"result\":3")) {
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

    // ═══ جلب الأجهزة المتصلة — تحليل يدوي ═══
    suspend fun getConnectedDevices(): Result<List<Device>> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val response = api.getStationList()

            if (response.isSuccessful) {
                val body = response.body()
                val devices = parseStationList(body?.station_list)
                Result.success(devices)
            } else if (response.code() == 401) {
                autoRelogin()
                retryGetDevices()
            } else {
                Result.failure(Exception("خطأ: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("فشل جلب الأجهزة: ${e.message}"))
        }
    }

    // ═══ تحليل station_list — يتعامل مع كل الحالات ═══
    private fun parseStationList(element: com.google.gson.JsonElement?): List<Device> {
        if (element == null || element.isJsonNull) return emptyList()

        return try {
            when {
                // ═══ الحالة 1: مصفوفة مباشرة [{...}, {...}] ═══
                element.isJsonArray -> {
                    parseJsonArray(element.asJsonArray)
                }

                // ═══ الحالة 2: نص JSON "[{...},{...}]" ═══
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                    val str = element.asString
                    if (str.isBlank()) return emptyList()

                    try {
                        val parsed = JsonParser.parseString(str)
                        if (parsed.isJsonArray) {
                            parseJsonArray(parsed.asJsonArray)
                        } else {
                            emptyList()
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                // ═══ الحالة 3: كائن واحد {...} ═══
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val list = obj.get("station_list")
                    if (list != null && list.isJsonArray) {
                        parseJsonArray(list.asJsonArray)
                    } else if (list != null && list.isJsonPrimitive) {
                        parseStationList(list)
                    } else {
                        // محاولة تحليل الكائن كجهاز واحد
                        val device = parseSingleDevice(obj)
                        if (device != null) listOf(device) else emptyList()
                    }
                }

                else -> emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun parseJsonArray(array: JsonArray): List<Device> {
        val devices = mutableListOf<Device>()
        for (element in array) {
            try {
                if (element.isJsonObject) {
                    val device = parseSingleDevice(element.asJsonObject)
                    if (device != null) devices.add(device)
                }
            } catch (_: Exception) {}
        }
        return devices
    }

    private fun parseSingleDevice(obj: JsonObject): Device? {
        val mac = getStringField(obj, "mac")
        if (mac.isBlank()) return null

        return Device(
            mac = mac.uppercase(),
            ip = getStringField(obj, "ip"),
            hostname = getStringField(obj, "hostname")
                .ifBlank { getStringField(obj, "name") }
                .ifBlank { "جهاز غير معروف" },
            connectionType = getStringField(obj, "conn_type")
                .ifBlank { getStringField(obj, "wlan_type") }
                .ifBlank { "WiFi" }
        )
    }

    // ═══ قراءة حقل نصي بأمان ═══
    private fun getStringField(obj: JsonObject, field: String): String {
        return try {
            val element = obj.get(field)
            when {
                element == null || element.isJsonNull -> ""
                element.isJsonPrimitive -> element.asString
                else -> element.toString()
            }
        } catch (_: Exception) {
            ""
        }
    }

    // ═══ حظر جهاز ═══
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
                    retryBlock(mac, currentBlockedList)
                } else {
                    Result.failure(Exception("فشل الحظر: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(Exception("فشل الحظر: ${e.message}"))
            }
        }

    // ═══ إلغاء حظر ═══
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
                    autoRelogin()
                    retryUnblock(mac, currentBlockedList)
                } else {
                    Result.failure(Exception("فشل إلغاء الحظر: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(Exception("فشل إلغاء الحظر: ${e.message}"))
            }
        }

    // ═══ جلب قائمة الحظر ═══
    suspend fun getBlockedMacs(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val response = api.getMacFilterList()

            if (response.isSuccessful) {
                val body = response.body()?.string() ?: ""
                val macs = parseBlockedMacs(body)
                Result.success(macs)
            } else {
                Result.success(emptyList())
            }
        } catch (_: Exception) {
            Result.success(emptyList())
        }
    }

    // ═══ تسجيل الخروج ═══
    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            RetrofitClient.getApi().logout()
        } catch (_: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.setSessionCookie(null)
    }

    // ═══ إعادة تسجيل الدخول التلقائية ═══
    private suspend fun autoRelogin() {
        try {
            val ip = storage.getRouterIp()
            val password = storage.getPassword()
            val encodedPassword = Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            RetrofitClient.setRouterAddress(ip)
            RetrofitClient.getApi().login(password = encodedPassword)
        } catch (_: Exception) {}
    }

    private suspend fun retryGetDevices(): Result<List<Device>> {
        return try {
            val response = RetrofitClient.getApi().getStationList()
            if (response.isSuccessful) {
                val devices = parseStationList(response.body()?.station_list)
                Result.success(devices)
            } else {
                Result.failure(Exception("انتهت الجلسة"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun retryBlock(mac: String, list: List<String>): Result<String> {
        return try {
            val newList = (list + mac.uppercase()).joinToString(";")
            val response = RetrofitClient.getApi().setMacFilter(macList = newList)
            if (response.isSuccessful) Result.success("تم الحظر") else Result.failure(Exception("فشل"))
        } catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun retryUnblock(mac: String, list: List<String>): Result<String> {
        return try {
            val newList = list.filter { it.uppercase() != mac.uppercase() }.joinToString(";")
            val response = if (newList.isEmpty()) {
                RetrofitClient.getApi().disableMacFilter()
            } else {
                RetrofitClient.getApi().setMacFilter(macList = newList)
            }
            if (response.isSuccessful) Result.success("تم إلغاء الحظر") else Result.failure(Exception("فشل"))
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun parseBlockedMacs(json: String): List<String> {
        return try {
            val macPattern = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
            macPattern.findAll(json).map { it.value.uppercase() }.toList()
        } catch (_: Exception) { emptyList() }
    }
}
