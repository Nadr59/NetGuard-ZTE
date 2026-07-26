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

            debug.appendLine("=== STEP 1: Load main page ===")
            try {
                val mainPage = api.getMainPage()
                debug.appendLine("Main page code: ${mainPage.code()}")
                readCookiesFromResponse(mainPage, debug)
                val indexPage = api.getIndexPage()
                debug.appendLine("Index page code: ${indexPage.code()}")
                readCookiesFromResponse(indexPage, debug)
            } catch (e: Exception) {
                debug.appendLine("Page load error: ${e.message}")
            }

            debug.appendLine("\n=== STEP 2: System info ===")
            try {
                val sysInfo = api.getSystemInfo()
                val sysBody = sysInfo.body()?.string() ?: ""
                debug.appendLine("System info: ${sysBody.take(200)}")
                readCookiesFromResponse(sysInfo, debug)
            } catch (e: Exception) {
                debug.appendLine("System info error: ${e.message}")
            }

            val encodedPassword = Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )

            debug.appendLine("\n=== STEP 3: Login ===")
            debug.appendLine("Password (base64): ${encodedPassword.take(20)}...")

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
                body.contains("\"result\":\"1\"") || body.contains("\"result\":1")
            ) {
                debug.appendLine("LOGIN SUCCESS!")
                storage.saveCredentials(routerIp, username, password)
                storage.setLoggedIn(true)
                debug.appendLine("\n=== STEP 4: Activate session ===")
                activateSession(api, debug)
                loginDebug = debug.toString()
                return@withContext Result.success("تم الاتصال بالراوتر")
            }

            if (response.isSuccessful) {
                debug.appendLine("Unknown result but HTTP OK")
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
        val cmds = listOf(
            "Language,cr_version,wa_inner_version",
            "wifi_onoff", "current_language",
            "data_volume_limit_switch", "SSID1", "wifi_coverage"
        )
        for (cmd in cmds) {
            try {
                val r = api.getGenericCmd(cmd = cmd)
                val b = r.body()?.string() ?: ""
                debug.appendLine("  activate[$cmd]: ${r.code()} ${b.take(80)}")
                readCookiesFromResponse(r, debug)
            } catch (e: Exception) {
                debug.appendLine("  activate[$cmd] error: ${e.message}")
            }
        }
    }

    private fun readCookiesFromResponse(response: Response<*>, debug: StringBuilder) {
        val setCookies = response.headers().values("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            debug.appendLine("Set-Cookie headers: ${setCookies.size}")
            for (cookieHeader in setCookies) {
                val parts = cookieHeader.split(";")[0].split("=", limit = 2)
                if (parts.size == 2) {
                    RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
                }
            }
        }
        debug.appendLine("Total stored cookies: ${RetrofitClient.getCookiesString()}")
    }

    suspend fun getConnectedDevices(): Result<List<Device>> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val debug = StringBuilder()

            cookieDebug = "Cookies: ${RetrofitClient.getCookiesString()}"
            debug.appendLine("=== DEVICE SCAN ===")

            val commands = listOf(
                "station_list", "wifi_station_list", "wifi_client_list",
                "dhcp_list", "client_list", "lan_station_list",
                "active_user_list", "connected_devices", "wlan_station_list",
                "multi_stations_list", "user_list", "station_list_5g",
                "wds_station_list", "Language"
            )

            for (cmdName in commands) {
                try {
                    val response = api.getGenericCmd(cmd = cmdName)
                    val rawBody = if (response.isSuccessful) {
                        response.body()?.string() ?: ""
                    } else {
                        "HTTP ${response.code()}"
                    }
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
                } catch (e: Exception) {
                    debug.appendLine("[$cmdName] -> ERROR: ${e.message}")
                }
            }

            debug.appendLine("\n=== TRY POST METHOD ===")
            try {
                val postResponse = api.postGetStationList()
                val postBody = if (postResponse.isSuccessful) {
                    postResponse.body()?.string() ?: ""
                } else {
                    "HTTP ${postResponse.code()}"
                }
                debug.appendLine("[POST] -> ${postResponse.code()}: ${postBody.take(150)}")

                if (postResponse.isSuccessful && hasRealData(postBody, "station_list")) {
                    lastRawResponse = postBody
                    lastWorkingCommand = "POST station_list"
                    val devices = tryAllParsingMethods(postBody)
                    if (devices.isNotEmpty()) {
                        allCommandsDebug = debug.toString()
                        return@withContext Result.success(devices)
                    }
                }
            } catch (e: Exception) {
                debug.appendLine("[POST] -> ERROR: ${e.message}")
            }

            debug.appendLine("\n=== TRY HTML + JS FILES ===")
            val htmlDevices = scrapeHtmlAndJsFiles(api, debug)
            if (htmlDevices.isNotEmpty()) {
                allCommandsDebug = debug.toString()
                return@withContext Result.success(htmlDevices)
            }

            debug.appendLine("\n=== TRY RE-LOGIN ===")
            reloginAndRetry(api, debug)

            allCommandsDebug = debug.toString()

            if (lastRawResponse.isNotBlank()) {
                Result.failure(Exception("لم يتم العثور على أجهزة\nالأمر: $lastWorkingCommand\n${lastRawResponse.take(200)}"))
            } else {
                Result.failure(Exception("لا توجد استجابة من الراوتر"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("فشل: ${e.message}"))
        }
    }

    private suspend fun scrapeHtmlAndJsFiles(api: ZteRouterApi, debug: StringBuilder): List<Device> {
        val allJsCommands = mutableListOf<String>()

        val pages: List<Pair<String, suspend () -> Response<ResponseBody>>> = listOf(
            "main page" to suspend { api.getMainPage() },
            "status page" to suspend { api.getStatusPage() },
            "wifi page" to suspend { api.getWifiPage() }
        )

        for ((name, pageCall) in pages) {
            try {
                val response = pageCall()
                if (response.isSuccessful) {
                    val html = response.body()?.string() ?: ""
                    debug.appendLine("[$name] -> ${response.code()}: ${html.length} chars")

                    if (html.length < 100) continue

                    val devices = extractDevicesFromHtml(html)
                    if (devices.isNotEmpty()) {
                        lastRawResponse = html.take(500)
                        lastWorkingCommand = "HTML $name"
                        return devices
                    }

                    val inlineCmds = extractJsCommands(html)
                    debug.appendLine("  Inline JS cmds: ${inlineCmds.joinToString(", ")}")
                    allJsCommands.addAll(inlineCmds)

                    val jsFiles = extractJsFileUrls(html)
                    debug.appendLine("  External JS files: ${jsFiles.size}")
                    for (jsUrl in jsFiles) {
                        try {
                            val fullUrl = if (jsUrl.startsWith("http")) jsUrl
                            else "http://${storage.getRouterIp()}${if (jsUrl.startsWith("/")) "" else "/"}$jsUrl"
                            val jsResponse = api.getGenericCmd(cmd = "__fetch_js__")
                            debug.appendLine("  [Fetching JS: $fullUrl]")

                            val jsClient = RetrofitClient.getApi()
                            val jsPage = jsClient.getMainPage()
                            val jsContent = fetchUrl(fullUrl)
                            if (jsContent.isNotBlank()) {
                                debug.appendLine("    JS content: ${jsContent.length} chars")
                                val jsCmds = extractJsCommands(jsContent)
                                debug.appendLine("    JS cmds found: ${jsCmds.joinToString(", ")}")
                                allJsCommands.addAll(jsCmds)

                                val jsDevices = extractDevicesFromHtml(jsContent)
                                if (jsDevices.isNotEmpty()) {
                                    lastRawResponse = jsContent.take(500)
                                    lastWorkingCommand = "JS file $jsUrl"
                                    return jsDevices
                                }
                            }
                        } catch (e: Exception) {
                            debug.appendLine("  JS fetch error [$jsUrl]: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                debug.appendLine("[$name] -> ERROR: ${e.message}")
            }
        }

        val uniqueCmds = allJsCommands.distinct().filter { it.isNotBlank() }
        debug.appendLine("\n=== TRY ${uniqueCmds.size} JS COMMANDS ===")

        for (cmd in uniqueCmds) {
            try {
                val r = api.getGenericCmd(cmd = cmd)
                val b = r.body()?.string() ?: ""
                debug.appendLine("  [$cmd] -> ${r.code()}: ${b.take(150)}")
                if (hasRealData(b, cmd)) {
                    lastRawResponse = b
                    lastWorkingCommand = "JS $cmd"
                    val d = tryAllParsingMethods(b)
                    if (d.isNotEmpty()) {
                        debug.appendLine("  FOUND ${d.size} devices!")
                        return d
                    }
                }
            } catch (e: Exception) {
                debug.appendLine("  [$cmd] -> ERROR: ${e.message}")
            }
        }

        return emptyList()
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
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractJsCommands(html: String): List<String> {
        val commands = mutableListOf<String>()
        val patterns = listOf(
            Regex("""cmd[=:]\s*["']([^"']+)["']"""),
            Regex("""goform_get_cmd_process\?cmd=([^"&\s]+)"""),
            Regex("""getAjaxData\([^,]*["']([^"']+)["']"""),
            Regex("""\.get\([^)]*cmd[=:]\s*["']([^"']+)["']"""),
            Regex("""url[=:]\s*["'][^"']*cmd=([^"&\s]+)["']"""),
            Regex("""["']([a-zA-Z_]+(?:_list|_info|_status|_count|_data|_setting|_config))["']"""),
            Regex("""\?cmd=([a-zA-Z0-9_,]+)"""),
            Regex("""CMD_TYPE\s*[=:]\s*["']([^"']+)["']"""),
            Regex("""var\s+\w+\s*=\s*["']([a-zA-Z_]+(?:station|client|device|user|dhcp|wifi|wlan|connected)[a-zA-Z_]*)["']""")
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(html)) {
                val cmd = match.groupValues[1]
                if (cmd.isNotBlank() && cmd !in commands && cmd.length > 3) {
                    commands.add(cmd)
                }
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
        return clientMacs.mapIndexed { i, mac ->
            Device(mac = mac, ip = ips.getOrNull(i) ?: "", hostname = "جهاز ${i + 1}", connectionType = "WiFi")
        }
    }

    private suspend fun reloginAndRetry(api: ZteRouterApi, debug: StringBuilder) {
        try {
            val encoded = Base64.encodeToString(storage.getPassword().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val loginResponse = api.login(password = encoded)
            val loginBody = loginResponse.body()?.string() ?: ""
            debug.appendLine("Re-login: ${loginResponse.code()} ${loginBody.take(100)}")
            readCookiesFromResponse(loginResponse, debug)
            activateSession(api, debug)
            val r = api.getGenericCmd(cmd = "station_list")
            val b = r.body()?.string() ?: ""
            debug.appendLine("After re-login [station_list]: ${r.code()}: ${b.take(150)}")
            if (hasRealData(b, "station_list")) {
                lastRawResponse = b
                lastWorkingCommand = "station_list (after re-login)"
            }
        } catch (e: Exception) {
            debug.appendLine("Re-login error: ${e.message}")
        }
    }

    private fun hasRealData(body: String, cmdName: String): Boolean {
        if (body.isBlank()) return false
        if (body == "{}") return false
        if (body == "[]") return false
        if (body == "{\"\":\"$cmdName\"}") return false
        if (body == "{\"$cmdName\":\"\"}") return false
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
                    if (parsed.isJsonArray) parseJsonArray(parsed.asJsonArray) else emptyList()
                } catch (_: Exception) { emptyList() }
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val list = obj.get("station_list") ?: obj.get("devices") ?: obj.get("clients")
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
            hostname = findField(obj, "hostname", "name", "host_name", "device_name", "client_name").ifBlank { "جهاز غير معروف" },
            connectionType = findField(obj, "conn_type", "wlan_type", "type", "connection").ifBlank { "WiFi" }
        )
    }

    private fun findMac(obj: JsonObject): String {
        val macFields = listOf("mac", "mac_addr", "mac_address", "MacAddress", "MAC", "hwaddr", "hw_addr")
        for (f in macFields) {
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
            when { el.isJsonNull -> ""; el.isJsonPrimitive -> el.asString; else -> el.toString() }
        } catch (_: Exception) { "" }
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
        return macs.mapIndexed { i, mac ->
            Device(mac = mac, ip = ips.getOrNull(i) ?: "", hostname = "جهاز ${i + 1}", connectionType = "Unknown")
        }
    }

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val newList = (currentBlockedList + mac.uppercase()).joinToString(";")
            val response = api.setMacFilter(macList = newList)
            if (response.isSuccessful) Result.success("تم حظر الجهاز")
            else if (response.code() == 401) { autoRelogin(); val r = RetrofitClient.getApi().setMacFilter(macList = newList); if (r.isSuccessful) Result.success("تم الحظر") else Result.failure(Exception("فشل")) }
            else Result.failure(Exception("فشل: ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val newList = currentBlockedList.filter { it.uppercase() != mac.uppercase() }.joinToString(";")
            val response = if (newList.isEmpty()) api.disableMacFilter() else api.setMacFilter(macList = newList)
            if (response.isSuccessful) Result.success("تم إلغاء الحظر")
            else if (response.code() == 401) { autoRelogin(); val ra = RetrofitClient.getApi(); val r = if (newList.isEmpty()) ra.disableMacFilter() else ra.setMacFilter(macList = newList); if (r.isSuccessful) Result.success("تم إلغاء الحظر") else Result.failure(Exception("فشل")) }
            else Result.failure(Exception("فشل: ${response.code()}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getBlockedMacs(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().getMacFilterList()
            if (response.isSuccessful) {
                val body = response.body()?.string() ?: ""
                Result.success(Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}").findAll(body).map { it.value.uppercase() }.toList())
            } else Result.success(emptyList())
        } catch (_: Exception) { Result.success(emptyList()) }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try { RetrofitClient.getApi().logout() } catch (_: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.reset()
    }

    private suspend fun autoRelogin() {
        try {
            val encoded = Base64.encodeToString(storage.getPassword().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            RetrofitClient.setRouterAddress(storage.getRouterIp())
            val response = RetrofitClient.getApi().login(password = encoded)
            val setCookies = response.headers().values("Set-Cookie")
            for (cookieHeader in setCookies) {
                val parts = cookieHeader.split(";")[0].split("=", limit = 2)
                if (parts.size == 2) RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
            }
        } catch (_: Exception) {}
    }
}
