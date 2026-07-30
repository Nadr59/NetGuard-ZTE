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
import okhttp3.CookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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

    private fun extractField(json: String, field: String): String {
        return Regex(""""$field"\s*:\s*"([^"]*?)"""")
            .find(json)?.groupValues?.getOrNull(1) ?: ""
    }

    // ═══════════════════════════════════════════
    // AD = SHA256(SHA256(wa+cr) + RD)
    // من service.js: cookWithRequest(cookWithRequest(rd0+rd1) + RD)
    // cookWithRequest = SHA256
    // rd0 = wa_inner_version, rd1 = cr_version
    // ═══════════════════════════════════════════

    private fun computeAd(client: OkHttpClient, base: String, debug: StringBuilder): String {
        try {
            fun get(url: String): String {
                return try {
                    client.newCall(Request.Builder().url(url).build())
                        .execute().body?.string() ?: ""
                } catch (_: Exception) { "" }
            }

            val wa = extractField(
                get("$base/goform/goform_get_cmd_process?cmd=wa_inner_version"),
                "wa_inner_version"
            )
            val cr = extractField(
                get("$base/goform/goform_get_cmd_process?cmd=cr_version"),
                "cr_version"
            )
            val rd = extractField(
                get("$base/goform/goform_set_cmd_process?nv=RD&_=${System.currentTimeMillis()}"),
                "RD"
            ).ifBlank {
                extractField(
                    get("$base/goform/goform_get_cmd_process?cmd=RD"),
                    "RD"
                )
            }

            debug.appendLine("wa=${wa.take(20)} cr=$cr RD=${rd.take(16)}")

            if (wa.isBlank() || cr.isBlank() || rd.isBlank()) {
                debug.appendLine("Missing data for AD")
                return ""
            }

            // ═══ SHA256 وليس MD5! ═══
            val ad = sha256(sha256(wa + cr) + rd)
            debug.appendLine("AD=$ad (${ad.length} chars)")
            return ad
        } catch (e: Exception) {
            debug.appendLine("AD error: ${e.message}")
            return ""
        }
    }

    // ═══════════════════════════════════════════
    // LOGIN — مثل المتصفح بالضبط
    // ═══════════════════════════════════════════

    suspend fun login(
        routerIp: String,
        username: String,
        password: String
    ): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                debug.appendLine("=== LOGIN FINAL ===")

                RetrofitClient.setRouterAddress(routerIp)
                val base = "http://$routerIp"
                val client = RetrofitClient.getHttpClient()

                fun get(url: String): String {
                    return try {
                        client.newCall(Request.Builder().url(url).build())
                            .execute().body?.string() ?: ""
                    } catch (_: Exception) { "" }
                }

                fun post(url: String, body: FormBody): String {
                    return try {
                        client.newCall(Request.Builder().url(url).post(body).build())
                            .execute().body?.string() ?: ""
                    } catch (_: Exception) { "" }
                }

                // ═══ 1. حمّل الصفحة ═══
                debug.appendLine("\n--- Page ---")
                get("$base/m/index.html")
                debug.appendLine("Loaded")

                // ═══ 2. طلبات التهيئة (مثل المتصفح) ═══
                debug.appendLine("\n--- Init requests ---")
                val langBody = get("$base/goform/goform_get_cmd_process?cmd=Language")
                debug.appendLine("Language: ${langBody.take(50)}")
                get("$base/goform/goform_get_cmd_process?cmd=wa_inner_version")
                get("$base/goform/goform_get_cmd_process?cmd=cr_version")
                get("$base/goform/goform_get_cmd_process?cmd=RD")
                debug.appendLine("Init done")

                // ═══ 3. جرّب nv=LD (بدون إنشاء جلسة!) ═══
                debug.appendLine("\n--- nv=LD ---")
                val ts = System.currentTimeMillis()
                val nvLdBody = get(
                    "$base/goform/goform_set_cmd_process?nv=LD&_=$ts"
                )
                debug.appendLine("nv=LD: $nvLdBody")
                var ld = extractField(nvLdBody, "LD")

                // ═══ 4. إذا nv=LD فشل، جرب cmd=LD ═══
                if (ld.isBlank()) {
                    debug.appendLine("nv=LD empty, trying cmd=LD...")
                    val cmdLdBody = get(
                        "$base/goform/goform_get_cmd_process?cmd=LD"
                    )
                    debug.appendLine("cmd=LD: $cmdLdBody")
                    ld = extractField(cmdLdBody, "LD")
                }

                debug.appendLine("LD: '${ld.take(20)}...'")

                if (ld.isBlank()) {
                    debug.appendLine("Cannot get LD!")
                    loginDebug = debug.toString()
                    return@withContext Result.failure(Exception("Cannot get LD"))
                }

                // ═══ 5. شفر: SHA256(SHA256(pass) + LD) ═══
                val sha256Pass = sha256(password)
                val encodedPass = sha256(sha256Pass + ld)
                debug.appendLine("SHA256(pass): ${sha256Pass.take(20)}...")
                debug.appendLine("Encoded: ${encodedPass.take(40)}...")

                // ═══ 6. LOGIN — بدون isForce، بدون AD ═══
                // (من service.js: {isTest:false, goformId:"LOGIN", password:..., save_login:false})
                debug.appendLine("\n--- LOGIN ---")
                val loginBody = post(
                    "$base/goform/goform_set_cmd_process",
                    FormBody.Builder()
                        .add("isTest", "false")
                        .add("goformId", "LOGIN")
                        .add("password", encodedPass)
                        .add("save_login", "false")
                        .build()
                )
                debug.appendLine("Response: $loginBody")

                when {
                    loginBody.contains("\"result\":\"0\"") ||
                            loginBody.contains("\"result\":0") -> {
                        debug.appendLine("SUCCESS!")
                        storage.saveCredentials(routerIp, username, password)
                        storage.setLoggedIn(true)
                        loginDebug = debug.toString()
                        return@withContext Result.success("done")
                    }
                    loginBody.contains("\"result\":\"4\"") ||
                            loginBody.contains("\"result\":4") -> {
                        debug.appendLine("SUCCESS (result:4)!")
                        storage.saveCredentials(routerIp, username, password)
                        storage.setLoggedIn(true)
                        loginDebug = debug.toString()
                        return@withContext Result.success("done")
                    }
                    loginBody.contains("\"result\":\"3\"") ||
                            loginBody.contains("\"result\":3") -> {
                        debug.appendLine("WRONG PASSWORD!")
                    }
                    loginBody.contains("\"result\":\"1\"") ||
                            loginBody.contains("\"result\":1") -> {
                        debug.appendLine("Login Fail (session conflict?)")
                    }
                    else -> {
                        debug.appendLine("Unknown: $loginBody")
                    }
                }

                debug.appendLine("\n=== FAILED ===")
                loginDebug = debug.toString()
                Result.failure(Exception("Login failed"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("error: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // ensureLoggedIn
    // ═══════════════════════════════════════════

    private suspend fun ensureLoggedIn(
        api: ZteRouterApi,
        debug: StringBuilder
    ): Boolean {
        debug.appendLine("\n--- ensureLoggedIn ---")
        val result = login(
            storage.getRouterIp(),
            storage.getUsername(),
            storage.getPassword()
        )
        debug.appendLine("Login: ${result.isSuccess}")
        return result.isSuccess
    }

    // ═══════════════════════════════════════════
    // BLOCK — AD بـ SHA256
    // ═══════════════════════════════════════════

    suspend fun blockDevice(
        mac: String,
        currentBlockedList: List<String>
    ): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()
                val base = "http://${storage.getRouterIp()}"
                val client = RetrofitClient.getHttpClient()

                debug.appendLine("=== BLOCK $macUpper ===")

                if (!ensureLoggedIn(RetrofitClient.getApi(), debug)) {
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext Result.failure(Exception("not logged in"))
                }

                fun get(url: String): String {
                    return try {
                        client.newCall(Request.Builder().url(url).build())
                            .execute().body?.string() ?: ""
                    } catch (_: Exception) { "" }
                }

                val aclBody = get(
                    "$base/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList"
                )
                debug.appendLine("ACL: $aclBody")

                val existingMacs = Regex(""""BlackMacList"\s*:\s*"([^"]*?)"""")
                    .find(aclBody)?.groupValues?.getOrNull(1)
                    ?.split(";")
                    ?.map { it.trim().uppercase() }
                    ?.filter {
                        it.isNotEmpty() && it.matches(
                            Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
                        )
                    }
                    ?.toMutableList() ?: mutableListOf()

                if (macUpper !in existingMacs) {
                    existingMacs.add(macUpper)
                }
                val newBlackList = existingMacs.joinToString(";") + ";"
                debug.appendLine("New: $newBlackList")

                // AD بـ SHA256!
                val ad = computeAd(client, base, debug)

                val formBody = FormBody.Builder()
                    .add("isTest", "false")
                    .add("goformId", "setDeviceAccessControlList")
                    .add("AclMode", "2")
                    .add("BlackMacList", newBlackList)
                    .add("WhiteMacList", "")
                    .add("WhiteNameList", "")
                    .add("BlackNameList", "")
                    .add("AD", ad)
                    .build()

                val resp = client.newCall(
                    Request.Builder()
                        .url("$base/goform/goform_set_cmd_process")
                        .post(formBody)
                        .build()
                ).execute().body?.string() ?: ""

                debug.appendLine("Response: $resp")

                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (isSuccess(resp)) {
                    Result.success("blocked $macUpper")
                } else {
                    Result.failure(Exception("failed: $resp"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("error: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // UNBLOCK
    // ═══════════════════════════════════════════

    suspend fun unblockDevice(
        mac: String,
        currentBlockedList: List<String>
    ): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()
                val base = "http://${storage.getRouterIp()}"
                val client = RetrofitClient.getHttpClient()

                debug.appendLine("=== UNBLOCK $macUpper ===")
                ensureLoggedIn(RetrofitClient.getApi(), debug)

                fun get(url: String): String {
                    return try {
                        client.newCall(Request.Builder().url(url).build())
                            .execute().body?.string() ?: ""
                    } catch (_: Exception) { "" }
                }

                val aclBody = get(
                    "$base/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList"
                )
                val existingMacs = Regex(""""BlackMacList"\s*:\s*"([^"]*?)"""")
                    .find(aclBody)?.groupValues?.getOrNull(1)
                    ?.split(";")
                    ?.map { it.trim().uppercase() }
                    ?.filter {
                        it.isNotEmpty() && it.matches(
                            Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
                        )
                    }
                    ?.toMutableList() ?: mutableListOf()

                existingMacs.remove(macUpper)
                val newAclMode = if (existingMacs.isEmpty()) "0" else "2"
                val newBlackList =
                    if (existingMacs.isEmpty()) ""
                    else existingMacs.joinToString(";") + ";"

                val ad = computeAd(client, base, debug)

                val resp = client.newCall(
                    Request.Builder()
                        .url("$base/goform/goform_set_cmd_process")
                        .post(
                            FormBody.Builder()
                                .add("isTest", "false")
                                .add("goformId", "setDeviceAccessControlList")
                                .add("AclMode", newAclMode)
                                .add("BlackMacList", newBlackList)
                                .add("WhiteMacList", "")
                                .add("WhiteNameList", "")
                                .add("BlackNameList", "")
                                .add("AD", ad)
                                .build()
                        )
                        .build()
                ).execute().body?.string() ?: ""

                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (isSuccess(resp)) {
                    Result.success("unblocked $macUpper")
                } else {
                    Result.failure(Exception("failed: $resp"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("error: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // DEVICES + BLOCKED + TEST + LOGOUT
    // ═══════════════════════════════════════════

    suspend fun getConnectedDevices(): Result<List<Device>> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val routerIp = try {
                    storage.getRouterIp()
                } catch (_: Exception) { "192.168.0.1" }
                val subnet = routerIp.substringBeforeLast(".")
                debug.appendLine("=== DEVICE SCAN ===")

                try {
                    Runtime.getRuntime().exec(
                        arrayOf("sh", "-c", "ip neigh flush dev wlan0")
                    ).waitFor()
                } catch (_: Exception) {}

                for (i in 1..50) {
                    try {
                        val s = java.net.Socket()
                        s.connect(
                            java.net.InetSocketAddress("$subnet.$i", 80), 30
                        )
                        s.close()
                    } catch (_: Exception) {}
                }

                var devices = readArpFromAllSources(debug)
                if (devices.isEmpty()) devices = readFromRouterApi(debug)

                allCommandsDebug = debug.toString()
                if (devices.isNotEmpty()) Result.success(devices)
                else Result.failure(Exception("no devices"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("error: ${e.message}"))
        }
    }

    suspend fun getBlockedMacs(): Result<List<String>> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                ensureLoggedIn(RetrofitClient.getApi(), debug)
                val base = "http://${storage.getRouterIp()}"
                val aclBody = try {
                    RetrofitClient.getHttpClient().newCall(
                        Request.Builder().url(
                            "$base/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList"
                        ).build()
                    ).execute().body?.string() ?: ""
                } catch (_: Exception) { "" }

                val macs = Regex(""""BlackMacList"\s*:\s*"([^"]*?)"""")
                    .find(aclBody)?.groupValues?.getOrNull(1)
                    ?.split(";")
                    ?.map { it.trim().uppercase() }
                    ?.filter {
                        it.isNotEmpty() && it.matches(
                            Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
                        )
                    } ?: emptyList()

                allCommandsDebug = debug.toString()
                Result.success(macs)
            }
        } catch (e: Exception) {
            Result.failure(Exception("error: ${e.message}"))
        }
    }

        suspend fun testRouterConnection(): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val host = storage.getRouterIp()
                val password = storage.getPassword()

                debug.appendLine("=== RAW SOCKET TEST ===")

                fun rawHttp(method: String, path: String,
                            body: String = "",
                            cookies: String = ""): Triple<String, String, String> {
                    val socket = java.net.Socket(host, 80)
                    socket.soTimeout = 10000
                    val sb = StringBuilder()
                    sb.append("$method $path HTTP/1.1\r\n")
                    sb.append("Host: $host\r\n")
                    sb.append("Connection: close\r\n")
                    if (cookies.isNotBlank()) sb.append("Cookie: $cookies\r\n")
                    sb.append("Accept: text/javascript, application/javascript, application/ecmascript, application/x-ecmascript, */*; q=0.01\r\n")
                    sb.append("X-Requested-With: XMLHttpRequest\r\n")
                    sb.append("Referer: http://$host/m/index.html\r\n")
                    sb.append("User-Agent: Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36\r\n")
                    if (body.isNotBlank()) {
                        sb.append("Content-Type: application/x-www-form-urlencoded; charset=UTF-8\r\n")
                        sb.append("Content-Length: ${body.toByteArray().size}\r\n")
                    }
                    sb.append("\r\n")
                    if (body.isNotBlank()) sb.append(body)

                    socket.getOutputStream().write(sb.toString().toByteArray())
                    socket.getOutputStream().flush()

                    val resp = StringBuilder()
                    val buf = ByteArray(8192)
                    try {
                        var n: Int
                        while (socket.getInputStream().read(buf).also { n = it } != -1) {
                            resp.append(String(buf, 0, n))
                        }
                    } catch (_: Exception) {}
                    socket.close()

                    val r = resp.toString()
                    val hEnd = r.indexOf("\r\n\r\n")
                    val headers = if (hEnd >= 0) r.substring(0, hEnd) else ""
                    val respBody = (if (hEnd >= 0) r.substring(hEnd + 4) else r).trim()
                    val setCookie = headers.lines()
                        .filter { it.startsWith("Set-Cookie:", true) }
                        .joinToString("; ") {
                            it.substringAfter(":").trim().split(";")[0].trim()
                        }
                    return Triple(headers.lines().first(), respBody, setCookie)
                }

                // 1. Page
                debug.appendLine("\n--- 1. Page ---")
                val (_, _, c1) = rawHttp("GET", "/m/index.html")
                debug.appendLine("Cookies: '$c1'")
                var ck = c1

                // 2. Language
                debug.appendLine("\n--- 2. Language ---")
                val (_, b2, c2) = rawHttp("GET", "/goform/goform_get_cmd_process?cmd=Language", cookies = ck)
                debug.appendLine("Body: $b2")
                debug.appendLine("Cookies: '$c2'")
                if (c2.isNotBlank()) ck = if (ck.isNotBlank()) "$ck; $c2" else c2

                // 3. nv=LD (THE CORRECT WAY!)
                debug.appendLine("\n--- 3. nv=LD ---")
                val (_, b3, c3) = rawHttp("GET", "/goform/goform_set_cmd_process?nv=LD", cookies = ck)
                debug.appendLine("Body: $b3")
                debug.appendLine("Cookies: '$c3'")
                if (c3.isNotBlank()) ck = if (ck.isNotBlank()) "$ck; $c3" else c3
                val nvLd = extractField(b3, "LD")
                debug.appendLine("nv LD: '${nvLd.take(30)}...'")

                // 4. cmd=LD for comparison
                debug.appendLine("\n--- 4. cmd=LD ---")
                val (_, b4, c4) = rawHttp("GET", "/goform/goform_get_cmd_process?cmd=LD", cookies = ck)
                debug.appendLine("Body: $b4")
                debug.appendLine("Cookies: '$c4'")
                if (c4.isNotBlank()) ck = if (ck.isNotBlank()) "$ck; $c4" else c4
                val cmdLd = extractField(b4, "LD")
                debug.appendLine("cmd LD: '${cmdLd.take(30)}...'")

                // 5. Compare
                debug.appendLine("\n--- 5. COMPARE ---")
                debug.appendLine("nv  LD: $nvLd")
                debug.appendLine("cmd LD: $cmdLd")
                debug.appendLine("Same? ${nvLd == cmdLd}")

                // 6. LOGIN with nv=LD (if available)
                val bestLd = if (nvLd.isNotBlank()) nvLd else cmdLd
                if (bestLd.isNotBlank()) {
                    debug.appendLine("\n--- 6. LOGIN (SHA256(SHA256+LD)) ---")
                    val encoded = sha256(sha256(password) + bestLd)
                    debug.appendLine("Encoded: $encoded")

                    val loginBody = "isTest=false&goformId=LOGIN&password=$encoded"
                    val (_, b6, c6) = rawHttp("POST", "/goform/goform_set_cmd_process",
                        body = loginBody, cookies = ck)
                    debug.appendLine("Response: $b6")
                    debug.appendLine("Cookies: '$c6'")

                    // 7. If failed, try SHA256(pass+LD)
                    if (b6.contains("\"result\":\"3\"") || b6.contains("\"result\":1")) {
                        debug.appendLine("\n--- 7. LOGIN (SHA256(pass+LD)) ---")
                        val encoded2 = sha256(password + bestLd)
                        val loginBody2 = "isTest=false&goformId=LOGIN&password=$encoded2"
                        val (_, b7, c7) = rawHttp("POST", "/goform/goform_set_cmd_process",
                            body = loginBody2, cookies = ck)
                        debug.appendLine("Response: $b7")
                    }
                }

                debug.appendLine("\n=== END ===")
                Result.success(debug.toString())
            }
        } catch (e: Exception) {
            Result.failure(Exception("error: ${e.message}"))
        }
    }

    suspend fun logout() {
        try {
            withContext(Dispatchers.IO) {
                try {
                    RetrofitClient.getApi().logout()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.reset()
    }

    // ═══════════════════════════════════════════
    // ARP
    // ═══════════════════════════════════════════
fun saveCredentials(ip: String, username: String, password: String) {
    storage.saveCredentials(ip, username, password)
    storage.setLoggedIn(true)
}
    private suspend fun readArpFromAllSources(debug: StringBuilder): List<Device> {
        var d = readIpNeigh(debug)
        if (d.isNotEmpty()) return d
        d = readArpFromFile()
        if (d.isNotEmpty()) return d
        return readArpFromCommand("cat /proc/net/arp")
    }

    private fun readIpNeigh(debug: StringBuilder): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip neigh"))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) {
                if (!line.uppercase().contains("FAILED") &&
                    !line.uppercase().contains("INCOMPLETE")
                ) {
                    parseArpLine(line)?.let { devices.add(it) }
                }
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
                if (parts.size >= 4 &&
                    parts[3].uppercase() != "00:00:00:00:00:00" &&
                    parts[2] != "0x0"
                ) {
                    devices.add(makeDevice(parts[0], parts[3].uppercase()))
                }
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
            while (line != null) {
                parseArpLine(line)?.let { devices.add(it) }
                line = r.readLine()
            }
            p.waitFor()
            p.destroy()
        } catch (_: Exception) {}
        return devices
    }

    private fun parseArpLine(line: String): Device? {
        val mac = Regex(
            "[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-]" +
                    "[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-]" +
                    "[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}"
        ).find(line)?.value?.uppercase() ?: return null
        if (mac == "00:00:00:00:00:00") return null
        val ip = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
            .find(line)?.value ?: return null
        return makeDevice(ip, mac)
    }

    private suspend fun readFromRouterApi(debug: StringBuilder): List<Device> {
        try {
            val base = "http://${storage.getRouterIp()}"
            for (cmd in listOf("station_list", "wifi_station_list", "dhcp_list")) {
                try {
                    val body = try {
                        RetrofitClient.getHttpClient().newCall(
                            Request.Builder().url(
                                "$base/goform/goform_get_cmd_process?cmd=$cmd"
                            ).build()
                        ).execute().body?.string() ?: ""
                    } catch (_: Exception) { "" }

                    debug.appendLine("  [$cmd]: ${body.take(100)}")
                    if (body.length > 30) {
                        val d = parseDevices(body)
                        if (d.isNotEmpty()) return d
                    }
                } catch (e: Exception) {
                    debug.appendLine("  [$cmd] error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            debug.appendLine("Error: ${e.message}")
        }
        return emptyList()
    }

    private fun makeDevice(ip: String, mac: String): Device {
        val rIp = try { storage.getRouterIp() } catch (_: Exception) { "" }
        return Device(
            mac = mac,
            ip = ip,
            hostname = nameFor(ip, mac),
            connectionType = if (ip == rIp) "Router" else "WiFi"
        )
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
        return when {
            v.isNotBlank() -> "$v ($s)"
            s == "1" -> "Router"
            else -> "Device .$s"
        }
    }

    private fun parseDevices(raw: String): List<Device> {
        try {
            val macs = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
                .findAll(raw)
                .map { it.value.uppercase() }
                .distinct()
                .toList()
            if (macs.isEmpty()) return emptyList()
            val ips = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
                .findAll(raw)
                .map { it.value }
                .toList()
            return macs.mapIndexed { i, m ->
                Device(
                    mac = m,
                    ip = ips.getOrNull(i) ?: "",
                    hostname = "Device ${i + 1}",
                    connectionType = "WiFi"
                )
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }
}
