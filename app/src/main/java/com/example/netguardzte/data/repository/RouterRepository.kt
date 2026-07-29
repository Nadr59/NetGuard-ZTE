package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.api.ZteRouterApi
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import retrofit2.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.security.MessageDigest

class RouterRepository(private val storage: SecureStorage) {

    var lastRawResponse: String = ""
        private set
    var loginDebug: String = ""
        private set
    var allCommandsDebug: String = ""
        private set

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun isSuccess(body: String): Boolean {
        return body.contains("\"result\":\"success\"") ||
                body.contains("\"result\":0") ||
                body.contains("\"result\":\"0\"")
    }

    // ═══ HTTP GET بـ OkHttp (وليس HttpURLConnection!) ═══
    private fun httpGet(url: String): String {
        return try {
            val req = Request.Builder().url(url)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, */*")
                .build()
            RetrofitClient.getHttpClient().newCall(req).execute().body?.string() ?: ""
        } catch (e: Exception) { "" }
    }

    // ═══ HTTP POST بـ OkHttp ═══
    private fun httpPost(url: String, formBody: FormBody): String {
        return try {
            val req = Request.Builder().url(url)
                .post(formBody)
                .header("Referer", "http://${storage.getRouterIp()}/m/index.html")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, */*")
                .build()
            RetrofitClient.getHttpClient().newCall(req).execute().body?.string() ?: ""
        } catch (e: Exception) { "" }
    }

    // ═══════════════════════════════════════════
    // جلب LD
    // ═══════════════════════════════════════════

