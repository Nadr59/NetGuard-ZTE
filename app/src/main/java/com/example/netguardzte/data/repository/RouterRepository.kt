package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.api.ZteRouterApi
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response

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

    suspend fun login(routerIp: String, username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            RetrofitClient.reset()
            RetrofitClient.setRouterAddress(routerIp)
            val api = RetrofitClient.getApi()
            val debug = StringBuilder()

            debug.appendLine("=== STEP 1: Load main page ===")
            try {
                val mainPage = api.getMainPage()
                debug.appendLine("Main page code: ${mainPage.code()}")
                readCookiesFromResponse(mainPage, debug)
                val indexPage = api.getIndexPage()
                debug.appendLine("Index page code: ${indexPage.code()}")
                readCookiesFromResponse(indexPage, debug)
            } catch (e: Exception) { debug.appendLine("Page load error: ${e.message}") }

            debug.appendLine("\n=== STEP 2: System info ===")
            try {
                val sysInfo = api.getSystemInfo()
                val sysBody = sysInfo.body()?.string() ?: ""
                debug.appendLine("System info: ${sysBody.take(200)}")
                readCookiesFromResponse(sysInfo, debug)
            } catch (e: Exception) { debug.appendLine("System info error: ${e.message}") }

            val encodedPassword = Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            debug.appendLine("\n=== STEP 3: Login ===")
            val response = api.login(password = encodedPassword)
            val body = response.body()?.string() ?: ""
            debug.appendLine("Login code: ${response.code()}")
            debug.appendLine("Login body: ${body.take(200)}")
            readCookiesFromResponse(response, debug)
            cookieDebug = "Cookies: ${RetrofitClient.getCookiesString()}"

            if (body.contains("\"result\":\"3\"") || body.contains("\"result\":3")) {
                debug.appendLine("WRONG PASSWORD")
                loginDebug = debug.toString()
                return@withContext Result.failure(Exception("كلمة المرور خاطئة"))
            }
            if (body.contains("\"result\":\"0\"") || body.contains("\"result\":0") ||
                body.contains("\"result\":\"1\"") || body.contains("\"result\":1")) {
                debug.appendLine("LOGIN SUCCESS!")
                storage.saveCredentials(routerIp, username, password)
                storage.setLoggedIn(true)
                activateSession(api, debug)
                loginDebug = debug.toString()
                return@withContext Result.success("تم الاتصال بالراوتر")
            }
            if (response.isSuccessful) {
                storage.saveCredentials(routerIp, username, password)
                storage.setLoggedIn(true)
                activateSession(api, debug)
                loginDebug = debug.toString()
                return@withContext Result.success("تم الاتصال (${response.code()})")
            }
            loginDebug = debug.toString()
            Result.failure(Exception("استجابة غير معروفة: ${body.take(100)}"))
        } catch (e: Exception) {
            loginDebug = "Exception: ${e.message}"
            Result.failure(Exception("لا يمكن الوصول للراوتر: ${e.message}"))
        }
    }

    private suspend fun activateSession(api: ZteRouterApi, debug: StringBuilder) {
        val cmds = listOf("Language,cr_version,wa_inner_version", "wifi_onoff", "current_language", "SSID1", "wifi_coverage")
        for (cmd in cmds) {
            try {
                val r = api.getGenericCmd(cmd = cmd)
                debug.appendLine("  activate[$cmd]: ${r.code()} ${r.body()?.string()?.take(80)}")
                readCookiesFromResponse(r, debug)
            } catch (e: Exception) { debug.appendLine("  activate[$cmd] error: ${e.message}") }
        }
    }

    private fun readCookiesFromResponse(response: Response<*>, debug: StringBuilder) {
        val setCookies = response.headers().values("Set-Cookie")
        for (cookieHeader in setCookies) {
            val parts = cookieHeader.split(";")[0].split("=", limit = 2)
            if (parts.size == 2) RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
        }
        debug.appendLine("Total stored cookies: ${RetrofitClient.getCookiesString()}")
    }

    suspend fun getConnectedDevices(): Result<List<Device>> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val debug = StringBuilder()
            val routerIp = storage.getRouterIp()

            debug.appendLine("=== DEVICE SCAN ===")
            debug.appendLine("Router: $routerIp")

            val commands = listOf(
                "station_list", "wifi_station_list", "wifi_client_list",
                "dhcp_list", "client_list", "lan_station_list",
                "active_user_list", "connected_devices", "wlan_station_list",
                "multi_stations_list", "user_list", "station_list_5g",
                "wds_station_list", "wifiAttachCount", "wifiAttachList",
                "wifiAttachDevices", "attached_devices", "attached_devices_list",
                "dhcp_lease_list", "arp_table", "lan_host_list",
                "host_list", "connected_user_list", "wifi_client_count",
                "station_count", "client_count"
            )

            for (cmdName in commands) {
                try {
                    val response = api.getGenericCmd(cmd = cmdName)
                    val rawBody = if (response.isSuccessful) response.body()?.string() ?: "" else "HTTP ${response.code()}"
                    debug.appendLine("\n[$cmdName] -> ${response.code()}: ${rawBody.take(150)}")
                    if (response.isSuccessful && hasRealData(rawBody, cmdName)) {
                        lastRawResponse = rawBody
                        lastWorkingCommand = cmdName
                        val devices = tryAllParsingMethods(rawBody)
                        if (devices.isNotEmpty()) {
                            debug.appendLine("  FOUND ${devices.size} devices!")
                            allCommandsDebug = debug.toString()
                            return@withContext Result.success(devices)
                        }
                    }
                } catch (e: Exception) { debug.appendLine("[$cmdName] -> ERROR: ${e.message}") }
            }

            debug.appendLine("\n=== TRY GOFORM POST METHODS ===")
            val goformIds = listOf(
                "GET_STATION_LIST_CONTENT", "GET_CONNECTED_DEVICES",
                "REFRESH_STATION_LIST", "GET_CLIENT_LIST",
                "GET_DHCP_CLIENT_LIST", "GET_MULTI_STATION_LIST",
                "GET_WIFI_STATION_LIST", "REFRESH_DATA",
                "FRESH_STATION_INFO", "GET_ATTACHED_DEVICES"
            )
            for (goformId in goformIds) {
                try {
                    val response = api.postGoformId(goformId = goformId)
                    val rawBody = if (response.isSuccessful) response.body()?.string() ?: "" else "HTTP ${response.code()}"
                    debug.appendLine("\n[POST $goformId] -> ${response.code()}: ${rawBody.take(150)}")
                    if (response.isSuccessful && hasRealData(rawBody, goformId)) {
                        lastRawResponse = rawBody
                        lastWorkingCommand = "POST $goformId"
                        val devices = tryAllParsingMethods(rawBody)
                        if (devices.isNotEmpty()) {
                            allCommandsDebug = debug.toString()
                            return@withContext Result.success(devices)
                        }
                    }
                } catch (e: Exception) { debug.appendLine("[POST $goformId] -> ERROR: ${e.message}") }
            }

            debug.appendLine("\n=== TRY HTML + REQUIREJS ===")
            val htmlDevices = scrapeHtmlAndRequireJs(api, routerIp, debug)
            if (htmlDevices.isNotEmpty()) {
                allCommandsDebug = debug.toString()
                return@withContext Result.success(htmlDevices)
            }

            debug.appendLine("\n=== TRY COMMON ZTE PAGES ===")
            val commonPages = listOf(
                "wifi_settings.html", "station_list.html", "device_management.html",
                "dhcp.html", "connected_devices.html", "client_list.html",
                "lan_settings.html", "wlan_station.html", "mobile_connection.html",
                "status.html", "network.html", "wifi.html", "settings.html",
                "advance.html", "admin.html", "sys_dev_info.html"
            )
            for (page in commonPages) {
                try {
                    val html = fetchUrl("http://$routerIp/$page")
                    if (html.length > 500) {
                        debug.appendLine("[$page] -> ${html.length} chars")
                        val devices = extractDevicesFromHtml(html)
                        if (devices.isNotEmpty()) {
                            debug.appendLine("  Found ${devices.size} devices in $page!")
                            lastRawResponse = html.take(500)
                            lastWorkingCommand = "Page $page"
                            allCommandsDebug = debug.toString()
                            return@withContext Result.success(devices)
                        }
                    }
                } catch (_: Exception) {}
            }

            debug.appendLine("\n=== TRY RE-LOGIN ===")
            reloginAndRetry(api, debug)

            allCommandsDebug = debug.toString()
            if (lastRawResponse.isNotBlank()) {
                Result.failure(Exception("لم يتم العثور على أجهزة\nالأمر: $lastWorkingCommand\n${lastRawResponse.take(200)}"))
            } else {
                Result.failure(Exception("لا توجد استجابة من الراوتر"))
            }
        } catch (e: Exception) { Result.failure(Exception("فشل: ${e.message}")) }
    }

    private suspend fun scrapeHtmlAndRequireJs(api: ZteRouterApi, routerIp: String, debug: StringBuilder): List<Device> {
        val allJsCommands = mutableListOf<String>()

        try {
            val mainPageResponse = api.getMainPage()
            if (!mainPageResponse.isSuccessful) return emptyList()
            val html = mainPageResponse.body()?.string() ?: ""
            debug.appendLine("[main page] -> ${html.length} chars")
            if (html.length < 100) return emptyList()

            val devices = extractDevicesFromHtml(html)
            if (devices.isNotEmpty()) { lastRawResponse = html.take(500); lastWorkingCommand = "HTML main page"; return devices }

            val inlineCmds = extractJsCommands(html)
            debug.appendLine("  Inline cmds: ${inlineCmds.joinToString(", ")}")
            allJsCommands.addAll(inlineCmds)

            val scriptSrcs = extractJsFileUrls(html)
            debug.appendLine("  Script src files: ${scriptSrcs.size}")

            // ═══ ابحث عن RequireJS data-main ═══
            val dataMainPattern = Regex("""data-main\s*=\s*["']([^"']+)["']""")
            val dataMain = dataMainPattern.find(html)?.groupValues?.get(1)
            debug.appendLine("  RequireJS data-main: $dataMain")

            val jsFilesToFetch = mutableListOf<String>()
            jsFilesToFetch.addAll(scriptSrcs)
            if (dataMain != null) jsFilesToFetch.add(dataMain)

            debug.appendLine("  Total JS files to fetch: ${jsFilesToFetch.size}")

            for (jsUrl in jsFilesToFetch) {
                try {
                    val fullUrl = if (jsUrl.startsWith("http")) jsUrl
                    else "http://$routerIp${if (jsUrl.startsWith("/")) "" else "/"}$jsUrl"

                    debug.appendLine("\n  [Fetching: $fullUrl]")
                    val jsContent = fetchUrl(fullUrl)
                    if (jsContent.length < 30) { debug.appendLine("    Too short, skipping"); continue }
                    debug.appendLine("    Content: ${jsContent.length} chars")

                    // ═══ اطبع أول 500 حرف من كل ملف JS ═══
                    debug.appendLine("    Preview: ${jsContent.take(500)}")

                    val jsDevices = extractDevicesFromHtml(jsContent)
                    if (jsDevices.isNotEmpty()) { lastRawResponse = jsContent.take(500); lastWorkingCommand = "JS file $jsUrl"; return jsDevices }

                    val jsCmds = extractJsCommands(jsContent)
                    if (jsCmds.isNotEmpty()) debug.appendLine("    Cmds: ${jsCmds.joinToString(", ")}")
                    allJsCommands.addAll(jsCmds)

                    // ═══ ابحث عن مسارات RequireJS في js/main ═══
                    if (jsUrl.contains("main")) {
                        val requirePaths = extractRequireJsPaths(jsContent)
                        debug.appendLine("    RequireJS paths: ${requirePaths.joinToString(", ")}")
                        for (path in requirePaths) {
                            try {
                                val pathUrl = if (path.startsWith("http")) path
                                else "http://$routerIp/${path.removeSuffix(".js")}.js"
                                debug.appendLine("    [Fetching module: $pathUrl]")
                                val moduleContent = fetchUrl(pathUrl)
                                if (moduleContent.length > 30) {
                                    debug.appendLine("      Module content: ${moduleContent.length} chars")
                                    debug.appendLine("      Preview: ${moduleContent.take(500)}")
                                    val moduleCmds = extractJsCommands(moduleContent)
                                    if (moduleCmds.isNotEmpty()) debug.appendLine("      Cmds: ${moduleCmds.joinToString(", ")}")
                                    allJsCommands.addAll(moduleCmds)
                                    val moduleDevices = extractDevicesFromHtml(moduleContent)
                                    if (moduleDevices.isNotEmpty()) { lastRawResponse = moduleContent.take(500); lastWorkingCommand = "Module $path"; return moduleDevices }
                                }
                            } catch (e: Exception) { debug.appendLine("      Error: ${e.message}") }
                        }
                    }
                } catch (e: Exception) { debug.appendLine("    Error: ${e.message}") }
            }
        } catch (e: Exception) { debug.appendLine("HTML scraping error: ${e.message}") }

        val uniqueCmds = allJsCommands.distinct().filter { it.isNotBlank() && it.length > 3 }
        debug.appendLine("\n=== TRY ${uniqueCmds.size} DISCOVERED COMMANDS ===")

        for (cmd in uniqueCmds) {
            try {
                val r = api.getGenericCmd(cmd = cmd)
                val b = r.body()?.string() ?: ""
                debug.appendLine("  [$cmd] -> ${r.code()}: ${b.take(150)}")
                if (hasRealData(b, cmd)) {
                    lastRawResponse = b; lastWorkingCommand = "JS $cmd"
                    val d = tryAllParsingMethods(b)
                    if (d.isNotEmpty()) { debug.appendLine("  FOUND ${d.size} devices!"); return d }
                }
            } catch (e: Exception) { debug.appendLine("  [$cmd] -> ERROR: ${e.message}") }
        }
        return emptyList()
    }

    private fun extractRequireJsPaths(jsContent: String): List<String> {
        val paths = mutableListOf<String>()
        val patterns = listOf(
            Regex("""paths\s*:\s*\{([^}]+)\}"""),
            Regex("""require\s*\(\s*$$([^$$]+)\]"""),
            Regex("""define\s*\(\s*$$([^$$]+)\]"""),
            Regex("""require\.config\s*\(\s*\{([^}]+)\}""")
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(jsContent)) {
                val block = match.groupValues[1]
                val pathPattern = Regex("""["']([^"']+)["']""")
                for (pathMatch in pathPattern.findAll(block)) {
                    val path = pathMatch.groupValues[1]
                    if (path.isNotBlank() && path !in paths) paths.add(path)
                }
            }
        }
        return paths
    }

    private fun extractJsFileUrls(html: String): List<String> {
        val urls = mutableListOf<String>()
        val scriptPattern = Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        for (match in scriptPattern.findAll(html)) {
            val url = match.groupValues[1]
            if (url.isNotBlank() && url !in urls) urls.add(url)
        }
        return urls
    }

    private fun fetchUrl(url: String): String {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            response.body?.string() ?: ""
        } catch (_: Exception) { "" }
    }

    private fun extractJsCommands(html: String): List<String> {
        val commands = mutableListOf<String>()
        val patterns = listOf(
            Regex("""cmd[=:]\s*["']([^"']+)["']"""),
            Regex("""goform_get_cmd_process\?cmd=([^"&\s]+)"""),
            Regex("""getAjaxData\([^,]*["']([^"']+)["']"""),
            Regex("""\.get\([^)]*cmd[=:]\s*["']([^"']+)["']"""),
            Regex("""\?cmd=([a-zA-Z0-9_,]+)"""),
            Regex("""goformId[=:]\s*["']([^"']+)["']"""),
            Regex("""["']([a-zA-Z_]*(?:station|client|device|user|dhcp|wifi|wlan|connect|attach|host|mac_filter)[a-zA-Z_]*)["']""")
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(html)) {
                val cmd = match.groupValues[1]
                if (cmd.isNotBlank() && cmd !in commands && cmd.length > 3) commands.add(cmd)
            }
        }
        return commands
    }

    private fun extractDevicesFromHtml(html: String): List<Device> {
        val macPattern = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
        val ipPattern = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
        val macs = macPattern.findAll(html).map { it.value.uppercase() }.distinct().toList()
        if (macs.isEmpty()) return emptyList()
        val routerMacs = setOf("FF:FF:FF:FF:FF:FF", "00:00:00:00:00:00")
        val clientMacs = macs.filter { it !in routerMacs }
        val ips = ipPattern.findAll(html).map { it.value }.toList()
        return clientMacs.mapIndexed { i, mac -> Device(mac = mac, ip = ips.getOrNull(i) ?: "", hostname = "جهاز ${i + 1}", connectionType = "WiFi") }
    }

    private suspend fun reloginAndRetry(api: ZteRouterApi, debug: StringBuilder) {
        try {
            val encoded = Base64.encodeToString(storage.getPassword().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val resp = api.login(password = encoded)
            debug.appendLine("Re-login: ${resp.code()} ${resp.body()?.string()?.take(100)}")
            readCookiesFromResponse(resp, debug)
            activateSession(api, debug)
            val r = api.getGenericCmd(cmd = "station_list")
            val b = r.body()?.string() ?: ""
            debug.appendLine("After re-login [station_list]: ${r.code()}: ${b.take(150)}")
            if (hasRealData(b, "station_list")) { lastRawResponse = b; lastWorkingCommand = "station_list (re-login)" }
        } catch (e: Exception) { debug.appendLine("Re-login error: ${e.message}") }
    }

    private fun hasRealData(body: String, cmdName: String): Boolean {
        if (body.isBlank() || body == "{}" || body == "[]") return false
        if (body == "{\"\":\"$cmdName\"}" || body == "{\"$cmdName\":\"\"}") return false
        if (body.contains("\"\":\"$cmdName\"") && body.length < 50) return false
        if (Regex("[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}").containsMatchIn(body)) return true
        if (Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(body)) return true
        if (body.length > 80) return true
        return false
    }

    private fun tryAllParsingMethods(rawBody: String): List<Device> {
        if (rawBody.isBlank()) return emptyList()
        try {
            val root = JsonParser.parseString(rawBody)
            if (root.isJsonObject) { for (key in root.asJsonObject.keySet()) { val el = root.asJsonObject.get(key) ?: continue; val d = tryParseDeviceArray(el); if (d.isNotEmpty()) return d } }
            if (root.isJsonArray) { val d = tryParseDeviceArray(root); if (d.isNotEmpty()) return d }
        } catch (_: Exception) {}
        return tryParseWithRegex(rawBody)
    }

    private fun tryParseDeviceArray(element: JsonElement): List<Device> {
        return when {
            element.isJsonArray -> parseJsonArray(element.asJsonArray)
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                val str = element.asString; if (str.isBlank()) return emptyList()
                try { val parsed = JsonParser.parseString(str); if (parsed.isJsonArray) parseJsonArray(parsed.asJsonArray) else emptyList() } catch (_: Exception) { emptyList() }
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject; val list = obj.get("station_list") ?: obj.get("devices") ?: obj.get("clients")
                if (list != null) tryParseDeviceArray(list) else { val d = parseSingleDevice(obj); if (d != null) listOf(d) else emptyList() }
            }
            else -> emptyList()
        }
    }

    private fun parseJsonArray(array: JsonArray): List<Device> {
        val devices = mutableListOf<Device>()
        for (item in array) { if (item.isJsonObject) { val d = parseSingleDevice(item.asJsonObject); if (d != null) devices.add(d) } }
        return devices
    }

    private fun parseSingleDevice(obj: JsonObject): Device? {
        val mac = findMac(obj); if (mac.isBlank()) return null
        return Device(mac = mac.uppercase(), ip = findField(obj, "ip", "ip_addr", "ipAddress", "address"), hostname = findField(obj, "hostname", "name", "host_name", "device_name", "client_name").ifBlank { "جهاز غير معروف" }, connectionType = findField(obj, "conn_type", "wlan_type", "type", "connection").ifBlank { "WiFi" })
    }

    private fun findMac(obj: JsonObject): String {
        val macFields = listOf("mac", "mac_addr", "mac_address", "MacAddress", "MAC", "hwaddr")
        for (f in macFields) { val v = getFieldStr(obj, f); if (v.isNotBlank() && isValidMac(v)) return v }
        for (key in obj.keySet()) { val v = getFieldStr(obj, key); if (isValidMac(v)) return v }
        return ""
    }

    private fun findField(obj: JsonObject, vararg names: String): String { for (n in names) { val v = getFieldStr(obj, n); if (v.isNotBlank()) return v }; return "" }

    private fun getFieldStr(obj: JsonObject, field: String): String {
        return try { val el = obj.get(field) ?: return ""; when { el.isJsonNull -> ""; el.isJsonPrimitive -> el.asString; else -> el.toString() } } catch (_: Exception) { "" }
    }

    private fun isValidMac(v: String): Boolean {
        return Regex("[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}").matches(v.trim())
    }

    private fun tryParseWithRegex(raw: String): List<Device> {
        val macPattern = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
        val ipPattern = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
        val macs = macPattern.findAll(raw).map { it.value.uppercase() }.distinct().toList()
        if (macs.isEmpty()) return emptyList()
        val ips = ipPattern.findAll(raw).map { it.value }.toList()
        return macs.mapIndexed { i, mac -> Device(mac = mac, ip = ips.getOrNull(i) ?: "", hostname = "جهاز ${i + 1}", connectionType = "Unknown") }
    }

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> = withContext(Dispatchers.IO) {
        try { val newList = (currentBlockedList + mac.uppercase()).joinToString(";"); val response = RetrofitClient.getApi().setMacFilter(macList = newList); if (response.isSuccessful) Result.success("تم حظر الجهاز") else if (response.code() == 401) { autoRelogin(); val r = RetrofitClient.getApi().setMacFilter(macList = newList); if (r.isSuccessful) Result.success("تم الحظر") else Result.failure(Exception("فشل")) } else Result.failure(Exception("فشل: ${response.code()}")) } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> = withContext(Dispatchers.IO) {
        try { val newList = currentBlockedList.filter { it.uppercase() != mac.uppercase() }.joinToString(";"); val response = if (newList.isEmpty()) RetrofitClient.getApi().disableMacFilter() else RetrofitClient.getApi().setMacFilter(macList = newList); if (response.isSuccessful) Result.success("تم إلغاء الحظر") else if (response.code() == 401) { autoRelogin(); val ra = RetrofitClient.getApi(); val r = if (newList.isEmpty()) ra.disableMacFilter() else ra.setMacFilter(macList = newList); if (r.isSuccessful) Result.success("تم إلغاء الحظر") else Result.failure(Exception("فشل")) } else Result.failure(Exception("فشل: ${response.code()}")) } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getBlockedMacs(): Result<List<String>> = withContext(Dispatchers.IO) {
        try { val response = RetrofitClient.getApi().getMacFilterList(); if (response.isSuccessful) { val body = response.body()?.string() ?: ""; Result.success(Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}").findAll(body).map { it.value.uppercase() }.toList()) } else Result.success(emptyList()) } catch (_: Exception) { Result.success(emptyList()) }
    }

    suspend fun logout() = withContext(Dispatchers.IO) { try { RetrofitClient.getApi().logout() } catch (_: Exception) {}; storage.setLoggedIn(false); RetrofitClient.reset() }

    private suspend fun autoRelogin() {
        try { val encoded = Base64.encodeToString(storage.getPassword().toByteArray(Charsets.UTF_8), Base64.NO_WRAP); RetrofitClient.setRouterAddress(storage.getRouterIp()); val response = RetrofitClient.getApi().login(password = encoded); val setCookies = response.headers().values("Set-Cookie"); for (cookieHeader in setCookies) { val parts = cookieHeader.split(";")[0].split("=", limit = 2); if (parts.size == 2) RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim()) } } catch (_: Exception) {}
    }
}
