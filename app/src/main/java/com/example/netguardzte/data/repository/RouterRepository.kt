package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.api.ZteRouterApi
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest

class RouterRepository(private val storage: SecureStorage) {

    var lastRawResponse: String = ""
        private set
    var loginDebug: String = ""
        private set
    var allCommandsDebug: String = ""
        private set

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun isSuccess(body: String): Boolean =
        body.contains("\"result\":\"success\"") || body.contains("\"result\":0") || body.contains("\"result\":\"0\"")
    private fun extractField(json: String, field: String): String =
        Regex(""""$field"\s*:\s*"([^"]*?)"""").find(json)?.groupValues?.getOrNull(1) ?: ""

    private fun httpGet(url: String): String = try {
        val req = Request.Builder().url(url).build()
        RetrofitClient.getHttpClient().newCall(req).execute().body?.string() ?: ""
    } catch (_: Exception) { "" }

    private fun httpPost(url: String, formBody: FormBody): String = try {
        val req = Request.Builder().url(url).post(formBody).build()
        RetrofitClient.getHttpClient().newCall(req).execute().body?.string() ?: ""
    } catch (_: Exception) { "" }

    private fun httpPostRaw(url: String, body: okhttp3.RequestBody): String = try {
        val req = Request.Builder().url(url).post(body).build()
        RetrofitClient.getHttpClient().newCall(req).execute().body?.string() ?: ""
    } catch (_: Exception) { "" }

    private fun computeAd(base: String, debug: StringBuilder): String {
        try {
            val wa = extractField(httpGet("$base/goform/goform_get_cmd_process?cmd=wa_inner_version"), "wa_inner_version")
            val cr = extractField(httpGet("$base/goform/goform_get_cmd_process?cmd=cr_version"), "cr_version")
            val rd = extractField(httpGet("$base/goform/goform_get_cmd_process?cmd=RD"), "RD")
            debug.appendLine("wa=$wa cr=$cr RD=${rd.take(16)}...")
            if (wa.isBlank() || cr.isBlank() || rd.isBlank()) return ""
            return md5(md5(wa + cr) + rd)
        } catch (e: Exception) { return "" }
    }

    // ═══════════════════════════════════════════
    // تشخيص شامل — يُحل المشكلة
    // ═══════════════════════════════════════════

    suspend fun diagnosePost(): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val base = "http://${storage.getRouterIp()}"
                val client = RetrofitClient.getHttpClient()

                debug.appendLine("=== COMPREHENSIVE DIAGNOSTIC ===")

                // ═══ 1. LOGIN ═══
                debug.appendLine("\n--- 1. LOGIN ---")
                val loginResult = login(storage.getRouterIp(), storage.getUsername(), storage.getPassword())
                debug.appendLine("Login: ${loginResult.isSuccess}")
                debug.appendLine("loginDebug has: ${loginDebug.length} chars")

                // ═══ 2. هل POST يعمل؟ SET_WEB_LANGUAGE ═══
                debug.appendLine("\n--- 2. TEST POST: SET_WEB_LANGUAGE ---")
                try {
                    val body = FormBody.Builder()
                        .add("isTest", "false")
                        .add("goformId", "SET_WEB_LANGUAGE")
                        .add("Language", "en")
                        .build()
                    val resp = httpPost("$base/goform/goform_set_cmd_process", body)
                    debug.appendLine("SET_WEB_LANGUAGE: $resp")
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                // ═══ 3. هل POST بدون AUTH يعمل؟ ═══
                debug.appendLine("\n--- 3. TEST POST: SET_WEB_LANGUAGE (no login context) ---")
                try {
                    val freshReq = Request.Builder()
                        .url("$base/goform/goform_set_cmd_process")
                        .post(FormBody.Builder()
                            .add("isTest", "false")
                            .add("goformId", "SET_WEB_LANGUAGE")
                            .add("Language", "ar")
                            .build())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .build()
                    val resp = client.newCall(freshReq).execute().body?.string() ?: ""
                    debug.appendLine("Fresh POST: $resp")
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                // ═══ 4. احسب AD ═══
                debug.appendLine("\n--- 4. AD ---")
                val ad = computeAd(base, debug)
                debug.appendLine("AD=$ad")

                // ═══ 5. جرب كل goformId ممكن للحظر ═══
                debug.appendLine("\n--- 5. goformId VARIATIONS ---")
                val mac = "7C:A4:49:E0:81:66"
                val macUpper = mac.uppercase()

                data class BlockTest(val id: String, val params: List<Pair<String, String>>, val desc: String)

                val tests = mutableListOf<BlockTest>()

                // setDeviceAccessControlList variations
                tests.add(BlockTest("setDeviceAccessControlList",
                    listOf("AclMode" to "2", "BlackMacList" to "$mac;", "WhiteMacList" to "", "WhiteNameList" to "", "BlackNameList" to "", "AD" to ad), "standard+AD"))
                tests.add(BlockTest("setDeviceAccessControlList",
                    listOf("AclMode" to "2", "BlackMacList" to "$mac;", "WhiteMacList" to "", "WhiteNameList" to "", "BlackNameList" to ""), "standard no AD"))
                tests.add(BlockTest("setDeviceAccessControlList",
                    listOf("AclMode" to "2", "BlackMacList" to mac), "minimal"))
                tests.add(BlockTest("setDeviceAccessControlList",
                    listOf("AclMode" to "2", "macList" to "$mac;", "AD" to ad), "macList"))
                tests.add(BlockTest("setDeviceAccessControlList",
                    listOf("AclMode" to "2", "black_list" to "$mac;", "AD" to ad), "black_list"))

                // Alternative goformIds
                val altIds = listOf(
                    "SET_DEVICE_ACCESS_CONTROL_LIST", "setAccessControlList", "SET_ACCESS_CONTROL_LIST",
                    "setAccessControl", "SET_ACCESS_CONTROL", "setMacFilter", "SET_MAC_FILTER",
                    "setWifiMacFilter", "SET_WIFI_MAC_FILTER", "setMacFilterMode", "SET_MAC_FILTER_MODE",
                    "setParentalControl", "SET_PARENTAL_CONTROL", "setLanACL", "SET_LAN_ACL",
                    "setFirewallMacFilter", "SET_FIREWALL_MAC_FILTER", "setDeviceMacFilter",
                    "setWifiAccessControl", "SET_WIFI_ACCESS_CONTROL", "setDeviceAccessCtrlList",
                    "MAC_FILTER", "ACCESS_CONTROL", "PARENTAL_CONTROL", "DEVICE_ACL"
                )

                for (id in altIds) {
                    tests.add(BlockTest(id,
                        listOf("AclMode" to "2", "BlackMacList" to "$mac;", "WhiteMacList" to "", "WhiteNameList" to "", "BlackNameList" to "", "AD" to ad), ""))
                    tests.add(BlockTest(id,
                        listOf("mac_filter_enabled" to "1", "mac_filter_mode" to "2", "mac_filter_list" to "$mac;", "AD" to ad), "alt params"))
                }

                var found = false
                for (test in tests) {
                    try {
                        val builder = FormBody.Builder().add("isTest", "false").add("goformId", test.id)
                        for ((k, v) in test.params) builder.add(k, v)
                        val resp = httpPost("$base/goform/goform_set_cmd_process", builder.build())
                        if (!resp.contains("\"result\":\"failure\"") && !resp.contains("\"result\":\"3\"")) {
                            debug.appendLine("  ✅✅✅ ${test.id} (${test.desc}): $resp")
                            found = true
                        }
                    } catch (_: Exception) {}
                }
                if (!found) debug.appendLine("  ❌ ALL goformIds returned failure or result:3")

                // ═══ 6. جرب JSON body ═══
                debug.appendLine("\n--- 6. JSON BODY ---")
                try {
                    val json = """{"isTest":false,"goformId":"setDeviceAccessControlList","AclMode":"2","BlackMacList":"$mac;","WhiteMacList":"","WhiteNameList":"","BlackNameList":"","AD":"$ad"}"""
                    val resp = httpPostRaw("$base/goform/goform_set_cmd_process",
                        json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    debug.appendLine("JSON: $resp")
                } catch (e: Exception) { debug.appendLine("JSON error: ${e.message}") }

                // ═══ 7. اقرأ config.js ═══
                debug.appendLine("\n--- 7. CONFIG.JS ---")
                for (path in listOf("m/js/config/config.js", "m/config.js", "config.js", "js/config.js", "m/js/config.js")) {
                    try {
                        val body = httpGet("$base/$path")
                        if (body.isNotBlank() && !body.contains("Document Error") && body.length > 10) {
                            debug.appendLine("✅ $path (${body.length} bytes):")
                            // ابحث عن ACCESSIBLE_ID_SUPPORT
                            if (body.contains("ACCESSIBLE_ID_SUPPORT")) {
                                val match = Regex("""ACCESSIBLE_ID_SUPPORT\s*[:=]\s*(\w+)""").find(body)
                                debug.appendLine("  ACCESSIBLE_ID_SUPPORT = ${match?.groupValues?.getOrNull(1)}")
                            }
                            // اطبع أول 2000 حرف
                            debug.appendLine(body.take(2000))
                        }
                    } catch (_: Exception) {}
                }

                // ═══ 8. اقرأ service.js وابحث عن ACL ═══
                debug.appendLine("\n--- 8. SERVICE.JS ACL SEARCH ---")
                try {
                    val serviceBody = httpGet("$base/m/js/service.js")
                    debug.appendLine("service.js: ${serviceBody.length} bytes")

                    if (serviceBody.length > 100) {
                        // ابحث عن كلمات ACL
                        for (keyword in listOf("AccessControl", "BlackMacList", "AclMode", "mac_filter", "MacFilter", "black_list", "parental", "ParentalControl")) {
                            val indices = mutableListOf<Int>()
                            var idx = serviceBody.indexOf(keyword, 0, ignoreCase = true)
                            while (idx != -1 && indices.size < 5) {
                                indices.add(idx)
                                idx = serviceBody.indexOf(keyword, idx + 1, ignoreCase = true)
                            }
                            if (indices.isNotEmpty()) {
                                debug.appendLine("\n  Found '$keyword' at ${indices.size} locations:")
                                for (pos in indices.take(3)) {
                                    val start = maxOf(0, pos - 200)
                                    val end = minOf(serviceBody.length, pos + keyword.length + 300)
                                    debug.appendLine("  ...${serviceBody.substring(start, end)}...")
                                }
                            }
                        }
                    }
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                // ═══ 9. اقرأ index.html بالكامل ═══
                debug.appendLine("\n--- 9. INDEX.HTML FULL ---")
                try {
                    val html = httpGet("$base/m/index.html")
                    debug.appendLine(html.take(5000))
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                // ═══ 10. اقرأ كل الملفات JS المذكورة في HTML ═══
                debug.appendLine("\n--- 10. ALL JS MODULES ---")
                try {
                    val html = httpGet("$base/m/index.html")
                    // RequireJS config
                    val requireConfig = Regex("""data-main="([^"]*?)"""").find(html)
                    debug.appendLine("RequireJS main: ${requireConfig?.groupValues?.getOrNull(1)}")

                    // Search for module paths in RequireJS config
                    val pathsBlock = Regex("""paths\s*:\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL).find(html)
                    if (pathsBlock != null) {
                        debug.appendLine("RequireJS paths:")
                        debug.appendLine(pathsBlock.groupValues[1].take(1000))
                    }
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                debug.appendLine("\n=== END DIAGNOSTIC ===")
                Result.success(debug.toString())
            }
        } catch (e: Exception) { Result.failure(Exception("Error: ${e.message}")) }
    }

    // ═══════════════════════════════════════════
    // LOGIN
    // ═══════════════════════════════════════════

    suspend fun login(routerIp: String, username: String, password: String): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                debug.appendLine("=== LOGIN v13 ===")

                RetrofitClient.reset()
                RetrofitClient.setRouterAddress(routerIp)
                val base = "http://$routerIp"

                httpGet("$base/m/index.html")

                val ldBody = httpGet("$base/goform/goform_get_cmd_process?cmd=LD")
                val ld = extractField(ldBody, "LD")

                val ad = computeAd(base, debug)

                val sha256Pass = sha256(password)
                val encodings = mutableListOf<Pair<String, String>>()
                if (ld.isNotBlank()) {
                    encodings.add("SHA256(pass+LD)" to sha256(password + ld))
                    encodings.add("SHA256(SHA256+LD)" to sha256(sha256Pass + ld))
                }
                encodings.add("Base64" to Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))

                for ((label, encodedPass) in encodings) {
                    for (tryNum in 1..3) {
                        val formBody = FormBody.Builder()
                            .add("isTest", "false")
                            .add("goformId", "LOGIN")
                            .add("password", encodedPass)
                            .add("AD", ad)
                            .add("isForce", "1").build()
                        val body = httpPost("$base/goform/goform_set_cmd_process", formBody)
                        debug.appendLine("$label Try#$tryNum: $body")
                        when {
                            body.contains("\"result\":\"0\"") || body.contains("\"result\":0") ||
                            body.contains("\"result\":\"1\"") || body.contains("\"result\":1") -> {
                                storage.saveCredentials(routerIp, username, password)
                                storage.setLoggedIn(true)
                                loginDebug = debug.toString()
                                return@withContext Result.success("تم الاتصال")
                            }
                            body.contains("\"result\":\"3\"") || body.contains("\"result\":3") -> Thread.sleep(500)
                        }
                    }
                }
                loginDebug = debug.toString()
                Result.failure(Exception("فشل الدخول"))
            }
        } catch (e: Exception) { Result.failure(Exception("خطأ: ${e.message}")) }
    }

    private suspend fun ensureLoggedIn(api: ZteRouterApi, debug: StringBuilder): Boolean {
        val result = login(storage.getRouterIp(), storage.getUsername(), storage.getPassword())
        debug.appendLine("Login: ${result.isSuccess}")
        return result.isSuccess
    }

    // ═══════════════════════════════════════════
    // حظر (مبسط)
    // ═══════════════════════════════════════════

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()
                val base = "http://${storage.getRouterIp()}"

                debug.appendLine("=== BLOCK $macUpper ===")
                if (!ensureLoggedIn(RetrofitClient.getApi(), debug)) {
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext Result.failure(Exception("غير مسجل"))
                }

                val aclBody = httpGet("$base/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList")
                debug.appendLine("ACL: $aclBody")

                val existingMacs = Regex(""""BlackMacList"\s*:\s*"([^"]*?)"""")
                    .find(aclBody)?.groupValues?.getOrNull(1)
                    ?.split(";")?.map { it.trim().uppercase() }
                    ?.filter { it.isNotEmpty() && it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
                    ?.toMutableList() ?: mutableListOf()

                if (macUpper !in existingMacs) existingMacs.add(macUpper)
                val newBlackList = existingMacs.joinToString(";") + ";"
                val ad = computeAd(base, debug)

                debug.appendLine("\nNew list: $newBlackList AD=$ad")

                val formBody = FormBody.Builder()
                    .add("isTest", "false")
                    .add("goformId", "setDeviceAccessControlList")
                    .add("AclMode", "2")
                    .add("BlackMacList", newBlackList)
                    .add("WhiteMacList", "")
                    .add("WhiteNameList", "")
                    .add("BlackNameList", "")
                    .add("AD", ad).build()

                val resp = httpPost("$base/goform/goform_set_cmd_process", formBody)
                debug.appendLine("Response: $resp")

                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (isSuccess(resp)) Result.success("تم حظر $macUpper")
                else Result.failure(Exception("فشل: $resp"))
            }
        } catch (e: Exception) { Result.failure(Exception("خطأ: ${e.message}")) }
    }

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()
                val base = "http://${storage.getRouterIp()}"

                debug.appendLine("=== UNBLOCK $macUpper ===")
                ensureLoggedIn(RetrofitClient.getApi(), debug)

                val aclBody = httpGet("$base/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList")
                val existingMacs = Regex(""""BlackMacList"\s*:\s*"([^"]*?)"""")
                    .find(aclBody)?.groupValues?.getOrNull(1)
                    ?.split(";")?.map { it.trim().uppercase() }
                    ?.filter { it.isNotEmpty() && it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
                    ?.toMutableList() ?: mutableListOf()

                existingMacs.remove(macUpper)
                val newAclMode = if (existingMacs.isEmpty()) "0" else "2"
                val newBlackList = if (existingMacs.isEmpty()) "" else existingMacs.joinToString(";") + ";"
                val ad = computeAd(base, debug)

                val formBody = FormBody.Builder()
                    .add("isTest", "false")
                    .add("goformId", "setDeviceAccessControlList")
                    .add("AclMode", newAclMode)
                    .add("BlackMacList", newBlackList)
                    .add("WhiteMacList", "")
                    .add("WhiteNameList", "")
                    .add("BlackNameList", "")
                    .add("AD", ad).build()

                val body = httpPost("$base/goform/goform_set_cmd_process", formBody)
                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (isSuccess(body)) Result.success("تم إلغاء حظر $macUpper")
                else Result.failure(Exception("فشل: $body"))
            }
        } catch (e: Exception) { Result.failure(Exception("خطأ: ${e.message}")) }
    }

    suspend fun getConnectedDevices(): Result<List<Device>> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val routerIp = try { storage.getRouterIp() } catch (_: Exception) { "192.168.0.1" }
                val subnet = routerIp.substringBeforeLast(".")
                debug.appendLine("=== DEVICE SCAN ===")
                try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh flush dev wlan0")).waitFor() } catch (_: Exception) {}
                for (i in 1..50) { try { val s = java.net.Socket(); s.connect(java.net.InetSocketAddress("$subnet.$i", 80), 30); s.close() } catch (_: Exception) {} }
                var devices = readArpFromAllSources(debug)
                if (devices.isEmpty()) devices = readFromRouterApi(debug)
                allCommandsDebug = debug.toString()
                if (devices.isNotEmpty()) Result.success(devices) else Result.failure(Exception("لم يتم العثور على أجهزة"))
            }
        } catch (e: Exception) { Result.failure(Exception("فشل: ${e.message}")) }
    }

    suspend fun getBlockedMacs(): Result<List<String>> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                ensureLoggedIn(RetrofitClient.getApi(), debug)
                val base = "http://${storage.getRouterIp()}"
                val aclBody = httpGet("$base/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList")
                val macs = Regex(""""BlackMacList"\s*:\s*"([^"]*?)"""")
                    .find(aclBody)?.groupValues?.getOrNull(1)
                    ?.split(";")?.map { it.trim().uppercase() }
                    ?.filter { it.isNotEmpty() && it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
                    ?: emptyList()
                allCommandsDebug = debug.toString()
                Result.success(macs)
            }
        } catch (e: Exception) { Result.failure(Exception("فشل: ${e.message}")) }
    }

    suspend fun testRouterConnection(): Result<String> = diagnosePost()

    suspend fun logout() {
        try { withContext(Dispatchers.IO) { try { RetrofitClient.getApi().logout() } catch (_: Exception) {} } } catch (_: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.reset()
    }

    // ═══════════════════════════════════════════
    // ARP أدوات
    // ═══════════════════════════════════════════

    private suspend fun readArpFromAllSources(debug: StringBuilder): List<Device> {
        var d = readIpNeigh(debug); if (d.isNotEmpty()) return d
        d = readArpFromFile(); if (d.isNotEmpty()) return d
        return readArpFromCommand("cat /proc/net/arp")
    }

    private fun readIpNeigh(debug: StringBuilder): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh"))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) { if (!line.uppercase().contains("FAILED") && !line.uppercase().contains("INCOMPLETE")) parseArpLine(line)?.let { devices.add(it) }; line = r.readLine() }
            p.waitFor()
        } catch (_: Exception) {}
        return devices
    }

    private fun readArpFromFile(): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val f = java.io.File("/proc/net/arp"); if (!f.exists() || !f.canRead()) return emptyList()
            val r = BufferedReader(InputStreamReader(f.inputStream())); r.readLine()
            var line = r.readLine()
            while (line != null) { val parts = line.trim().split("\\s+".toRegex()); if (parts.size >= 4 && parts[3].uppercase() != "00:00:00:00:00:00" && parts[2] != "0x0") devices.add(makeDevice(parts[0], parts[3].uppercase())); line = r.readLine() }
            r.close()
        } catch (_: Exception) {}
        return devices
    }

    private fun readArpFromCommand(command: String): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine(); while (line != null) { parseArpLine(line)?.let { devices.add(it) }; line = r.readLine() }
            p.waitFor(); p.destroy()
        } catch (_: Exception) {}
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
            val base = "http://${storage.getRouterIp()}"
            for (cmd in listOf("station_list", "wifi_station_list", "dhcp_list")) {
                try { val body = httpGet("$base/goform/goform_get_cmd_process?cmd=$cmd"); debug.appendLine("  [$cmd]: ${body.take(100)}"); if (body.length > 30) { val d = parseDevices(body); if (d.isNotEmpty()) return d } } catch (e: Exception) { debug.appendLine("  [$cmd] error: ${e.message}") }
            }
        } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }
        return emptyList()
    }

    private fun makeDevice(ip: String, mac: String): Device {
        val rIp = try { storage.getRouterIp() } catch (_: Exception) { "" }
        return Device(mac = mac, ip = ip, hostname = nameFor(ip, mac), connectionType = if (ip == rIp) "Router" else "WiFi")
    }

    private fun nameFor(ip: String, mac: String): String {
        val v = when { mac.startsWith("A4:83") || mac.startsWith("F0:18") -> "Apple"; mac.startsWith("CC:96") || mac.startsWith("58:48") -> "Huawei"; mac.startsWith("70:F9") || mac.startsWith("94:B8") -> "Samsung"; mac.startsWith("6C:B0") || mac.startsWith("54:FA") -> "Xiaomi"; mac.startsWith("00:21") -> "ZTE"; else -> "" }
        val s = ip.substringAfterLast(".")
        return when { v.isNotBlank() -> "$v ($s)"; s == "1" -> "الراوتر"; else -> "جهاز .$s" }
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
