package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RouterRepository(private val storage: SecureStorage) {

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
                    // بعض الراوترات لا تُرجع result صريح — نعتبر النجاح كافياً
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

    // ═══ جلب الأجهزة المتصلة ═══
    suspend fun getConnectedDevices(): Result<List<Device>> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val response = api.getStationList()

            if (response.isSuccessful) {
                val body = response.body()
                val stations = body?.stationList ?: emptyList()

                val devices = stations.map { station ->
                    Device(
                        mac = station.mac.uppercase(),
                        ip = station.ip,
                        hostname = station.hostname.ifBlank { "Unknown Device" },
                        connectionType = station.connType.ifBlank { "WiFi" }
                    )
                }
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

    // ═══ حظر جهاز (إضافة MAC للقائمة السوداء) ═══
    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val api = RetrofitClient.getApi()
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")

                val response = api.setMacFilter(macList = newList)

                if (response.isSuccessful) {
                    Result.success("تم حظر الجهاز $mac")
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

    // ═══ إلغاء حظر جهاز ═══
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
                    Result.success("تم إلغاء حظر $mac")
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
            val api = RetrofitClient.getApi()
            api.logout()
        } catch (_: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.setSessionCookie(null)
    }

    // ═══ إعادة تسجيل الدخول التلقائي ═══
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
                val devices = response.body()?.stationList?.map {
                    Device(it.mac.uppercase(), it.ip, it.hostname.ifBlank { "Unknown" }, it.connType)
                } ?: emptyList()
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
        } catch (e: Exception) {
            Result.failure(e)
        }
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
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseBlockedMacs(json: String): List<String> {
        return try {
            val macPattern = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
            macPattern.findAll(json).map { it.value.uppercase() }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
