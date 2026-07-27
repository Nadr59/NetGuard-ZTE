package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.BufferedReader
import java.io.File
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

                debug.appendLine("\n--- Flush ARP ---")
                flushArpCache(debug)

                debug.appendLine("\n--- Force ARP ---")
                forceArpEntries(subnet, debug)

                debug.appendLine("\n--- Read ARP ---")
                var devices = readArpFromAllSources(debug)
                debug.appendLine("Found: ${devices.size}")

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

    // ═══════════════════════════════════════════
    // حظر — بحث شامل عن الأمر الصحيح
    // ═══════════════════════════════════════════
    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")
                val newListComma = (currentBlockedList + mac.uppercase()).joinToString(",")

                debug.appendLine("=== BLOCK $mac ===")
                debug.appendLine("New list (semicolon): $newList")
                debug.appendLine("New list (comma): $newListComma")

                // ═══ الخطوة 1: اكتشف goformIds من صفحات الراوتر ═══
                debug.appendLine("\n--- Discover from pages ---")
                val discoveredIds = discoverGoformIds(debug)

                // ═══ الخطوة 2: جرب كل goformId مكتشف ═══
                for (id in discoveredIds) {
                    debug.appendLine("\nTrying discovered: $id")
                    try {
                        val fields = mapOf(
                            "isTest" to "false",
                            "goformId" to id,
                            "mac_filter_enabled" to "1",
                            "mac_filter_mode" to "0",
                            "mac_filter_list" to newList,
                            "MAC_Filter_list" to newList,
                            "MAC_list" to newList,
                            "mac_list" to newList,
                            "wifi_mac_filter_list" to newList,
                            "filterMacList" to newList,
                            "macFilterList" to newList,
                            "denyList" to newList,
                            "allowList" to newList,
                            "blocked_list" to newList
                        )
                        val body = fields.entries.joinToString("&") { "${it.key}=${it.value}" }
                        val requestBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType())
                        val r = api.postRaw(requestBody)
                        val b = r.body()?.string() ?: ""
                        debug.appendLine("  Code: ${r.code()}, Body: ${b.take(200)}")
                    } catch (e: Exception) {
                        debug.appendLine("  Error: ${e.message}")
                    }
                }

                // ═══ الخطوة 3: جرب أوامر معروفة ═══
                debug.appendLine("\n--- Known commands ---")
                val knownIds = listOf(
                    "SET_WIFI_MAC_FILTER",
                    "SET_WIFI_AP_MAC_FILTER",
                    "SET_MAC_FILTER",
                    "ACCESS_CONTROL_SET",
                    "ACCESS_CONTROL_ADD",
                    "PARENTAL_CONTROL_SET",
                    "SET_PARENTAL_CONTROL",
                    "SET_WIFI_ACCESS_CONTROL",
                    "WLAN_SET_MAC_FILTER",
                    "SET_BLOCKED_DEVICES",
                    "WIFI_MAC_FILTER_SET",
                    "SET_LAN_MAC_FILTER"
                )

                for (id in knownIds) {
                    debug.appendLine("\n--- $id ---")

                    // محاولة 1: فاصلة منقوطة
                    try {
                        val body = "isTest=false&goformId=$id&mac_filter_enabled=1&mac_filter_mode=0&mac_filter_list=$newList"
                        val r = api.postRaw(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                        val b = r.body()?.string() ?: ""
                        debug.appendLine("  semicolon: ${r.code()} ${b.take(100)}")
                        if (b.contains("\"result\":\"0\"") || b.contains("\"result\":0") ||
                            b.contains("\"result\":\"success\"") || b.contains("successful")) {
                            debug.appendLine("  ✅ SUCCESS with $id!")
                        }
                    } catch (e: Exception) {
                        debug.appendLine("  Error: ${e.message}")
                    }

                    // محاولة 2: فاصلة عادية
                    try {
                        val body = "isTest=false&goformId=$id&mac_filter_enabled=1&mac_filter_mode=0&mac_filter_list=$newListComma"
                        val r = api.postRaw(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                        val b = r.body()?.string() ?: ""
                        debug.appendLine("  comma: ${r.code()} ${b.take(100)}")
                        if (b.contains("\"result\":\"0\"") || b.contains("\"result\":0") ||
                            b.contains("\"result\":\"success\"") || b.contains("successful")) {
                            debug.appendLine("  ✅ SUCCESS with $id (comma)!")
                        }
                    } catch (e: Exception) {
                        debug.appendLine("  Error: ${e.message}")
                    }

                    // محاولة 3: بدون مُحددات إضافية
                    try {
                        val body = "isTest=false&goformId=$id&mac_filter_list=$newList"
                        val r = api.postRaw(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                        val b = r.body()?.string() ?: ""
                        debug.appendLine("  minimal: ${r.code()} ${b.take(100)}")
                        if (b.contains("\"result\":\"0\"") || b.contains("\"result\":0") ||
                            b.contains("\"result\":\"success\"") || b.contains("successful")) {
                            debug.appendLine("  ✅ SUCCESS with $id (minimal)!")
                        }
                    } catch (e: Exception) {
                        debug.appendLine("  Error: ${e.message}")
                    }
                }

                // ═══ الخطوة 4: جرب setMacFilter الأصلي ═══
                debug.appendLine("\n--- Original setMacFilter ---")
                try {
                    val r = api.setMacFilter(macList = newList)
                    debug.appendLine("  Code: ${r.code()}, Body: ${r.body()?.string()?.take(100)}")
                } catch (e: Exception) {
                    debug.appendLine("  Error: ${e.message}")
                }

                try {
                    val r = api.enableMacFilter(mode = "0", macList = newList)
                    debug.appendLine("  enableMacFilter: ${r.code()}, Body: ${r.body()?.string()?.take(100)}")
                } catch (e: Exception) {
                    debug.appendLine("  Error: ${e.message}")
                }

                // ═══ الخطوة 5: تحقق ═══
                debug.appendLine("\n--- Verify ---")
                var verified = false
                try {
                    val r = api.getMacFilterList()
                    val b = r.body()?.string() ?: ""
                    debug.appendLine("  Filter: $b")
                    verified = b.contains(mac, ignoreCase = true)
                    debug.appendLine(if (verified) "  ✅ MAC in filter!" else "  ❌ MAC NOT in filter")
                } catch (e: Exception) {
                    debug.appendLine("  Error: ${e.message}")
                }

                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (verified) {
                    Result.success("تم حظر الجهاز")
                } else {
                    Result.failure(Exception("لم يتم الحظر — اضغط 🔍 لرؤية التفاصيل"))
                }
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    // ═══ اكتشاف goformIds من صفحات الراوتر ═══
    private fun discoverGoformIds(debug: StringBuilder): List<String> {
        val ids = mutableSetOf<String>()
        try {
            val routerIp = storage.getRouterIp()
            val pages = listOf("", "index.html", "wifi.html", "status.html", "settings.html",
                "security.html", "access_control.html", "mac_filter.html", "wlan.html",
                "wlanMultiMacFilter.asp", "wlsecurity.asp", "wlmacflt.asp",
                "parental_control.html", "advance.html")

            for (page in pages) {
                try {
                    val url = "http://$routerIp/$page"
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    val html = response.body?.string() ?: ""

                    if (html.length > 100) {
                        debug.appendLine("  /$page -> ${html.length} chars")

                        // استخرج goformId
                        val patterns = listOf(
                            Regex("""goformId['":\s=]+['"]*([A-Z_]+)['"]*"""),
                            Regex("""goformId['"]*\s*:\s*['"]([A-Z_]+)['"]"""),
                            Regex("""cmd['":\s=]+['"]*([A-Z_]+)['"]*"""),
                            Regex("""['"]([A-Z_]{8,})['"]""")
                        )

                        for (p in patterns) {
                            for (m in p.findAll(html)) {
                                val id = m.groupValues[1]
                                if (id.length > 5 && id.contains("FILTER") || id.contains("MAC") ||
                                    id.contains("ACCESS") || id.contains("BLOCK") ||
                                    id.contains("CONTROL") || id.contains("PARENTAL") ||
                                    id.contains("WLAN") || id.contains("WIFI")) {
                                    ids.add(id)
                                    debug.appendLine("    Found: $id")
                                }
                            }
                        }

                        // استخرج أسماء المعاملات
                        val paramPatterns = listOf(
                            Regex("""['"]([a-zA-Z_]*mac[a-zA-Z_]*list[a-zA-Z_]*)['"]"""),
                            Regex("""['"]([a-zA-Z_]*filter[a-zA-Z_]*mac[a-zA-Z_]*)['"]"""),
                            Regex("""['"]([a-zA-Z_]*blocked[a-zA-Z_]*)['"]"""),
                            Regex("""['"]([a-zA-Z_]*deny[a-zA-Z_]*)['"]""")
                        )
                        for (p in paramPatterns) {
                            for (m in p.findAll(html)) {
                                debug.appendLine("    Param: ${m.groupValues[1]}")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            debug.appendLine("  Discovery error: ${e.message}")
        }

        // أضف افتراضيات
        ids.addAll(listOf(
            "SET_WIFI_MAC_FILTER",
            "SET_WIFI_AP_MAC_FILTER",
            "SET_MAC_FILTER"
        ))

        debug.appendLine("  Total IDs to try: ${ids.size}")
        return ids.toList()
    }

    // ═══ باقي الدوال ═══

    private fun flushArpCache(debug: StringBuilder) {
        try {
            val commands = listOf("ip neigh flush dev wlan0", "ip -s neigh flush dev wlan0")
            for (cmd in commands) {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    p.waitFor()
                    debug.appendLine("  $cmd -> done")
                } catch (e: Exception) { debug.appendLine("  $cmd -> ${e.message}") }
            }
        } catch (e: Exception) { debug.appendLine("Flush error: ${e.message}") }
    }

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
            debug.appendLine("  TCP done")
        } catch (e: Exception) { debug.appendLine("  TCP error: ${e.message}") }
    }

    private suspend fun readArpFromAllSources(debug: StringBuilder): List<Device> {
        var devices = readIpNeigh(debug)
        if (devices.isNotEmpty()) return devices
        devices = readArpFromFile()
        if (devices.isNotEmpty()) return devices
        devices = readArpFromCommand("arp -a")
        if (devices.isNotEmpty()) return devices
        devices = readArpFromCommand("cat /proc/net/arp")
        return devices
    }

    private fun readIpNeigh(debug: StringBuilder): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var line = reader.readLine()
            while (line != null) {
                try {
                    val device = parseArpLine(line)
                    if (device != null) {
                        val upperLine = line.uppercase()
                        if (!upperLine.contains("FAILED") && !upperLine.contains("INCOMPLETE")) {
                            devices.add(device)
                        }
                    }
                } catch (_: Exception) {}
                line = reader.readLine()
            }
            p.waitFor()
            debug.appendLine("  ip neigh: ${devices.size}")
        } catch (e: Exception) { debug.appendLine("  ip neigh error: ${e.message}") }
        return devices
    }

    private fun readArpFromFile(): List<Device> {
        val devices = mutableListOf<Device>()
        var reader: BufferedReader? = null
        try {
            val file = File("/proc/net/arp")
            if (!file.exists() || !file.canRead()) return emptyList()
            reader = java.io.BufferedReader(java.io.FileReader(file))
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

    private suspend fun readFromRouterApi(debug: StringBuilder): List<Device> {
        try {
            val api = RetrofitClient.getApi()
            for (cmd in listOf("station_list", "wifi_station_list", "dhcp_list", "client_list")) {
                try {
                    val r = api.getGenericCmd(cmd = cmd)
                    val b = r.body()?.string() ?: ""
                    debug.appendLine("  [$cmd] -> ${b.take(100)}")
                    if (b.length > 30 && !b.contains("\"\":\"$cmd\"")) {
                        val devices = parseDevices(b)
                        if (devices.isNotEmpty()) return devices
                    }
                } catch (e: Exception) { debug.appendLine("  [$cmd] error: ${e.message}") }
            }
        } catch (e: Exception) { debug.appendLine("  API error: ${e.message}") }
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

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val newList = currentBlockedList.filter { it.uppercase() != mac.uppercase() }.joinToString(";")
                val r = if (newList.isEmpty()) api.disableMacFilter()
                else api.enableMacFilter(mode = "0", macList = newList)
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
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }
                try {
                    val r = RetrofitClient.getApi().getMacFilterList()
                    debug.appendLine("MAC filter: ${r.body()?.string()}")
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }
                debug.appendLine("\n--- ip neigh ---")
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh"))
                    val output = BufferedReader(InputStreamReader(p.inputStream)).readText()
                    p.waitFor()
                    debug.appendLine(output.take(500))
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
