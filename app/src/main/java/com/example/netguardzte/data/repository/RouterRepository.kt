package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.api.ZteRouterApi
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
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            RetrofitClient.reset()
            RetrofitClient.setRouterAddress(routerIp)
            val api = RetrofitClient.getApi()
            val debug = StringBuilder()

            debug.appendLine("=== STEP 1: Load pages ===")
            try {
                val mainPage = api.getMainPage()
                debug.appendLine("Main page: ${mainPage.code()}")
                readCookies(mainPage, debug)
                val indexPage = api.getIndexPage()
                debug.appendLine("Index page: ${indexPage.code()}")
                readCookies(indexPage, debug)
            } catch (e: Exception) {
                debug.appendLine("Page error: ${e.message}")
            }

            val encodedPassword = Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )

            debug.appendLine("\n=== STEP 2: Login ===")
            val response = api.login(password = encodedPassword)
            val body = response.body()?.string() ?: ""
            debug.appendLine("Code: ${response.code()}")
            debug.appendLine("Body: ${body.take(200)}")
            readCookies(response, debug)
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
                loginDebug = debug.toString()
                return@withContext Result.success("تم الاتصال بالراوتر")
            }

            if (response.isSuccessful) {
                debug.appendLine("HTTP OK, saving credentials")
                storage.saveCredentials(routerIp, username, password)
                storage.setLoggedIn(true)
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

    suspend fun getConnectedDevices(): Result<List<Device>> = withContext(Dispatchers.IO) {
        try {
            val debug = StringBuilder()
            val routerIp = storage.getRouterIp()

            debug.appendLine("=== DEVICE SCAN ===")
            debug.appendLine("Router: $routerIp")

            debug.appendLine("\n--- ARP Table ---")
            val arpDevices = readArpTable()
            debug.appendLine("Found: ${arpDevices.size} devices")
            for (d in arpDevices) {
                debug.appendLine("  ${d.ip} | ${d.mac} | ${d.hostname}")
            }

            if (arpDevices.isNotEmpty()) {
                lastRawResponse = "ARP: ${arpDevices.size} devices"
                lastWorkingCommand = "ARP table"

                val routerNames = tryGetDeviceNamesFromRouter()
                val enhanced = arpDevices.map { device ->
                    val routerName = routerNames[device.mac.uppercase()]
                    if (routerName != null && device.hostname.startsWith("جهاز")) {
                        device.copy(hostname = routerName)
                    } else device
                }

                allCommandsDebug = debug.toString()
                return@withContext Result.success(enhanced)
            }

            debug.appendLine("\n--- Ping Scan ---")
            val scanned = scanNetwork(routerIp, debug)
            debug.appendLine("Found: ${scanned.size} devices")

            if (scanned.isNotEmpty()) {
                lastRawResponse = "Scan: ${scanned.size} devices"
                lastWorkingCommand = "Ping scan"
                allCommandsDebug = debug.toString()
                return@withContext Result.success(scanned)
            }

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
                                allCommandsDebug = debug.toString()
                                return@withContext Result.success(devices)
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                debug.appendLine("API error: ${e.message}")
            }

            allCommandsDebug = debug.toString()
            Result.failure(Exception("لم يتم العثور على أجهزة"))
        } catch (e: Exception) {
            Result.failure(Exception("فشل: ${e.message}"))
        }
    }

    suspend fun testRouterConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val api = RetrofitClient.getApi()
            val debug = StringBuilder()

            debug.appendLine("=== TEST ROUTER API ===")
            debug.appendLine("Cookies: ${RetrofitClient.getCookiesString()}")

            debug.appendLine("\n--- Test 1: Session ---")
            try {
                val r = api.getGenericCmd(cmd = "Language")
                val b = r.body()?.string() ?: ""
                debug.appendLine("Language: $b")
                if (b.contains("\"Language\"")) debug.appendLine("✅ Session active!")
                else debug.appendLine("❌ Session not active")
            } catch (e: Exception) {
                debug.appendLine("❌ Error: ${e.message}")
            }

            debug.appendLine("\n--- Test 2: MAC filter ---")
            try {
                val r = api.getMacFilterList()
                val b = r.body()?.string() ?: ""
                debug.appendLine("MAC filter: $b")
                if (r.isSuccessful) debug.appendLine("✅ Works! Code: ${r.code()}")
                else debug.appendLine("❌ Failed. Code: ${r.code()}")
            } catch (e: Exception) {
                debug.appendLine("❌ Error: ${e.message}")
            }

            debug.appendLine("\n--- Test 3: Various commands ---")
            val testCmds = listOf(
                "wifi_onoff", "SSID1", "mac_filter_enabled",
                "mac_filter_mode", "mac_filter_list", "wifi_mac_filter",
                "wan_connect_status", "wifi_wpa_psk", "imei"
            )
            for (cmd in testCmds) {
                try {
                    val r = api.getGenericCmd(cmd = cmd)
                    val b = r.body()?.string() ?: ""
                    val ok = b.length > 20 && !b.contains("\"\":\"$cmd\"")
                    debug.appendLine("  [$cmd] -> ${if (ok) "✅" else "❌"} ${b.take(100)}")
                } catch (e: Exception) {
                    debug.appendLine("  [$cmd] -> ❌ ${e.message}")
                }
            }

            debug.appendLine("\n--- Test 4: POST goformIds ---")
            val goformTests = listOf(
                "SET_WIFI_MAC_FILTER", "GET_WIFI_MAC_FILTER",
                "SET_WIFI_SSID1_SETTINGS", "GET_WIFI_SSID1_SETTINGS"
            )
            for (id in goformTests) {
                try {
                    val r = api.postGoformId(goformId = id)
                    val b = r.body()?.string() ?: ""
                    debug.appendLine("  [$id] -> ${b.take(100)}")
                } catch (e: Exception) {
                    debug.appendLine("  [$id] -> ❌ ${e.message}")
                }
            }

            debug.appendLine("\n--- Test 5: Router pages ---")
            val pages = listOf("", "index.html", "wifi.html", "status.html")
            for (page in pages) {
                try {
                    val url = "http://${storage.getRouterIp()}/$page"
                    val content = fetchUrl(url)
                    debug.appendLine("  [/$page] -> ${content.length} chars")
                    if (content.length > 500) {
                        val cmds = extractCmdsFromHtml(content)
                        debug.appendLine("    Cmds: ${cmds.joinToString(", ")}")
                    }
                } catch (e: Exception) {
                    debug.appendLine("  [/$page] -> ❌ ${e.message}")
                }
            }

            Result.success(debug.toString())
        } catch (e: Exception) {
            Result.failure(Exception("Test failed: ${e.message}"))
        }
    }

    private fun tryGetDeviceNamesFromRouter(): Map<String, String> {
        val names = mutableMapOf<String, String>()
        try {
            val api = RetrofitClient.getApi()
            for (cmd in listOf("station_list", "dhcp_list", "client_list")) {
                try {
                    val response = api.getGenericCmd(cmd = cmd)
                    val body = response.body()?.string() ?: ""
                    if (body.length < 30) continue
                    val root = com.google.gson.JsonParser.parseString(body)
                    if (root.isJsonObject) {
                        for (key in root.asJsonObject.keySet()) {
                            val el = root.asJsonObject.get(key) ?: continue
                            val items = when {
                                el.isJsonArray -> el.asJsonArray
                                el.isJsonPrimitive && el.asJsonPrimitive.isString -> {
                                    try {
                                        val p = com.google.gson.JsonParser.parseString(el.asString)
                                        if (p.isJsonArray) p.asJsonArray else null
                                    } catch (_: Exception) { null }
                                }
                                else -> null
                            }
                            items?.forEach { item ->
                                if (item.isJsonObject) {
                                    val obj = item.asJsonObject
                                    val mac = getFieldStr(obj, "mac", "mac_addr", "mac_address")
                                    val name = getFieldStr(obj, "hostname", "name", "host_name")
                                    if (mac.isNotBlank() && name.isNotBlank()) {
                                        names[mac.uppercase()] = name
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return names
    }

    private fun readArpTable(): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val reader = BufferedReader(FileReader("/proc/net/arp"))
            reader.readLine()
            var line = reader.readLine()
            while (line != null) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 6) {
                    val ip = parts[0]
                    val mac = parts[3].uppercase()
                    val flags = parts[2]
                    if (mac != "00:00:00:00:00:00" && flags != "0x0") {
                        val hostname = try {
                            val h = InetAddress.getByName(ip).canonicalHostName
                            if (h != ip) h else ""
                        } catch (_: Exception) { "" }

                        devices.add(
                            Device(
                                mac = mac,
                                ip = ip,
                                hostname = hostname.ifBlank { guessDeviceName(ip, mac) },
                                connectionType = if (ip == storage.getRouterIp()) "Router" else "WiFi"
                            )
                        )
                    }
                }
                line = reader.readLine()
            }
            reader.close()
        } catch (_: Exception) {}
        return devices
    }

    private fun scanNetwork(routerIp: String, debug: StringBuilder): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val subnet = routerIp.substringBeforeLast(".")
            for (i in 1..254) {
                val ip = "$subnet.$i"
                try {
                    val addr = InetAddress.getByName(ip)
                    if (addr.isReachable(100) || ip == routerIp) {
                        val mac = getMacFromArp(ip)
                        devices.add(
                            Device(
                                mac = mac.ifBlank { "??:??:??:??:??:??" },
                                ip = ip,
                                hostname = guessDeviceName(ip, mac),
                                connectionType = if (ip == routerIp) "Router" else "WiFi"
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
            for (arp in readArpTable()) {
                if (devices.none { it.ip == arp.ip }) devices.add(arp)
            }
        } catch (e: Exception) {
            debug.appendLine("Scan error: ${e.message}")
        }
        return devices
    }

    private fun getMacFromArp(ip: String): String {
        try {
            val reader = BufferedReader(FileReader("/proc/net/arp"))
            reader.readLine()
            var line = reader.readLine()
            while (line != null) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 6 && parts[0] == ip) {
                    val mac = parts[3].uppercase()
                    reader.close()
                    if (mac != "00:00:00:00:00:00") return mac
                }
                line = reader.readLine()
            }
            reader.close()
        } catch (_: Exception) {}
        return ""
    }

    private fun guessDeviceName(ip: String, mac: String): String {
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
            mac.startsWith("00:21") || mac.startsWith("58:7F") -> "ZTE"
            mac.startsWith("00:E0:4C") -> "Realtek"
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
                val api = RetrofitClient.getApi()
                val newList = (currentBlockedList + mac.uppercase()).joinToString(";")
                val response = api.setMacFilter(macList = newList)
                if (response.isSuccessful) {
                    Result.success("تم حظر الجهاز")
                } else if (response.code() == 401) {
                    autoRelogin()
                    val r = RetrofitClient.getApi().setMacFilter(macList = newList)
                    if (r.isSuccessful) Result.success("تم الحظر") else Result.failure(Exception("فشل"))
                } else {
                    Result.failure(Exception("فشل: ${response.code()}"))
                }
            } catch (e: Exception) { Result.failure(e) }
        }

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
                    val ra = RetrofitClient.getApi()
                    val r = if (newList.isEmpty()) ra.disableMacFilter() else ra.setMacFilter(macList = newList)
                    if (r.isSuccessful) Result.success("تم إلغاء الحظر") else Result.failure(Exception("فشل"))
                } else {
                    Result.failure(Exception("فشل: ${response.code()}"))
                }
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun getBlockedMacs(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().getMacFilterList()
            if (response.isSuccessful) {
                val body = response.body()?.string() ?: ""
                Result.success(
                    Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
                        .findAll(body).map { it.value.uppercase() }.toList()
                )
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
            val encoded = Base64.encodeToString(
                storage.getPassword().toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            RetrofitClient.setRouterAddress(storage.getRouterIp())
            val r = RetrofitClient.getApi().login(password = encoded)
            for (c in r.headers().values("Set-Cookie")) {
                val p = c.split(";")[0].split("=", limit = 2)
                if (p.size == 2) RetrofitClient.setSessionCookie(p[0].trim(), p[1].trim())
            }
        } catch (_: Exception) {}
    }

    private fun fetchUrl(url: String): String {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).execute().body?.string() ?: ""
        } catch (_: Exception) { "" }
    }

    private fun extractCmdsFromHtml(html: String): List<String> {
        val cmds = mutableListOf<String>()
        val patterns = listOf(
            Regex("""cmd[=:]\s*["']([^"']+)["']"""),
            Regex("""goformId[=:]\s*["']([^"']+)["']"""),
            Regex("""\?cmd=([^"&\s]+)""")
        )
        for (p in patterns) {
            for (m in p.findAll(html)) {
                val c = m.groupValues[1]
                if (c.isNotBlank() && c !in cmds && c.length > 3) cmds.add(c)
            }
        }
        return cmds
    }

    private fun getFieldStr(obj: com.google.gson.JsonObject, vararg fields: String): String {
        for (f in fields) {
            try {
                val el = obj.get(f)
                if (el != null && !el.isJsonNull && el.isJsonPrimitive) return el.asString
            } catch (_: Exception) {}
        }
        return ""
    }

    private fun readCookies(response: Response<*>, debug: StringBuilder) {
        for (c in response.headers().values("Set-Cookie")) {
            val parts = c.split(";")[0].split("=", limit = 2)
            if (parts.size == 2) RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
        }
        debug.appendLine("Cookies: ${RetrofitClient.getCookiesString()}")
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