    private fun fetchLd(base: String, debug: StringBuilder): String {
        // cmd=LD يُنشئ جلسة لكنه يعمل
        try {
            val body = httpGet("$base/goform/goform_get_cmd_process?cmd=LD")
            debug.appendLine("cmd=LD: $body")
            val ld = Regex(""""LD"\s*:\s*"([^"]*?)"""").find(body)?.groupValues?.getOrNull(1) ?: ""
            if (ld.isNotBlank()) return ld
        } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

        debug.appendLine("❌ Could not get LD")
        return ""
    }

    // ═══════════════════════════════════════════
    // حساب AD
    // من service.js: cookWithRequest = SHA256
    // AD = SHA256(SHA256(wa_inner + cr_version) + RD)
    // ═══════════════════════════════════════════

    private fun computeAd(base: String, debug: StringBuilder): String {
        try {
            var waInner = ""; var crVersion = ""; var rd = ""

            for (name in listOf("wa_inner_version", "cr_version", "RD")) {
                val body = httpGet("$base/goform/goform_get_cmd_process?cmd=$name")
                debug.appendLine("$name: $body")
                val value = Regex(""""$name"\s*:\s*"([^"]*?)"""").find(body)?.groupValues?.getOrNull(1) ?: ""
                when (name) {
                    "wa_inner_version" -> waInner = value
                    "cr_version" -> crVersion = value
                    "RD" -> rd = value
                }
            }

            if (waInner.isBlank() || crVersion.isBlank() || rd.isBlank()) {
                debug.appendLine("⚠️ Missing data for AD")
                return ""
            }

            val ad = sha256(sha256(waInner + crVersion) + rd)
            debug.appendLine("AD=$ad")
            return ad
        } catch (e: Exception) {
            debug.appendLine("AD error: ${e.message}")
            return ""
        }
    }

    // ═══════════════════════════════════════════
    // تسجيل الدخول
    // ═══════════════════════════════════════════

    suspend fun login(
        routerIp: String,
        username: String,
        password: String
    ): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                debug.appendLine("=== LOGIN v10 ===")
                debug.appendLine("Router: $routerIp")

                RetrofitClient.reset()
                RetrofitClient.setRouterAddress(routerIp)
                val base = "http://$routerIp"

                // ═══ 1. حمّل صفحة الدخول ═══
                debug.appendLine("\n--- Login page ---")
                try {
                    val html = httpGet("$base/m/index.html")
                    debug.appendLine("HTML: ${html.length}")
                } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }

                // ═══ 2. اجلب LD ═══
                debug.appendLine("\n--- LD ---")
                val ld = fetchLd(base, debug)
                debug.appendLine("LD: '$ld'")

                // ═══ 3. احسب AD ═══
                debug.appendLine("\n--- AD ---")
                val ad = computeAd(base, debug)

                // ═══ 4. شفر كلمة المرور ═══
                val encodings = mutableListOf<Pair<String, String>>()

                if (ld.isNotBlank()) {
                    encodings.add("SHA256(pass+LD)" to sha256(password + ld))
                    encodings.add("SHA256(SHA256+LD)" to sha256(sha256(password) + ld))
                }
                encodings.add("Base64" to Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                encodings.add("SHA256" to sha256(password))
                encodings.add("Plain" to password)

                debug.appendLine("\n--- Encodings ---")
                for ((l, v) in encodings) debug.appendLine("  $l: ${v.take(60)}")

                // ═══ 5. جرب كل طريقة (3 محاولات لكل) ═══
                for ((label, encodedPass) in encodings) {
                    debug.appendLine("\n=== $label (3 tries) ===")

                    for (tryNum in 1..3) {
                        try {
                            val formBody = FormBody.Builder()
                                .add("isTest", "false")
                                .add("goformId", "LOGIN")
                                .add("password", encodedPass)
                                .add("AD", ad)
                                .add("isForce", "1")
                                .build()

                            val body = httpPost("$base/goform/goform_set_cmd_process", formBody)
                            debug.appendLine("  Try#$tryNum: $body")

                            when {
                                body.contains("\"result\":\"0\"") || body.contains("\"result\":0") -> {
                                    debug.appendLine("  ✅ SUCCESS (result:0)!")
                                    storage.saveCredentials(routerIp, username, password)
                                    storage.setLoggedIn(true)
                                    loginDebug = debug.toString()
                                    return@withContext Result.success("تم الاتصال")
                                }
                                body.contains("\"result\":\"1\"") || body.contains("\"result\":1") -> {
                                    debug.appendLine("  ✅ ACCEPTED (result:1)")
                                    storage.saveCredentials(routerIp, username, password)
                                    storage.setLoggedIn(true)
                                    loginDebug = debug.toString()
                                    return@withContext Result.success("تم الاتصال")
                                }
                                body.contains("\"result\":\"3\"") || body.contains("\"result\":3") -> {
                                    debug.appendLine("  ❌ result:3")
                                    Thread.sleep(500)
                                }
                                else -> debug.appendLine("  ❓ $body")
                            }
                        } catch (e: Exception) { debug.appendLine("  Error: ${e.message}") }
                    }
                }

                debug.appendLine("\n=== ALL FAILED ===")
                loginDebug = debug.toString()
                Result.failure(Exception("فشل الدخول"))
            }
        } catch (e: Exception) { Result.failure(Exception("خطأ: ${e.message}")) }
    }

    // ═══════════════════════════════════════════
    // ensureLoggedIn
    // ═══════════════════════════════════════════

    private suspend fun ensureLoggedIn(api: ZteRouterApi, debug: StringBuilder): Boolean {
        try {
            debug.appendLine("\n--- ensureLoggedIn ---")
            val result = login(storage.getRouterIp(), storage.getUsername(), storage.getPassword())
            debug.appendLine("Login: ${result.isSuccess}")
            return result.isSuccess
        } catch (e: Exception) {
            debug.appendLine("Error: ${e.message}")
            return false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // حظر جهاز — يجرب مع AD وبدون AD
    // ═══════════════════════════════════════════════════════════════

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
                    return@withContext Result.failure(Exception("غير مسجل دخول"))
                }

                // اقرأ القائمة الحالية
                val aclBody = httpGet("$base/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList")
                debug.appendLine("ACL: $aclBody")

                val existingMacs = Regex(""""BlackMacList"\s*:\s*"([^"]*?)"""")
                    .find(aclBody)?.groupValues?.getOrNull(1)
                    ?.split(";")
                    ?.map { it.trim().uppercase() }
                    ?.filter { it.isNotEmpty() && it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
                    ?.toMutableList() ?: mutableListOf()

                if (macUpper !in existingMacs) existingMacs.add(macUpper)
                val newBlackList = existingMacs.joinToString(";") + ";"
                debug.appendLine("New list: $newBlackList")

                // احسب AD
                val ad = computeAd(base, debug)

                // ═══ جرب عدة طرق ═══
                data class BlockAttempt(val label: String, val includeAd: Boolean, val format: String)
                val attempts = mutableListOf<BlockAttempt>()

                attempts.add(BlockAttempt("With AD", true, "semicolon"))
                attempts.add(BlockAttempt("Without AD", false, "semicolon"))
                attempts.add(BlockAttempt("With AD, no semicolon", true, "nosemi"))
                attempts.add(BlockAttempt("With AD, single MAC", true, "single"))
                attempts.add(BlockAttempt("Lowercase MAC + AD", true, "lower"))

                for (attempt in attempts) {
                    debug.appendLine("\n=== ${attempt.label} ===")

                    val macList = when (attempt.format) {
                        "semicolon" -> newBlackList
                        "nosemi" -> existingMacs.joinToString(";")
                        "single" -> macUpper
                        "lower" -> mac.lowercase() + ";"
                        else -> newBlackList
                    }

                    try {
                        val formBuilder = FormBody.Builder()
                            .add("isTest", "false")
                            .add("goformId", "setDeviceAccessControlList")
                            .add("AclMode", "2")
                            .add("BlackMacList", macList)
                            .add("WhiteMacList", "")
                            .add("WhiteNameList", "")
                            .add("BlackNameList", "")

                        if (attempt.includeAd && ad.isNotBlank()) {
                            formBuilder.add("AD", ad)
                        }

                        val responseBody = httpPost("$base/goform/goform_set_cmd_process", formBuilder.build())
                        debug.appendLine("Response: $responseBody")

                        if (isSuccess(responseBody)) {
                            debug.appendLine("✅ BLOCK SUCCESS with ${attempt.label}!")
                            lastRawResponse = debug.toString()
                            allCommandsDebug = debug.toString()
                            return@withContext Result.success("تم حظر $macUpper")
                        }

                        debug.appendLine("❌ ${attempt.label} failed")
                    } catch (e: Exception) {
                        debug.appendLine("Error: ${e.message}")
                    }
                }

                debug.appendLine("\n=== ALL BLOCK METHODS FAILED ===")
                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()
                return@withContext Result.failure(Exception("فشل الحظر"))
            }
        } catch (e: Exception) { Result.failure(Exception("خطأ: ${e.message}")) }
    }

    // ═══════════════════════════════════════════════════════════════
    // إلغاء حظر
    // ═══════════════════════════════════════════════════════════════

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
                    ?.split(";")
                    ?.map { it.trim().uppercase() }
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
                    .add("AD", ad)
                    .build()

                val responseBody = httpPost("$base/goform/goform_set_cmd_process", formBody)

                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (isSuccess(responseBody)) Result.success("تم إلغاء حظر $macUpper")
                else Result.failure(Exception("فشل: $responseBody"))
            }
        } catch (e: Exception) { Result.failure(Exception("خطأ: ${e.message}")) }
    }

    // ═══════════════════════════════════════════
    // الأجهزة + المحظورين + اختبار + خروج
    // ═══════════════════════════════════════════

    suspend fun getConnectedDevices(): Result<List<Device>> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val routerIp = try { storage.getRouterIp() } catch (_: Exception) { "192.168.0.1" }
                val subnet = routerIp.substringBeforeLast(".")
                debug.appendLine("=== DEVICE SCAN ===")

                try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh flush dev wlan0")).waitFor() } catch (_: Exception) {}
                for (i in 1..50) {
                    try { val s = java.net.Socket(); s.connect(java.net.InetSocketAddress("$subnet.$i", 80), 30); s.close() } catch (_: Exception) {}
                }

                var devices = readArpFromAllSources(debug)
                if (devices.isEmpty()) devices = readFromRouterApi(debug)

                allCommandsDebug = debug.toString()
                if (devices.isNotEmpty()) Result.success(devices)
                else Result.failure(Exception("لم يتم العثور على أجهزة"))
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
                debug.appendLine("ACL: $aclBody")

                val macs = Regex(""""BlackMacList"\s*:\s*"([^"]*?)"""")
                    .find(aclBody)?.groupValues?.getOrNull(1)
                    ?.split(";")
                    ?.map { it.trim().uppercase() }
                    ?.filter { it.isNotEmpty() && it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
                    ?: emptyList()

                allCommandsDebug = debug.toString()
                Result.success(macs)
            }
        } catch (e: Exception) { Result.failure(Exception("فشل: ${e.message}")) }
    }

    suspend fun testRouterConnection(): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val base = "http://${storage.getRouterIp()}"
                debug.appendLine("=== TEST ===")
                debug.appendLine("Language: ${httpGet("$base/goform/goform_get_cmd_process?cmd=Language")}")
                debug.appendLine("\n=== LD ===")
                fetchLd(base, debug)
                debug.appendLine("\n=== AD ===")
                computeAd(base, debug)
                debug.appendLine("\n=== ACL ===")
                debug.appendLine("${httpGet("$base/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList")}")
                Result.success(debug.toString())
            }
        } catch (e: Exception) { Result.failure(Exception("Test: ${e.message}")) }
    }

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
            while (line != null) {
                if (!line.uppercase().contains("FAILED") && !line.uppercase().contains("INCOMPLETE"))
                    parseArpLine(line)?.let { devices.add(it) }
                line = r.readLine()
            }
            p.waitFor()
        } catch (_: Exception) {}
        return devices
    }

    private fun readArpFromFile(): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val f = java.io.File("/proc/net/arp")
            if (!f.exists() || !f.canRead()) return emptyList()
            val r = BufferedReader(InputStreamReader(f.inputStream()))
            r.readLine()
            var line = r.readLine()
            while (line != null) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4 && parts[3].uppercase() != "00:00:00:00:00:00" && parts[2] != "0x0")
                    devices.add(makeDevice(parts[0], parts[3].uppercase()))
                line = r.readLine()
            }
            r.close()
        } catch (_: Exception) {}
        return devices
    }

    private fun readArpFromCommand(command: String): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) { parseArpLine(line)?.let { devices.add(it) }; line = r.readLine() }
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
                try {
                    val body = httpGet("$base/goform/goform_get_cmd_process?cmd=$cmd")
                    debug.appendLine("  [$cmd]: ${body.take(100)}")
                    if (body.length > 30) { val d = parseDevices(body); if (d.isNotEmpty()) return d }
                } catch (e: Exception) { debug.appendLine("  [$cmd] error: ${e.message}") }
            }
        } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }
        return emptyList()
    }

    private fun makeDevice(ip: String, mac: String): Device {
        val rIp = try { storage.getRouterIp() } catch (_: Exception) { "" }
        return Device(mac = mac, ip = ip, hostname = nameFor(ip, mac), connectionType = if (ip == rIp) "Router" else "WiFi")
    }

    private fun nameFor(ip: String, mac: String): String {
        val v = when {
            mac.startsWith("A4:83") || mac.startsWith("F0:18") -> "Apple"
            mac.startsWith("CC:96") || mac.startsWith("58:48") -> "Huawei"
            mac.startsWith("70:F9") || mac.startsWith("94:B8") -> "Samsung"
            mac.startsWith("6C:B0") || mac.startsWith("54:FA") -> "Xiaomi"
            mac.startsWith("00:21") -> "ZTE"
            else -> ""
        }
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
