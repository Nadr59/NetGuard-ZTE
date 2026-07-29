package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.api.ZteRouterApi
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
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
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun isSuccess(body: String): Boolean =
        body.contains("\"result\":\"success\"") ||
                body.contains("\"result\":0") ||
                body.contains("\"result\":\"0\"")

    private fun extractField(json: String, field: String): String =
        Regex(""""$field"\s*:\s*"([^"]*?)"""").find(json)?.groupValues?.getOrNull(1) ?: ""

    // ═══ GET عبر OkHttp ═══
    private fun httpGet(url: String): String = try {
        val req = Request.Builder().url(url).build()
        RetrofitClient.getHttpClient().newCall(req).execute().body?.string() ?: ""
    } catch (_: Exception) { "" }

    // ═══ POST عبر OkHttp ═══
    private fun httpPost(url: String, formBody: FormBody): String = try {
        val req = Request.Builder().url(url).post(formBody).build()
        RetrofitClient.getHttpClient().newCall(req).execute().body?.string() ?: ""
    } catch (_: Exception) { "" }

    // ═══ GET مع طباعة الـ headers ═══
    private fun httpGetDebug(url: String, debug: StringBuilder): String {
        return try {
            val req = Request.Builder().url(url).build()
            val resp = RetrofitClient.getHttpClient().newCall(req).execute()
            val body = resp.body?.string() ?: ""

            // اطبع Set-Cookie headers
            for (header in resp.headers) {
                if (header.first.equals("Set-Cookie", ignoreCase = true)) {
                    debug.appendLine("  🍪 Set-Cookie: ${header.second}")
                }
            }

            // اطبع حالة الكوكيز
            val cookies = RetrofitClient.getCookiesString()
            if (cookies.isNotBlank()) {
                debug.appendLine("  Cookies: $cookies")
            }

            body
        } catch (e: Exception) {
            debug.appendLine("  Error: ${e.message}")
            ""
        }
    }

    // ═══════════════════════════════════════════
    // حساب AD
    // ═══════════════════════════════════════════

    private fun computeAd(base: String, debug: StringBuilder): String {
        try {
            val wa = extractField(httpGet("$base/goform/goform_get_cmd_process?cmd=wa_inner_version"), "wa_inner_version")
            val cr = extractField(httpGet("$base/goform/goform_get_cmd_process?cmd=cr_version"), "cr_version")
            val rd = extractField(httpGet("$base/goform/goform_get_cmd_process?cmd=RD"), "RD")
            debug.appendLine("wa=$wa cr=$cr RD=${rd.take(20)}...")

            if (wa.isBlank() || cr.isBlank() || rd.isBlank()) return ""

            // MD5 (من v8)
            val ad = md5(md5(wa + cr) + rd)
            debug.appendLine("AD=$ad")
            return ad
        } catch (e: Exception) { debug.appendLine("AD error: ${e.message}"); return "" }
    }

    // ═══════════════════════════════════════════
    // تسجيل الدخول
    // ═══════════════════════════════════════════

    suspend fun login(routerIp: String, username: String, password: String): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                debug.appendLine("=== LOGIN v12 ===")

                RetrofitClient.reset()
                RetrofitClient.setRouterAddress(routerIp)
                val base = "http://$routerIp"

                // ═══ 1. حمّل صفحة الدخول (بدون XHR!) ═══
                debug.appendLine("\n--- Load login page (no XHR) ---")
                val pageHtml = httpGetDebug("$base/m/index.html", debug)
                debug.appendLine("HTML: ${pageHtml.length} bytes")
                debug.appendLine("Cookies after page: '${RetrofitClient.getCookiesString()}'")

                // ═══ 2. جرّب nv=LD (قد يعمل الآن مع الكوكيز) ═══
                debug.appendLine("\n--- Try nv=LD ---")
                val nvLdBody = httpGetDebug("$base/goform/goform_set_cmd_process?nv=LD", debug)
                debug.appendLine("nv=LD response: $nvLdBody")
                var ld = extractField(nvLdBody, "LD")

                // ═══ 3. إذا nv=LD فشل، جرب cmd=LD ═══
                if (ld.isBlank()) {
                    debug.appendLine("\n--- Try cmd=LD ---")
                    val cmdLdBody = httpGetDebug("$base/goform/goform_get_cmd_process?cmd=LD", debug)
                    debug.appendLine("cmd=LD response: $cmdLdBody")
                    ld = extractField(cmdLdBody, "LD")
                }

                debug.appendLine("LD: '$ld'")

                // ═══ 4. احسب AD ═══
                debug.appendLine("\n--- AD ---")
                val ad = computeAd(base, debug)

                // ═══ 5. شفر كلمة المرور ═══
                val sha256Pass = sha256(password)
                val encodings = mutableListOf<Pair<String, String>>()

                if (ld.isNotBlank()) {
                    encodings.add("SHA256(pass+LD)" to sha256(password + ld))
                    encodings.add("SHA256(SHA256+LD)" to sha256(sha256Pass + ld))
                }
                encodings.add("Base64" to Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                encodings.add("SHA256" to sha256Pass)

                debug.appendLine("\n--- Encodings ---")
                for ((l, v) in encodings) debug.appendLine("  $l: ${v.take(60)}")

                // ═══ 6. LOGIN ═══
                for ((label, encodedPass) in encodings) {
                    debug.appendLine("\n=== $label ===")

                    for (tryNum in 1..3) {
                        val formBody = FormBody.Builder()
                            .add("isTest", "false")
                            .add("goformId", "LOGIN")
                            .add("password", encodedPass)
                            .add("AD", ad)
                            .add("isForce", "1")
                            .build()

                        val req = Request.Builder()
                            .url("$base/goform/goform_set_cmd_process")
                            .post(formBody)
                            .build()

                        try {
                            val resp = RetrofitClient.getHttpClient().newCall(req).execute()
                            val body = resp.body?.string() ?: ""

                            // اطبع Set-Cookie
                            for (header in resp.headers) {
                                if (header.first.equals("Set-Cookie", ignoreCase = true)) {
                                    debug.appendLine("  🍪 Set-Cookie: ${header.second}")
                                }
                            }

                            debug.appendLine("  Try#$tryNum: $body")
                            debug.appendLine("  Cookies: '${RetrofitClient.getCookiesString()}'")

                            when {
                                body.contains("\"result\":\"0\"") || body.contains("\"result\":0") -> {
                                    debug.appendLine("  ✅ SUCCESS!")
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
        debug.appendLine("\n--- ensureLoggedIn ---")
        val result = login(storage.getRouterIp(), storage.getUsername(), storage.getPassword())
        debug.appendLine("Login: ${result.isSuccess}")
        return result.isSuccess
    }

    // ═══════════════════════════════════════════════════════════════
    // حظر — بعد نجاح LOGIN مباشرة (نفس الجلسة!)
    // ═══════════════════════════════════════════════════════════════

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()
                val base = "http://${storage.getRouterIp()}"
                val client = RetrofitClient.getHttpClient()

                debug.appendLine("=== BLOCK $macUpper ===")

                // أعد الدخول (يحمّل صفحة + يسجل + يحصل على كوكيز)
                if (!ensureLoggedIn(RetrofitClient.getApi(), debug)) {
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext Result.failure(Exception("غير مسجل"))
                }

                debug.appendLine("Cookies: '${RetrofitClient.getCookiesString()}'")

                // اقرأ القائمة
                val aclBody = httpGet("$base/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList")
                debug.appendLine("ACL: $aclBody")

                val existingMacs = Regex(""""BlackMacList"\s*:\s*"([^"]*?)"""")
                    .find(aclBody)?.groupValues?.getOrNull(1)
                    ?.split(";")?.map { it.trim().uppercase() }
                    ?.filter { it.isNotEmpty() && it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
                    ?.toMutableList() ?: mutableListOf()

                if (macUpper !in existingMacs) existingMacs.add(macUpper)
                val newBlackList = existingMacs.joinToString(";") + ";"
                debug.appendLine("New: $newBlackList")

                // احسب AD
                val ad = computeAd(base, debug)

                // ═══ أرسل الحظر ═══
                debug.appendLine("\n--- BLOCK CMD ---")

                // جرب مع AD وبدون AD
                for (withAd in listOf(true, false)) {
                    val label = if (withAd) "With AD" else "Without AD"
                    debug.appendLine("\n=== $label ===")

                    val builder = FormBody.Builder()
                        .add("isTest", "false")
                        .add("goformId", "setDeviceAccessControlList")
                        .add("AclMode", "2")
                        .add("BlackMacList", newBlackList)
                        .add("WhiteMacList", "")
                        .add("WhiteNameList", "")
                        .add("BlackNameList", "")

                    if (withAd && ad.isNotBlank()) builder.add("AD", ad)

                    val req = Request.Builder()
                        .url("$base/goform/goform_set_cmd_process")
                        .post(builder.build())
                        .build()

                    try {
                        val resp = client.newCall(req).execute()
                        val body = resp.body?.string() ?: ""
                        debug.appendLine("Response: $body")

                        // اطبع headers
                        for (header in resp.headers) {
                            if (header.first.equals("Set-Cookie", ignoreCase = true))
                                debug.appendLine("  🍪 ${header.second}")
                        }

                        if (isSuccess(body)) {
                            debug.appendLine("✅ BLOCK SUCCESS!")
                            lastRawResponse = debug.toString()
                            allCommandsDebug = debug.toString()
                            return@withContext Result.success("تم حظر $macUpper")
                        }
                    } catch (e: Exception) { debug.appendLine("Error: ${e.message}") }
                }

                debug.appendLine("\n=== BLOCK FAILED ===")
                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()
                Result.failure(Exception("فشل الحظر"))
            }
        } catch (e: Exception) { Result.failure(Exception("خطأ: ${e.message}")) }
    }

    // ═══════════════════════════════════════════
    // إلغاء حظر
    // ═══════════════════════════════════════════

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

                val req = Request.Builder()
                    .url("$base/goform/goform_set_cmd_process")
                    .post(formBody).build()

                val body = RetrofitClient.getHttpClient().newCall(req).execute().body?.string() ?: ""

                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (isSuccess(body)) Result.success("تم إلغاء حظر $macUpper")
                else Result.failure(Exception("فشل: $body"))
            }
        } catch (e: Exception) { Result.failure(Exception("خطأ: ${e.message}")) }
    }

    // ═══════════════════════════════════════════
    // أجهزة + محظورين + اختبار + خروج
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

    suspend fun testRouterConnection(): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val base = "http://${storage.getRouterIp()}"

                debug.appendLine("=== DIAGNOSTIC ===")

                // اختبار 1: تحميل صفحة بدون XHR
                debug.appendLine("\n--- Page load (no XHR) ---")
                val html = httpGetDebug("$base/m/index.html", debug)
                debug.appendLine("HTML: ${html.length}")
                debug.appendLine("Cookies: '${RetrofitClient.getCookiesString()}'")

                // اختبار 2: nv=LD
                debug.appendLine("\n--- nv=LD ---")
                val nvLd = httpGetDebug("$base/goform/goform_set_cmd_process?nv=LD", debug)
                debug.appendLine("Response: $nvLd")

                // اختبار 3: cmd=LD
                debug.appendLine("\n--- cmd=LD ---")
                val cmdLd = httpGetDebug("$base/goform/goform_get_cmd_process?cmd=LD", debug)
                debug.appendLine("Response: $cmdLd")

                debug.appendLine("\nCookies: '${RetrofitClient.getCookiesString()}'")

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
