package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader

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
                val subnet = routerIp.substringBeforeLast(".")

                debug.appendLine("=== DEVICE SCAN ===")

                // ═══ الخطوة 1: مسح ARP cache ═══
                debug.appendLine("\n--- Flush ARP ---")
                flushArpCache(debug)

                // ═══ الخطوة 2: TCP connect لإجبار ARP جديد ═══
                debug.appendLine("\n--- Force ARP via TCP ---")
                forceArpEntries(subnet, debug)

                // ═══ الخطوة 3: اقرأ ARP ═══
                debug.appendLine("\n--- Read ARP ---")
                var devices = readArpFromAllSources(debug)
                debug.appendLine("Found: ${devices.size}")

                // ═══ الخطوة 4: Router API ═══
                if (devices.isEmpty()) {
                    debug.appendLine("\n--- Router API ---")
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

    // ═══ مسح ARP cache ═══
    private fun flushArpCache(debug: StringBuilder) {
        try {
            val routerIp = storage.getRouterIp()
            val subnet = routerIp.substringBeforeLast(".")

            // محاولة مسح ARP عبر shell
            val commands = listOf(
                "ip neigh flush dev wlan0",
                "ip -s neigh flush dev wlan0",
                "ndc ip neigh flush dev wlan0"
            )
            for (cmd in commands) {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    p.waitFor()
                    debug.appendLine("  $cmd -> done")
                } catch (e: Exception) {
                    debug.appendLine("  $cmd -> ${e.message}")
                }
            }
        } catch (e: Exception) {
            debug.appendLine("Flush error: ${e.message}")
        }
    }

    // ═══ TCP connect لإجبار ARP ═══
    private fun forceArpEntries(subnet: String, debug: StringBuilder) {
        try {
            for (i in 1..50) {
                val ip = "$subnet.$i"
                for (port in listOf(80, 443)) {
                    try {
                        val socket = java.net.Socket()
                        socket.connect(java.net.InetSocketAddress(ip, port), 30)
                        socket.close()
                    } catch (_: Exception) {}
                }
            }
            debug.appendLine("  TCP connect done")
        } catch (e: Exception) {
            debug.appendLine("  TCP error: ${e.message}")
        }
    }

    // ═══ قراءة ARP من كل المصادر ═══
    private fun readArpFromAllSources(debug: StringBuilder): List<Device> {
        // الطريقة 1: ip neigh (يُظهر الحالة)
        var devices = readIpNeigh(debug)
        if (devices.isNotEmpty()) return devices

        // الطريقة 2: /proc/net/arp
        devices = readArpFromFile()
        if (devices.isNotEmpty()) return devices

        // الطريقة 3: arp -a
        devices = readArpFromCommand("arp -a")
        if (devices.isNotEmpty()) return devices

        // الطريقة 4: cat /proc/net/arp
        devices = readArpFromCommand("cat /proc/net/arp")
        return devices
    }

    // ═══ ip neigh — يُظهر حالة الجهاز ═══
    private fun readIpNeigh(debug: StringBuilder): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var line = reader.readLine()
            while (line != null) {
                try {
                    val device = parseIpNeighLine(line)
                    if (device != null) devices.add(device)
                } catch (_: Exception) {}
                line = reader.readLine()
            }
            p.waitFor()
            debug.appendLine("  ip neigh: ${devices.size} devices")
        } catch (e: Exception) {
            debug.appendLine("  ip neigh error: ${e.message}")
        }
        return devices
    }

    // ═══ تحليل سطر ip neigh ═══
    // التنسيق: 192.168.0.1 dev wlan0 lladdr AA:BB:CC:DD:EE:FF REACHABLE
    private fun parseIpNeighLine(line: String): Device? {
        val macRegex = Regex("[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}")
        val ipRegex = Regex("(\\d{1,3}\\.){3}\\d{1,3}")

        val macMatch = macRegex.find(line) ?: return null
        val mac = macMatch.value.uppercase()
        if (mac == "00:00:00:00:00:00") return null

        val ipMatch = ipRegex.find(line)
        val ip = ipMatch?.value ?: return null

        // تحقق من الحالة
        val upperLine = line.uppercase()
        val state = when {
            upperLine.contains("REACHABLE") -> "REACHABLE"
            upperLine.contains("STALE") -> "STALE"
            upperLine.contains("DELAY") -> "DELAY"
            upperLine.contains("PROBE") -> "PROBE"
            upperLine.contains("FAILED") -> return null  // غير متصل
            upperLine.contains("INCOMPLETE") -> return null  // غير متصل
            else -> "UNKNOWN"
        }

        return Device(
            mac = mac,
            ip = ip,
            hostname = nameFor(ip, mac),
            connectionType = if (ip == (try { storage.getRouterIp() } catch (_: Exception) { "" })) "Router" else "WiFi ($state)"
        )
    }

    private fun readArpFromFile(): List<Device> {
        val devices = mutableListOf<Device>()
        var reader: BufferedReader? = null
        try {
            val file = File("/proc/net/arp")
            if (!file.exists() || !file.canRead()) return emptyList()
            reader = BufferedReader(FileReader(file))
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
                            devices.add(makeDevice(ip, mac))
                        }
                    }
                } catch (_: Exception) {}
                line = reader.readLine()
            }
        } catch (_: Exception) {}
        finally { try { reader?.close() } catch (_: Exception) {} }
        return devices
    }

    private fun readArpFromCommand(command: String): List<Device> {
        val devices = mutableListOf<Device>()
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line = reader.readLine()
            while (line != null) {
                try {
                    val device = parseArpLine(line)
                    if (device != null) devices.add(device)
                } catch (_: Exception) {}
                line = reader.readLine()
            }
            process.waitFor()
        } catch (_: Exception) {}
        finally { try { process?.destroy() } catch (_: Exception) {} }
        return devices
    }

    private fun parseArpLine(line: String): Device? {
        val macRegex = Regex("[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}")
        val ipRegex = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
        val macMatch = macRegex.find(line) ?: return null
        val mac = macMatch.value.uppercase()
        if (mac == "00:00:00:00:00:00") return null
        val ipMatch = ipRegex.find(line) ?: return null
        return makeDevice(ipMatch.value, mac)
    }

    private fun readFromRouterApi(debug: StringBuilder): List<Device> {
        try {
            val api = RetrofitClient.getApi()
            val cmds = listOf("station_list", "wifi_station_list", "dhcp_list", "client_list")
            for (cmd in cmds) {
                try {
                    val r = api.getGenericCmd(cmd = cmd)
                    val b = r.body()?.string() ?: ""
                    debug.appendLine("  [$cmd] -> ${b.take(100)}")
                    if (b.length > 30 && !b.contains("\"\":\"$cmd\"")) {
                        val devices = parseDevices(b)
                        if (devices.isNotEmpty()) return devices
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
        val routerIp = try { storage.getRouterIp() } catch (_: Exception) { "" }
        return Device(mac = mac, ip = ip, hostname = nameFor(ip, mac), connectionType = if (ip == routerIp) "Router" else "WiFi")
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
        return when { v.isNotBlank() -> "$v ($s)"; s == "1" -> "الراوتر"; else -> "جهاز .$s" }
    }

    // ═══════════════════════════════════════════
    // حظر — مع اختبار هل نجح فعلاً
    // ═══════════════════════════════════════════
    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")

                debug.appendLine("=== BLOCK $mac ===")
                debug.appendLine("List: $newList")

                // ═══ المحاولة 1: إرسال القائمة كاملة ═══
                debug.appendLine("\n--- Try 1: Full list ---")
                try {
                    val r1 = api.setMacFilter(macList = newList)
                    val b1 = r1.body()?.string() ?: ""
                    debug.appendLine("  Code: ${r1.code()}, Body: $b1")
                } catch (e: Exception) {
                    debug.appendLine("  Error: ${e.message}")
                }

                // ═══ المحاولة 2: تفعيل الفلتر أولاً ثم القائمة ═══
                debug.appendLine("\n--- Try 2: Enable + Set ---")
                try {
                    val r2 = api.enableMacFilter(
                        mode = "0",
                        macList = newList
                    )
                    val b2 = r2.body()?.string() ?: ""
                    debug.appendLine("  Code: ${r2.code()}, Body: $b2")
                } catch (e: Exception) {
                    debug.appendLine("  Error: ${e.message}")
                }

                // ═══ المحاولة 3: POST يدوي ═══
                debug.appendLine("\n--- Try 3: Manual POST ---")
                try {
                    val body = "isTest=false&goformId=SET_WIFI_MAC_FILTER&mac_filter_enabled=1&mac_filter_mode=0&mac_filter_list=$newList"
                    val requestBody = okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/x-www-form-urlencoded"), body
                    )
                    val r3 = api.postRaw(requestBody)
                    val b3 = r3.body()?.string() ?: ""
                    debug.appendLine("  Code: ${r3.code()}, Body: $b3")
                } catch (e: Exception) {
                    debug.appendLine("  Error: ${e.message}")
                }

                // ═══ تحقق: هل الحظر نجح؟ ═══
                debug.appendLine("\n--- Verify ---")
                try {
                    val verify = api.getMacFilterList()
                    val vBody = verify.body()?.string() ?: ""
                    debug.appendLine("  Filter list: $vBody")
                    if (vBody.contains(mac, ignoreCase = true)) {
                        debug.appendLine("  ✅ MAC found in filter!")
                    } else {
                        debug.appendLine("  ❌ MAC NOT in filter")
                    }
                } catch (e: Exception) {
                    debug.appendLine("  Verify error: ${e.message}")
                }

                lastRawResponse = debug.toString()

                // نُرجع النتيجة بناءً على التحقق
                val verifyBody = try {
                    RetrofitClient.getApi().getMacFilterList().body()?.string() ?: ""
                } catch (_: Exception) { "" }

                if (verifyBody.contains(mac, ignoreCase = true)) {
                    Result.success("تم حظر الجهاز ✓")
                } else {
                    Result.failure(Exception("الراوتر لم ينفذ الحظر\n\n$debug"))
                }
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    // ═══════════════════════════════════════════
    // إلغاء حظر
    // ═══════════════════════════════════════════
    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val newList = currentBlockedList.filter { it.uppercase() != mac.uppercase() }.joinToString(";")

                // جرب إزالة MAC
                val r = if (newList.isEmpty()) {
                    api.disableMacFilter()
                } else {
                    api.enableMacFilter(mode = "0", macList = newList)
                }

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

    suspend fun testRouterConnection(): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                debug.appendLine("=== TEST ===")

                try {
                    val r = RetrofitClient.getApi().getGenericCmd(cmd = "Language")
                    debug.appendLine("Language: ${r.body()?.string()}")
                } catch (e: Exception) { debug.appendLine("Language error: ${e.message}") }

                try {
                    val r = RetrofitClient.getApi().getMacFilterList()
                    debug.appendLine("MAC filter: ${r.body()?.string()}")
                } catch (e: Exception) { debug.appendLine("MAC filter error: ${e.message}") }

                debug.appendLine("\n--- ARP Methods ---")
                debug.appendLine("ip neigh:")
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh"))
                    val output = BufferedReader(InputStreamReader(p.inputStream)).readText()
                    p.waitFor()
                    debug.appendLine(output.take(500))
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                debug.appendLine("\n/proc/net/arp:")
                try {
                    val f = File("/proc/net/arp")
                    debug.appendLine("Exists: ${f.exists()}, Readable: ${f.canRead()}")
                    if (f.canRead()) debug.appendLine(f.readText().take(500))
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                Result.success(debug.toString())
            }
        } catch (e: Exception) { Result.failure(Exception("Test failed: ${e.message}")) }
    }

    suspend fun logout() {
        try { withContext(Dispatchers.IO) { try { RetrofitClient.getApi().logout() } catch (_: Exception) {} } } catch (_: Exception) {}
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
            return macs.mapIndexed { i, mac -> Device(mac = mac, ip = ips.getOrNull(i) ?: "", hostname = "جهاز ${i + 1}", connectionType = "WiFi") }
        } catch (_: Exception) { return emptyList() }
    }
}
