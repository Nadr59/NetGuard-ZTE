package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.BufferedReader
import java.io.FileReader
import java.net.InetAddress

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

    suspend fun getConnectedDevices(): Result<List<Device>> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val routerIp = try { storage.getRouterIp() } catch (_: Exception) { "192.168.0.1" }

                debug.appendLine("=== DEVICE SCAN ===")
                debug.appendLine("Router: $routerIp")

                // ═══ الخطوة 1: Ping الراوتر لملء ARP cache ═══
                debug.appendLine("\n--- Step 1: Ping gateway ---")
                try {
                    val gateway = InetAddress.getByName(routerIp)
                    gateway.isReachable(1000)
                    debug.appendLine("Pinged gateway: $routerIp")
                } catch (e: Exception) {
                    debug.appendLine("Ping gateway error: ${e.message}")
                }

                // ═══ الخطوة 2: Ping أجهزة شائعة ═══
                debug.appendLine("\n--- Step 2: Ping subnet ---")
                val subnet = routerIp.substringBeforeLast(".")
                val ipsToPing = mutableListOf<String>()
                for (i in 1..50) ipsToPing.add("$subnet.$i")
                for (ip in ipsToPing) {
                    try {
                        InetAddress.getByName(ip).isReachable(100)
                    } catch (_: Exception) {}
                }
                debug.appendLine("Pinged 50 IPs")

                // ═══ الخطوة 3: اقرأ ARP ═══
                debug.appendLine("\n--- Step 3: Read ARP ---")
                val arpDevices = readArp()
                debug.appendLine("ARP: ${arpDevices.size} devices")
                for (d in arpDevices) {
                    debug.appendLine("  ${d.ip} | ${d.mac} | ${d.hostname}")
                }

                if (arpDevices.isNotEmpty()) {
                    lastWorkingCommand = "ARP"
                    lastRawResponse = "${arpDevices.size} devices"
                    allCommandsDebug = debug.toString()
                    return@withContext Result.success(arpDevices)
                }

                // ═══ الخطوة 4: Router API ═══
                debug.appendLine("\n--- Step 4: Router API ---")
                try {
                    val api = RetrofitClient.getApi()
                    val cmds = listOf(
                        "station_list", "wifi_station_list", "dhcp_list",
                        "client_list", "connected_devices", "lan_station_list"
                    )
                    for (cmd in cmds) {
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

                allCommandsDebug = debug.toString()
                Result.failure(Exception("لم يتم العثور على أجهزة"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("فشل: ${e.message}"))
        }
    }

    suspend fun testRouterConnection(): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                debug.appendLine("=== TEST ===")

                try {
                    val r = RetrofitClient.getApi().getGenericCmd(cmd = "Language")
                    debug.appendLine("Language: ${r.body()?.string()}")
                } catch (e: Exception) {
                    debug.appendLine("Language error: ${e.message}")
                }

                try {
                    val r = RetrofitClient.getApi().getMacFilterList()
                    debug.appendLine("MAC filter: ${r.body()?.string()}")
                } catch (e: Exception) {
                    debug.appendLine("MAC filter error: ${e.message}")
                }

                try {
                    val routerIp = storage.getRouterIp()
                    val subnet = routerIp.substringBeforeLast(".")
                    debug.appendLine("\nPinging subnet...")
                    for (i in 1..20) {
                        try { InetAddress.getByName("$subnet.$i").isReachable(100) } catch (_: Exception) {}
                    }
                    debug.appendLine("Ping done")
                } catch (_: Exception) {}

                try {
                    val arp = readArp()
                    debug.appendLine("\nARP: ${arp.size} devices")
                    for (d in arp) debug.appendLine("  ${d.ip} | ${d.mac}")
                } catch (e: Exception) {
                    debug.appendLine("ARP error: ${e.message}")
                }

                Result.success(debug.toString())
            }
        } catch (e: Exception) {
            Result.failure(Exception("Test failed: ${e.message}"))
        }
    }

    private fun readArp(): List<Device> {
        val devices = mutableListOf<Device>()
        var reader: BufferedReader? = null
        try {
            reader = BufferedReader(FileReader("/proc/net/arp"))
            reader.readLine()
            var line = reader.readLine()
            while (line != null) {
                try {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3].uppercase()
                        val flags = parts[2]
                        if (mac != "00:00:00:00:00:00" && flags != "0x0") {
                            val routerIp = try { storage.getRouterIp() } catch (_: Exception) { "" }
                            devices.add(
                                Device(
                                    mac = mac,
                                    ip = ip,
                                    hostname = nameFor(ip, mac),
                                    connectionType = if (ip == routerIp) "Router" else "WiFi"
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
                line = reader.readLine()
            }
        } catch (_: Exception) {}
        finally {
            try { reader?.close() } catch (_: Exception) {}
        }
        return devices
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
            mac.startsWith("F0:27") || mac.startsWith("74:C2") -> "Amazon"
            mac.startsWith("B8:27") -> "Raspberry Pi"
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

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")
                val r = RetrofitClient.getApi().setMacFilter(macList = newList)
                if (r.isSuccessful) Result.success("تم حظر الجهاز")
                else Result.failure(Exception("فشل: ${r.code()}"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val newList = currentBlockedList.filter { it.uppercase() != mac.uppercase() }.joinToString(";")
                val r = if (newList.isEmpty()) RetrofitClient.getApi().disableMacFilter()
                else RetrofitClient.getApi().setMacFilter(macList = newList)
                if (r.isSuccessful) Result.success("تم إلغاء الحظر")
                else Result.failure(Exception("فشل: ${r.code()}"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getBlockedMacs(): Result<List<String>> {
        return try {
            withContext(Dispatchers.IO) {
                val r = RetrofitClient.getApi().getMacFilterList()
                if (r.isSuccessful) {
                    val body = r.body()?.string() ?: ""
                    Result.success("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}".toRegex().findAll(body).map { it.value.uppercase() }.toList())
                } else Result.success(emptyList())
            }
        } catch (_: Exception) { Result.success(emptyList()) }
    }

    suspend fun logout() {
        try {
            withContext(Dispatchers.IO) {
                try { RetrofitClient.getApi().logout() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.reset()
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
