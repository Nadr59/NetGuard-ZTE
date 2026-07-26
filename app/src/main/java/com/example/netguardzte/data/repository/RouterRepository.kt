package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import java.io.BufferedReader
import java.io.FileReader

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

            val encodedPassword = Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )

            for (attempt in 1..3) {
                debug.appendLine("=== Login attempt $attempt ===")
                try {
                    if (attempt == 1) {
                        try { api.getMainPage() } catch (_: Exception) {}
                    }

                    val response = api.login(password = encodedPassword)
                    val body = response.body()?.string() ?: ""

                    debug.appendLine("Code: ${response.code()}")
                    debug.appendLine("Body: ${body.take(200)}")
                    readCookies(response, debug)

                    if (body.contains("\"result\":\"3\"") || body.contains("\"result\":3")) {
                        if (attempt == 3) {
                            loginDebug = debug.toString()
                            return@withContext Result.failure(Exception("كلمة المرور خاطئة"))
                        }
                        continue
                    }

                    if (body.contains("\"result\":\"0\"") || body.contains("\"result\":0") ||
                        body.contains("\"result\":\"1\"") || body.contains("\"result\":1")
                    ) {
                        debug.appendLine("SUCCESS on attempt $attempt!")
                        storage.saveCredentials(routerIp, username, password)
                        storage.setLoggedIn(true)
                        loginDebug = debug.toString()
                        return@withContext Result.success("تم الاتصال بالراوتر")
                    }

                    if (response.isSuccessful) {
                        storage.saveCredentials(routerIp, username, password)
                        storage.setLoggedIn(true)
                        loginDebug = debug.toString()
                        return@withContext Result.success("تم الاتصال")
                    }

                } catch (e: Exception) {
                    debug.appendLine("Error: ${e.message}")
                }
            }

            loginDebug = debug.toString()
            Result.failure(Exception("فشل تسجيل الدخول"))
        } catch (e: Exception) {
            loginDebug = "Exception: ${e.message}"
            Result.failure(Exception("لا يمكن الوصول للراوتر: ${e.message}"))
        }
    }

    suspend fun getConnectedDevices(): Result<List<Device>> = withContext(Dispatchers.IO) {
        val debug = StringBuilder()
        debug.appendLine("=== DEVICE SCAN ===")

        try {
            // ═══ الطريقة 1: ARP table (الأسرع) ═══
            debug.appendLine("\n--- ARP Table ---")
            val arpDevices = readArpTableSafe()
            debug.appendLine("Found: ${arpDevices.size}")

            if (arpDevices.isNotEmpty()) {
                for (d in arpDevices) debug.appendLine("  ${d.ip} | ${d.mac}")
                lastWorkingCommand = "ARP table"
                allCommandsDebug = debug.toString()
                return@withContext Result.success(arpDevices)
            }

            // ═══ الطريقة 2: Router API ═══
            debug.appendLine("\n--- Router API ---")
            try {
                val api = RetrofitClient.getApi()
                for (cmd in listOf("station_list", "wifi_station_list", "dhcp_list")) {
                    try {
                        val r = api.getGenericCmd(cmd = cmd)
                        val b = r.body()?.string() ?: ""
                        debug.appendLine("[$cmd] -> ${b.take(100)}")
                        if (b.length > 30 && !b.contains("\"\":\"$cmd\"")) {
                            val devices = parseDevices(b)
                            if (devices.isNotEmpty()) {
                                lastWorkingCommand = cmd
                                allCommandsDebug = debug.toString()
                                return@withContext Result.success(devices)
                            }
                        }
                    } catch (e: Exception) {
                        debug.appendLine("[$cmd] error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                debug.appendLine("API error: ${e.message}")
            }

            // ═══ الطريقة 3: Ping سريع ═══
            debug.appendLine("\n--- Quick Ping ---")
            try {
                val pingDevices = quickScanSafe()
                debug.appendLine("Found: ${pingDevices.size}")

                if (pingDevices.isNotEmpty()) {
                    lastWorkingCommand = "Ping scan"
                    allCommandsDebug = debug.toString()
                    return@withContext Result.success(pingDevices)
                }
            } catch (e: Exception) {
                debug.appendLine("Ping error: ${e.message}")
            }

            allCommandsDebug = debug.toString()
            Result.failure(Exception("لم يتم العثور على أجهزة"))
        } catch (e: Exception) {
            allCommandsDebug = debug.toString()
            Result.failure(Exception("فشل البحث: ${e.message}"))
        }
    }

    suspend fun testRouterConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val debug = StringBuilder()

            debug.appendLine("=== TEST ROUTER ===")

            debug.appendLine("\n--- Session ---")
            try {
                val r = api.getGenericCmd(cmd = "Language")
                val b = r.body()?.string() ?: ""
                debug.appendLine("Language: $b")
            } catch (e: Exception) {
                debug.appendLine("Error: ${e.message}")
            }

            debug.appendLine("\n--- MAC filter ---")
            try {
                val r = api.getMacFilterList()
                val b = r.body()?.string() ?: ""
                debug.appendLine("MAC filter: $b")
            } catch (e: Exception) {
                debug.appendLine("Error: ${e.message}")
            }

            debug.appendLine("\n--- ARP ---")
            try {
                val arp = readArpTableSafe()
                debug.appendLine("Devices: ${arp.size}")
                for (d in arp) debug.appendLine("  ${d.ip} | ${d.mac}")
            } catch (e: Exception) {
                debug.appendLine("Error: ${e.message}")
            }

            Result.success(debug.toString())
        } catch (e: Exception) {
            Result.failure(Exception("Test failed: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // ARP table — آمن بالكامل
    // ═══════════════════════════════════════════
    private fun readArpTableSafe(): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val reader = BufferedReader(FileReader("/proc/net/arp"))
            try {
                reader.readLine() // skip header
                var line = reader.readLine()
                while (line != null) {
                    try {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 4) {
                            val ip = parts[0]
                            val mac = parts[3].uppercase()
                            val flags = parts.getOrElse(2) { "0x0" }

                            if (mac != "00:00:00:00:00:00" && flags != "0x0") {
                                val routerIp = storage.getRouterIp()
                                devices.add(
                                    Device(
                                        mac = mac,
                                        ip = ip,
                                        hostname = guessName(ip, mac),
                                        connectionType = if (ip == routerIp) "Router" else "WiFi"
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {}
                    line = reader.readLine()
                }
            } finally {
                reader.close()
            }
        } catch (_: Exception) {}
        return devices
    }

    // ═══ Ping سريع وآمن — بدون DNS ═══
    private fun quickScanSafe(): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val routerIp = storage.getRouterIp()
            val subnet = routerIp.substringBeforeLast(".")

            for (i in 1..10) {
                try {
                    val ip = "$subnet.$i"
                    val addr = java.net.InetAddress.getByName(ip)
                    val reachable = try {
                        addr.isReachable(50)
                    } catch (_: Exception) { false }

                    if (reachable) {
                        val mac = getMacFromArp(ip)
                        devices.add(
                            Device(
                                mac = mac.ifBlank { "??:??:??:??:??:??" },
                                ip = ip,
                                hostname = guessName(ip, mac),
                                connectionType = if (ip == routerIp) "Router" else "WiFi"
                            )
                        )
                    }
                } catch (_: Exception) {}
            }

            // أضف من ARP table
            for (arp in readArpTableSafe()) {
                if (devices.none { it.ip == arp.ip }) {
                    devices.add(arp)
                }
            }
        } catch (_: Exception) {}
        return devices
    }

    private fun getMacFromArp(ip: String): String {
        try {
            val reader = BufferedReader(FileReader("/proc/net/arp"))
            try {
                reader.readLine()
                var line = reader.readLine()
                while (line != null) {
                    try {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 4 && parts[0] == ip) {
                            val mac = parts[3].uppercase()
                            if (mac != "00:00:00:00:00:00") return mac
                        }
                    } catch (_: Exception) {}
                    line = reader.readLine()
                }
            } finally {
                reader.close()
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun guessName(ip: String, mac: String): String {
        val vendor = when {
            mac.startsWith("A4:83") || mac.startsWith("F0:18") || mac.startsWith("3C:2E") -> "Apple"
            mac.startsWith("CC:96") || mac.startsWith("58:48") || mac.startsWith("AC:CF") -> "Huawei"
            mac.startsWith("70:F9") || mac.startsWith("94:B8") || mac.startsWith("C0:BD") -> "Samsung"
            mac.startsWith("6C:B0") || mac.startsWith("54:FA") || mac.startsWith("AC:F7") -> "Xiaomi"
            mac.startsWith("58:7F") || mac.startsWith("74:51") || mac.startsWith("50:64") -> "Xiaomi"
            mac.startsWith("00:16") || mac.startsWith("50:C7") || mac.startsWith("EC:08") -> "TP-Link"
            mac.startsWith("88:66") || mac.startsWith("F4:F5") -> "Google"
            mac.startsWith("F0:27") || mac.startsWith("74:C2") -> "Amazon"
            mac.startsWith("B8:27") || mac.startsWith("DC:A6") -> "Raspberry Pi"
            mac.startsWith("00:21") -> "ZTE"
            else -> ""
        }
        val suffix = ip.substringAfterLast(".")
        return when {
            vendor.isNotBlank() -> "$vendor ($suffix)"
            suffix == "1" -> "الراوتر"
            else -> "جهاز .$suffix"
        }
    }

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")
                val r = RetrofitClient.getApi().setMacFilter(macList = newList)
                if (r.isSuccessful) Result.success("تم حظر الجهاز")
                else Result.failure(Exception("فشل: ${r.code()}"))
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val newList = currentBlockedList.filter { it.uppercase() != mac.uppercase() }.joinToString(";")
                val r = if (newList.isEmpty()) RetrofitClient.getApi().disableMacFilter()
                else RetrofitClient.getApi().setMacFilter(macList = newList)
                if (r.isSuccessful) Result.success("تم إلغاء الحظر")
                else Result.failure(Exception("فشل: ${r.code()}"))
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun getBlockedMacs(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val r = RetrofitClient.getApi().getMacFilterList()
            if (r.isSuccessful) {
                val body = r.body()?.string() ?: ""
                Result.success(Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}").findAll(body).map { it.value.uppercase() }.toList())
            } else Result.success(emptyList())
        } catch (_: Exception) { Result.success(emptyList()) }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try { RetrofitClient.getApi().logout() } catch (_: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.reset()
    }

    private fun readCookies(response: Response<*>, debug: StringBuilder) {
        for (c in response.headers().values("Set-Cookie")) {
            val parts = c.split(";")[0].split("=", limit = 2)
            if (parts.size == 2) RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
        }
        cookieDebug = "Cookies: ${RetrofitClient.getCookiesString()}"
        debug.appendLine(cookieDebug)
    }

    private fun parseDevices(raw: String): List<Device> {
        try {
            val macPattern = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
            val ipPattern = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
            val macs = macPattern.findAll(raw).map { it.value.uppercase() }.distinct().toList()
            if (macs.isEmpty()) return emptyList()
            val ips = ipPattern.findAll(raw).map { it.value }.toList()
            return macs.mapIndexed { i, mac ->
                Device(mac = mac, ip = ips.getOrNull(i) ?: "", hostname = "جهاز ${i + 1}", connectionType = "WiFi")
            }
        } catch (_: Exception) { return emptyList() }
    }
}
