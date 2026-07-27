package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

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

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

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
    // حظر — اكتشاف تلقائي + تجربة شاملة
    // ═══════════════════════════════════════════
    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")

                debug.appendLine("=== BLOCK $mac ===")
                debug.appendLine("List: $newList")
                debug.appendLine("Cookies: ${RetrofitClient.getCookiesString()}")

                // ═══ الخطوة 1: اسحب صفحة WiFi من الراوتر ═══
                debug.appendLine("\n=== DISCOVER COMMANDS ===")
                val routerIp = storage.getRouterIp()
                val pageCmds = discoverFromPages(routerIp, debug)

                // ═══ الخطوة 2: جرب الأوامر المكتشفة ═══
                debug.appendLine("\n=== TRY DISCOVERED (${pageCmds.size}) ===")
                var success = false

                for (cmd in pageCmds) {
                    if (success) break
                    debug.appendLine("\n--- ${cmd.goformId} | ${cmd.paramName} ---")
                    try {
                        val body = "isTest=false&goformId=${cmd.goformId}&${cmd.paramName}=$newList"
                        val r = api.postRaw(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                        val b = r.body()?.string() ?: ""
                        debug.appendLine("  semicolon: ${r.code()} ${b.take(150)}")
                        if (b.contains("\"result\":0") || b.contains("\"result\":\"0\"") ||
                            b.contains("success")) {
                            debug.appendLine("  ✅ SUCCESS!")
                            success = true
                        }
                    } catch (e: Exception) {
                        debug.appendLine("  Error: ${e.message}")
                    }

                    // جرب مع تفعيل الفلتر
                    try {
                        val body = "isTest=false&goformId=${cmd.goformId}&mac_filter_enabled=1&mac_filter_mode=0&${cmd.paramName}=$newList"
                        val r = api.postRaw(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                        val b = r.body()?.string() ?: ""
                        debug.appendLine("  with enable: ${r.code()} ${b.take(150)}")
                        if (b.contains("\"result\":0") || b.contains("\"result\":\"0\"") ||
                            b.contains("success")) {
                            debug.appendLine("  ✅ SUCCESS with enable!")
                            success = true
                        }
                    } catch (e: Exception) {
                        debug.appendLine("  Error: ${e.message}")
                    }
                }

                // ═══ الخطوة 3: جرب 15+ أمر معروف ═══
                debug.appendLine("\n=== TRY KNOWN COMMANDS ===")
                data class CmdAttempt(val goformId: String, val paramName: String, val extra: Map<String, String> = emptyMap())

                val attempts = listOf(
                    CmdAttempt("SET_WIFI_MAC_FILTER", "mac_filter_list"),
                    CmdAttempt("SET_WIFI_MAC_FILTER", "MAC_Filter_list"),
                    CmdAttempt("SET_WIFI_MAC_FILTER", "macFilterList"),
                    CmdAttempt("SET_WIFI_MAC_FILTER", "filterMacList"),
                    CmdAttempt("SET_WIFI_AP_MAC_FILTER", "mac_filter_list"),
                    CmdAttempt("SET_WIFI_AP_MAC_FILTER", "macfilter_addr"),
                    CmdAttempt("SET_MAC_FILTER", "mac_filter_list"),
                    CmdAttempt("SET_MAC_FILTER", "macFilterList"),
                    CmdAttempt("ACCESS_CONTROL_SET", "access_control_list"),
                    CmdAttempt("ACCESS_CONTROL_ADD", "mac_filter_list"),
                    CmdAttempt("SET_PARENTAL_CONTROL", "mac_filter_list"),
                    CmdAttempt("WLAN_SET_MAC_FILTER", "mac_filter_list"),
                    CmdAttempt("SET_BLOCKED_DEVICES", "blocked_list"),
                    CmdAttempt("WIFI_MAC_FILTER_SET", "mac_filter_list"),
                    CmdAttempt("SET_LAN_MAC_FILTER", "mac_filter_list"),
                    CmdAttempt("SET_WIFI_AP_macfilter", "macfilter_addr"),
                    CmdAttempt("SET_WIFI_ACCESS_CONTROL", "mac_filter_list"),
                    // مع تفعيل الفلتر
                    CmdAttempt("SET_WIFI_MAC_FILTER", "mac_filter_list", mapOf("mac_filter_enabled" to "1", "mac_filter_mode" to "0")),
                    CmdAttempt("SET_WIFI_MAC_FILTER", "mac_filter_list", mapOf("wifi_mac_filter_enabled" to "1", "wifi_mac_filter_mode" to "0")),
                )

                for (a in attempts) {
                    if (success) break
                    debug.appendLine("\n--- ${a.goformId} / ${a.paramName} ---")

                    // بناء body
                    val fields = mutableMapOf(
                        "isTest" to "false",
                        "goformId" to a.goformId,
                        a.paramName to newList
                    )
                    fields.putAll(a.extra)
                    val bodyStr = fields.entries.joinToString("&") { "${it.key}=${it.value}" }

                    try {
                        val r = api.postRaw(bodyStr.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                        val b = r.body()?.string() ?: ""
                        debug.appendLine("  ${r.code()} ${b.take(150)}")
                        if (b.contains("\"result\":0") || b.contains("\"result\":\"0\"") ||
                            b.contains("success")) {
                            debug.appendLine("  ✅ SUCCESS!")
                            success = true
                        }
                    } catch (e: Exception) {
                        debug.appendLine("  Error: ${e.message}")
                    }
                }

                // ═══ الخطوة 4: جرب setMacFilter + enableMacFilter ═══
                debug.appendLine("\n=== TRY API METHODS ===")
                try {
                    val r1 = api.setMacFilter(macList = newList)
                    debug.appendLine("setMacFilter: ${r1.code()} ${r1.body()?.string()?.take(100)}")
                } catch (e: Exception) { debug.appendLine("setMacFilter error: ${e.message}") }

                try {
                    val r2 = api.enableMacFilter(mode = "0", macList = newList)
                    debug.appendLine("enableMacFilter: ${r2.code()} ${r2.body()?.string()?.take(100)}")
                } catch (e: Exception) { debug.appendLine("enableMacFilter error: ${e.message}") }

                // ═══ الخطوة 5: تحقق ═══
                debug.appendLine("\n=== VERIFY ===")
                var verified = false
                try {
                    Thread.sleep(1000) // انتظر ثانية
                    val r = api.getMacFilterList()
                    val b = r.body()?.string() ?: ""
                    debug.appendLine("Filter: $b")
                    verified = b.contains(mac, ignoreCase = true)
                    debug.appendLine(if (verified) "✅ MAC in filter!" else "❌ MAC NOT in filter")
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (verified) {
                    Result.success("تم حظر الجهاز")
                } else {
                    Result.failure(Exception("فشل الحظر — اضغط 🔍 للتفاصيل"))
                }
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    // ═══ اكتشاف أوامر من صفحات الراوتر ═══
    private fun discoverFromPages(routerIp: String, debug: StringBuilder): List<DiscoveredCmd> {
        val cmds = mutableListOf<DiscoveredCmd>()
        val pages = listOf(
            "", "index.html", "wifi.html", "status.html",
            "wlan.html", "wlanSettings.html", "wlanMultiMacFilter.asp",
            "wlsecurity.asp", "wlmacflt.asp", "settings.html",
            "security.html", "access_control.html", "mac_filter.html",
            "advance.html", "parental.html", "wps.html"
        )

        val allHtml = StringBuilder()

        for (page in pages) {
            try {
                val url = "http://$routerIp/$page"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                val html = response.body?.string() ?: ""
                if (html.length > 100) {
                    debug.appendLine("  /$page -> ${html.length} chars")
                    allHtml.appendLine(html)

                    // استخرج goformId
                    val goformPattern = Regex("""goformId['"]*\s*[=:]\s*['"]*([A-Z_a-z]{5,})['"]*""")
                    for (m in goformPattern.findAll(html)) {
                        val id = m.groupValues[1]
                        debug.appendLine("    goformId: $id")
                    }

                    // استخرج معاملات MAC
                    val macParamPattern = Regex("""['"]([a-zA-Z_]*[Mm][Aa][Cc][a-zA-Z_]*[Ll]ist[a-zA-Z_]*)['"]""")
                    for (m in macParamPattern.findAll(html)) {
                        debug.appendLine("    mac param: ${m.groupValues[1]}")
                    }

                    val filterParamPattern = Regex("""['"]([a-zA-Z_]*[Ff]ilter[a-zA-Z_]*[Mm]ac[a-zA-Z_]*)['"]""")
                    for (m in filterParamPattern.findAll(html)) {
                        debug.appendLine("    filter param: ${m.groupValues[1]}")
                    }
                }
            } catch (_: Exception) {}
        }

        // استخرج من JavaScript
        val jsFiles = listOf("js/common.js", "js/config.js", "js/index.js",
            "js/wifi.js", "js/wlan.js", "js/main.js", "js/app.js",
            "js/settings.js", "js/security.js", "static/js/main.js",
            "js/wireless.js", "js/advance.js", "js/goform.js")

        for (js in jsFiles) {
            try {
                val url = "http://$routerIp/$js"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                val content = response.body?.string() ?: ""
                if (content.length > 100) {
                    debug.appendLine("  /$js -> ${content.length} chars")
                    allHtml.appendLine(content)
                }
            } catch (_: Exception) {}
        }

        // حلل كل المحتوى
        val allText = allHtml.toString()

        // ابحث عن goformIds
        val goformIds = mutableSetOf<String>()
        for (m in Regex("""goformId['"]*\s*[=:]\s*['"]*([A-Z_a-z]{5,})['"]*""").findAll(allText)) {
            goformIds.add(m.groupValues[1])
        }
        for (m in Regex("""['"]goformId['"]\s*:\s*['"]([^'"]+)['"]""").findAll(allText)) {
            goformIds.add(m.groupValues[1])
        }

        debug.appendLine("\n  All goformIds found: ${goformIds.joinToString(", ")}")

        // ابحث عن معاملات MAC
        val macParams = mutableSetOf<String>()
        for (m in Regex("""['"]([a-zA-Z_]*[Mm][Aa][Cc][a-zA-Z_]*[Ll]ist[a-zA-Z_]*)['"]""").findAll(allText)) {
            macParams.add(m.groupValues[1])
        }
        for (m in Regex("""['"]([a-zA-Z_]*[Ff]ilter[a-zA-Z_]*[Mm]ac[a-zA-Z_]*)['"]""").findAll(allText)) {
            macParams.add(m.groupValues[1])
        }
        for (m in Regex("""['"]([a-zA-Z_]*[Bb]locked[a-zA-Z_]*[Ll]ist[a-zA-Z_]*)['"]""").findAll(allText)) {
            macParams.add(m.groupValues[1])
        }
        for (m in Regex("""['"]([a-zA-Z_]*[Dd]eny[a-zA-Z_]*[Ll]ist[a-zA-Z_]*)['"]""").findAll(allText)) {
            macParams.add(m.groupValues[1])
        }

        debug.appendLine("  All mac params found: ${macParams.joinToString(", ")}")

        // ابحث عن أسماء معاملات في setMacFilter calls
        for (m in Regex("""\.\w+\(\s*\{[^}]*mac_filter_list""", RegexOption.IGNORE_CASE).findAll(allText)) {
            debug.appendLine("  Found pattern: ${m.value.take(100)}")
        }

        // ابحث عن أي شيء يحتوي "filter" و "mac" معًا
        for (m in Regex("""['"](SET_[A-Z_]*MAC[A-Z_]*FILTER[A-Z_]*)['"]""").findAll(allText)) {
            goformIds.add(m.groupValues[1])
        }
        for (m in Regex("""['"](SET_[A-Z_]*FILTER[A-Z_]*MAC[A-Z_]*)['"]""").findAll(allText)) {
            goformIds.add(m.groupValues[1])
        }

        // إنشاء أوامر من الاكتشاف
        if (macParams.isEmpty()) {
            macParams.addAll(listOf("mac_filter_list", "MAC_Filter_list", "macFilterList"))
        }

        for (id in goformIds) {
            for (param in macParams) {
                cmds.add(DiscoveredCmd(id, param))
            }
        }

        debug.appendLine("  Commands to try: ${cmds.size}")
        return cmds
    }

    private data class DiscoveredCmd(val goformId: String, val paramName: String)

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
                debug.appendLine("\n--- Discover commands ---")
                discoverFromPages(storage.getRouterIp(), debug)
                Result.success(debug.toString())
            }
        } catch (e: Exception) { Result.failure(Exception("Test: ${e.message}")) }
    }

    private fun flushArpCache(debug: StringBuilder) {
        try {
            for (cmd in listOf("ip neigh flush dev wlan0", "ip -s neigh flush dev wlan0")) {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    p.waitFor()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun forceArpEntries(subnet: String, debug: StringBuilder) {
        try {
            for (i in 1..50) {
                for (port in listOf(80, 443)) {
                    try {
                        val s = java.net.Socket()
                        s.connect(java.net.InetSocketAddress("$subnet.$i", port), 30)
                        s.close()
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun readArpFromAllSources(debug: StringBuilder): List<Device> {
        var d = readIpNeigh(debug); if (d.isNotEmpty()) return d
        d = readArpFromFile(); if (d.isNotEmpty()) return d
        d = readArpFromCommand("arp -a"); if (d.isNotEmpty()) return d
        return readArpFromCommand("cat /proc/net/arp")
    }

    private fun readIpNeigh(debug: StringBuilder): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh"))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) {
                try {
                    if (!line.uppercase().contains("FAILED") && !line.uppercase().contains("INCOMPLETE")) {
                        parseArpLine(line)?.let { devices.add(it) }
                    }
                } catch (_: Exception) {}
                line = r.readLine()
            }
            p.waitFor()
            debug.appendLine("  ip neigh: ${devices.size}")
        } catch (e: Exception) { debug.appendLine("  ip neigh: ${e.message}") }
        return devices
    }

    private fun readArpFromFile(): List<Device> {
        val devices = mutableListOf<Device>()
        var r: BufferedReader? = null
        try {
            val f = File("/proc/net/arp")
            if (!f.exists() || !f.canRead()) return emptyList()
            r = java.io.BufferedReader(java.io.FileReader(f))
            r.readLine()
            var line = r.readLine()
            while (line != null) {
                try {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 4 && parts[3].uppercase() != "00:00:00:00:00:00" && parts[2] != "0x0") {
                        devices.add(makeDevice(parts[0], parts[3].uppercase()))
                    }
                } catch (_: Exception) {}
                line = r.readLine()
            }
        } catch (_: Exception) {}
        finally { try { r?.close() } catch (_: Exception) {} }
        return devices
    }

    private fun readArpFromCommand(command: String): List<Device> {
        val devices = mutableListOf<Device>()
        var p: Process? = null
        try {
            p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) {
                try { parseArpLine(line)?.let { devices.add(it) } } catch (_: Exception) {}
                line = r.readLine()
            }
            p.waitFor()
        } catch (_: Exception) {}
        finally { try { p?.destroy() } catch (_: Exception) {} }
        return devices
    }

    private fun parseArpLine(line: String): Device? {
        val mac = Regex("[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}").find(line)?.value?.uppercase() ?: return null
        if (mac == "00:00:00:00:00:00") return null
        val ip = Regex("(\\d{1,3}\\.){3}\\d{1,3}").find(line)?.value ?: return null
        return makeDevice(ip, mac)
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
                        val d = parseDevices(b)
                        if (d.isNotEmpty()) return d
                    }
                } catch (e: Exception) { debug.appendLine("  [$cmd] error: ${e.message}") }
            }
        } catch (e: Exception) { debug.appendLine("  API error: ${e.message}") }
        return emptyList()
    }

    private fun makeDevice(ip: String, mac: String): Device {
        val rIp = try { storage.getRouterIp() } catch (_: Exception) { "" }
        return Device(mac = mac, ip = ip, hostname = nameFor(ip, mac), connectionType = if (ip == rIp) "Router" else "WiFi")
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
            mac.startsWith("00:21") -> "ZTE"
            else -> ""
        }
        val s = ip.substringAfterLast(".")
        return when { v.isNotBlank() -> "$v ($s)"; s == "1" -> "الراوتر"; else -> "جهاز .$s" }
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
            val macs = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}").findAll(raw).map { it.value.uppercase() }.distinct().toList()
            if (macs.isEmpty()) return emptyList()
            val ips = Regex("(\\d{1,3}\\.){3}\\d{1,3}").findAll(raw).map { it.value }.toList()
            return macs.mapIndexed { i, mac -> Device(mac = mac, ip = ips.getOrNull(i) ?: "", hostname = "جهاز ${i + 1}", connectionType = "WiFi") }
        } catch (_: Exception) { return emptyList() }
    }
}
