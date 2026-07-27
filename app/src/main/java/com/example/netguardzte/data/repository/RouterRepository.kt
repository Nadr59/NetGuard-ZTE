package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private fun fetchUrl(url: String, cookies: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Cookie", cookies)
                .build()
            httpClient.newCall(request).execute().body?.string() ?: ""
        } catch (_: Exception) { "" }
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

                flushArpCache(debug)
                forceArpEntries(subnet, debug)

                var devices = readArpFromAllSources(debug)
                debug.appendLine("Found: ${devices.size}")

                if (devices.isEmpty()) {
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
    // حظر — اكتشاف من RequireJS modules
    // ═══════════════════════════════════════════
    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")
                val routerIp = storage.getRouterIp()
                val cookies = RetrofitClient.getCookiesString()

                debug.appendLine("=== BLOCK $mac ===")

                // ═══ الخطوة 1: اسحب index.html واستخرج RequireJS modules ═══
                debug.appendLine("\n=== FIND MODULES ===")
                val allJsContent = StringBuilder()

                // اسحب index.html أولاً
                val indexHtml = fetchUrl("http://$routerIp/index.html", cookies)
                debug.appendLine("index.html: ${indexHtml.length} chars")
                allJsContent.appendLine(indexHtml)

                // استخرج مسارات الملفات من index.html
                val scriptPattern = Regex("""src=['"]([^'"]+\.js[^'"]*)['"]""")
                val scripts = scriptPattern.findAll(indexHtml).map { it.groupValues[1] }.toList()
                debug.appendLine("Scripts found: ${scripts.joinToString(", ")}")

                // استخرج data-main من RequireJS
                val dataMainPattern = Regex("""data-main=['"]([^'"]+)['"]""")
                val dataMain = dataMainPattern.find(indexHtml)?.groupValues?.get(1)
                debug.appendLine("RequireJS data-main: $dataMain")

                // اسحب ملفات JS المكتشفة
                for (script in scripts) {
                    val url = if (script.startsWith("http")) script else "http://$routerIp/$script"
                    val content = fetchUrl(url, cookies)
                    if (content.length > 50) {
                        debug.appendLine("  $script -> ${content.length} chars")
                        allJsContent.appendLine(content)
                    }
                }

                // ═══ اسحب RequireJS modules الشائعة ═══
                val modulePaths = listOf(
                    // config modules
                    "config/config.js", "config/menu.js", "config/wifiConfig.js",
                    "config/serverConfig.js", "config/settings.js",
                    // status modules
                    "status/statusBar.js", "status/status.js",
                    // wifi/wlan modules
                    "wifi/wifi.js", "wifi/macFilter.js", "wifi/wifiSettings.js",
                    "wifi/wifiSecurity.js", "wifi/advance.js",
                    "wlan/wlan.js", "wlan/macFilter.js", "wlan/wlanSettings.js",
                    "wlan/security.js", "wlan/advance.js",
                    "wlanMultiMacFilter/wlanMultiMacFilter.js",
                    "macFilter/macFilter.js",
                    "mac_filter/mac_filter.js",
                    // settings modules
                    "settings/wifi.js", "settings/wlan.js", "settings/security.js",
                    "settings/advance.js", "settings/macFilter.js",
                    // security modules
                    "security/security.js", "security/macFilter.js",
                    // access control
                    "accessControl/accessControl.js", "access_control/access_control.js",
                    // other common modules
                    "router.js", "login.js", "language.js", "logout.js",
                    "firewall/firewall.js", "parentalControl/parentalControl.js",
                    // tmpl templates
                    "../tmpl/wifi.html", "../tmpl/wlan.html",
                    "../tmpl/macFilter.html", "../tmpl/security.html",
                    "../tmpl/advance.html", "../tmpl/settings.html",
                    "../tmpl/accessControl.html", "../tmpl/parental.html"
                )

                for (mod in modulePaths) {
                    val url = "http://$routerIp/js/$mod"
                    val content = fetchUrl(url, cookies)
                    if (content.length > 100) {
                        debug.appendLine("  js/$mod -> ${content.length} chars")
                        debug.appendLine("  Preview: ${content.take(200)}")
                        allJsContent.appendLine(content)
                    }
                }

                // ═══ الخطوة 2: حلل كل المحتوى ═══
                debug.appendLine("\n=== ANALYZE ===")
                val fullText = allJsContent.toString()

                val goformIds = mutableSetOf<String>()
                val macParams = mutableSetOf<String>()

                // ابحث عن SET_ commands
                for (m in Regex("""['"](SET_[A-Z_]+)['"]""").findAll(fullText)) {
                    goformIds.add(m.groupValues[1])
                }
                for (m in Regex("""goformId['"]*\s*[=:]\s*['"]*([A-Z_a-z]+)['"]*""").findAll(fullText)) {
                    goformIds.add(m.groupValues[1])
                }
                for (m in Regex("""goformId\s*=\s*([A-Z_]+)""").findAll(fullText)) {
                    goformIds.add(m.groupValues[1])
                }

                // ابحث عن معاملات MAC/filter
                val paramPatterns = listOf(
                    Regex("""['"]([a-zA-Z_]*[Mm][Aa][Cc][a-zA-Z_]*[Ll]ist[a-zA-Z_]*)['"]"""),
                    Regex("""['"]([a-zA-Z_]*[Ff]ilter[a-zA-Z_]*[Mm]ac[a-zA-Z_]*)['"]"""),
                    Regex("""['"]([a-zA-Z_]*[Mm]ac[Ff]ilter[a-zA-Z_]*)['"]"""),
                    Regex("""['"]([a-zA-Z_]*[Bb]locked[a-zA-Z_]*[Ll]ist[a-zA-Z_]*)['"]"""),
                    Regex("""['"]([a-zA-Z_]*[Dd]eny[a-zA-Z_]*[Ll]ist[a-zA-Z_]*)['"]"""),
                    Regex("""['"]([a-zA-Z_]*[Aa]ccess[Cc]ontrol[a-zA-Z_]*)['"]"""),
                    Regex("""['"](mac_filter_enabled)['"]"""),
                    Regex("""['"](mac_filter_mode)['"]""")
                )
                for (p in paramPatterns) {
                    for (m in p.findAll(fullText)) {
                        val param = m.groupValues[1]
                        if (param.length > 3 && param.length < 50) macParams.add(param)
                    }
                }

                // ابحث عن fetch/ajax calls مع MAC
                for (m in Regex("""(?:fetch|ajax|post|get)\s*\([^)]*['"](.*?mac.*?)['"]""", RegexOption.IGNORE_CASE).findAll(fullText)) {
                    debug.appendLine("  Fetch call: ${m.groupValues[1]}")
                }

                debug.appendLine("\ngoformIds: ${goformIds.joinToString(", ")}")
                debug.appendLine("macParams: ${macParams.joinToString(", ")}")

                // ═══ الخطوة 3: جرب الأوامر ═══
                debug.appendLine("\n=== TRY COMMANDS ===")
                var success = false

                goformIds.addAll(listOf(
                    "SET_WIFI_MAC_FILTER", "SET_WIFI_AP_MAC_FILTER",
                    "SET_MAC_FILTER", "ACCESS_CONTROL_SET",
                    "ACCESS_CONTROL_ADD", "SET_PARENTAL_CONTROL",
                    "SET_BLOCKED_DEVICES"
                ))
                if (macParams.isEmpty()) {
                    macParams.addAll(listOf(
                        "mac_filter_list", "MAC_Filter_list",
                        "macFilterList", "filterMacList",
                        "macfilter_addr", "deny_list"
                    ))
                }

                for (id in goformIds) {
                    if (success) break
                    for (param in macParams) {
                        if (success) break

                        try {
                            val body = "isTest=false&goformId=$id&$param=$newList"
                            val r = api.postRaw(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                            val b = r.body()?.string() ?: ""
                            debug.appendLine("  $id/$param: ${b.take(80)}")
                            if (isSuccess(b)) { debug.appendLine("  ✅ SUCCESS!"); success = true }
                        } catch (e: Exception) { debug.appendLine("  Error: ${e.message}") }

                        try {
                            val body = "isTest=false&goformId=$id&mac_filter_enabled=1&mac_filter_mode=0&$param=$newList"
                            val r = api.postRaw(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                            val b = r.body()?.string() ?: ""
                            debug.appendLine("  $id/$param+enable: ${b.take(80)}")
                            if (isSuccess(b)) { debug.appendLine("  ✅ SUCCESS!"); success = true }
                        } catch (e: Exception) { debug.appendLine("  Error: ${e.message}") }
                    }
                }

                // ═══ تحقق ═══
                debug.appendLine("\n=== VERIFY ===")
                var verified = false
                try {
                    Thread.sleep(1000)
                    val r = api.getMacFilterList()
                    val b = r.body()?.string() ?: ""
                    debug.appendLine("Filter: $b")
                    verified = b.contains(mac, ignoreCase = true)
                    debug.appendLine(if (verified) "✅ MAC in filter!" else "❌ MAC NOT in filter")
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (verified) Result.success("تم حظر الجهاز")
                else Result.failure(Exception("فشل الحظر"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun isSuccess(body: String): Boolean {
        return body.contains("\"result\":0") || body.contains("\"result\":\"0\"") ||
                body.contains("\"result\":\"success\"") || body.contains("successful")
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
                val api = RetrofitClient.getApi()
                val routerIp = storage.getRouterIp()
                val cookies = RetrofitClient.getCookiesString()

                debug.appendLine("=== TEST ===")

                try {
                    val r = api.getGenericCmd(cmd = "Language")
                    debug.appendLine("Language: ${r.body()?.string()}")
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                // اسحب index.html
                debug.appendLine("\n--- index.html ---")
                val html = fetchUrl("http://$routerIp/index.html", cookies)
                debug.appendLine("Length: ${html.length}")

                // استخرج scripts
                val scripts = Regex("""src=['"]([^'"]+\.js[^'"]*)['"]""").findAll(html).map { it.groupValues[1] }.toList()
                debug.appendLine("Scripts: ${scripts.joinToString(", ")}")

                // اسحب كل module
                debug.appendLine("\n--- Modules ---")
                val mods = listOf(
                    "config/config.js", "config/menu.js", "router.js",
                    "wifi/wifi.js", "wifi/macFilter.js",
                    "wlan/wlan.js", "wlan/macFilter.js",
                    "macFilter/macFilter.js", "mac_filter/mac_filter.js",
                    "security/security.js", "accessControl/accessControl.js",
                    "settings/wifi.js", "settings/wlan.js",
                    "status/statusBar.js", "login.js", "language.js"
                )
                for (mod in mods) {
                    val content = fetchUrl("http://$routerIp/js/$mod", cookies)
                    if (content.length > 50) {
                        debug.appendLine("\n=== js/$mod (${content.length} chars) ===")
                        debug.appendLine(content.take(3000))
                    }
                }

                Result.success(debug.toString())
            }
        } catch (e: Exception) { Result.failure(Exception("Test: ${e.message}")) }
    }

    // ═══ باقي الدوال ═══

    private fun flushArpCache(debug: StringBuilder) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh flush dev wlan0"))
            p.waitFor()
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
        } catch (_: Exception) {}
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
