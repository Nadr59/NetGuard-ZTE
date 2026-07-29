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

    private fun extractField(json: String, field: String): String {
        val regex = Regex(""""$field"\s*:\s*"([^"]*?)"""")
        return regex.find(json)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun httpGet(url: String): String {
        return try {
            val req = Request.Builder().url(url).build()
            RetrofitClient.getHttpClient().newCall(req).execute().body?.string() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun httpPost(url: String, formBody: FormBody): String {
        return try {
            val req = Request.Builder().url(url).post(formBody).build()
            RetrofitClient.getHttpClient().newCall(req).execute().body?.string() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun computeAd(base: String, debug: StringBuilder): String {
        try {
            val waBody = httpGet("$base/goform/goform_get_cmd_process?cmd=wa_inner_version")
            val crBody = httpGet("$base/goform/goform_get_cmd_process?cmd=cr_version")
            val rdBody = httpGet("$base/goform/goform_get_cmd_process?cmd=RD")

            val wa = extractField(waBody, "wa_inner_version")
            val cr = extractField(crBody, "cr_version")
            val rd = extractField(rdBody, "RD")

            debug.appendLine("wa=${wa.take(20)} cr=$cr RD=${rd.take(16)}")

            if (wa.isBlank() || cr.isBlank() || rd.isBlank()) {
                debug.appendLine("Missing data for AD")
                return ""
            }

            val ad = md5(md5(wa + cr) + rd)
            debug.appendLine("AD=$ad")
            return ad
        } catch (e: Exception) {
            debug.appendLine("AD error: ${e.message}")
            return ""
        }
    }

        suspend fun login(
        routerIp: String,
        username: String,
        password: String
    ): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                debug.appendLine("=== LOGIN v17 ===")

                RetrofitClient.setRouterAddress(routerIp)
                val base = "http://$routerIp"

                // 1. Load page
                debug.appendLine("\n--- Page ---")
                httpGet("$base/m/index.html")
                debug.appendLine("Loaded")

                // 2. Get LD (creates anonymous session!)
                debug.appendLine("\n--- LD ---")
                val ldBody = httpGet("$base/goform/goform_get_cmd_process?cmd=LD")
                val ld = extractField(ldBody, "LD")
                debug.appendLine("LD: '${ld.take(20)}...'")

                // 3. Compute AD for LOGOUT
                debug.appendLine("\n--- AD ---")
                val ad = computeAd(base, debug)

                // 4. LOGOUT immediately to kill anonymous session!
                debug.appendLine("\n--- LOGOUT (kill LD session) ---")
                val logoutBody = httpPost(
                    "$base/goform/goform_set_cmd_process",
                    FormBody.Builder()
                        .add("isTest", "false")
                        .add("goformId", "LOGOUT")
                        .add("AD", ad)
                        .build()
                )
                debug.appendLine("LOGOUT: $logoutBody")
                Thread.sleep(2000)

                // 5. Prepare encodings
                val encodings = mutableListOf<Pair<String, String>>()
                if (ld.isNotBlank()) {
                    encodings.add(
                        "SHA256(pass+LD)" to sha256(password + ld)
                    )
                    encodings.add(
                        "SHA256(SHA256+LD)" to sha256(sha256(password) + ld)
                    )
                }
                encodings.add(
                    "Base64" to Base64.encodeToString(
                        password.toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    )
                )

                debug.appendLine("\n--- Encodings ---")
                for ((l, v) in encodings) {
                    debug.appendLine("  $l: ${v.take(60)}")
                }

                // 6. LOGIN attempts (1 try each, then fresh LD)
                for ((label, encodedPass) in encodings) {
                    debug.appendLine("\n=== $label ===")

                    // Try with existing LD
                    val formBody = FormBody.Builder()
                        .add("isTest", "false")
                        .add("goformId", "LOGIN")
                        .add("password", encodedPass)
                        .add("isForce", "1")
                        .build()

                    val body = httpPost(
                        "$base/goform/goform_set_cmd_process",
                        formBody
                    )
                    debug.appendLine("  Response: $body")

                    when {
                        body.contains("\"result\":\"0\"") ||
                                body.contains("\"result\":0") -> {
                            debug.appendLine("  SUCCESS!")
                            storage.saveCredentials(
                                routerIp, username, password
                            )
                            storage.setLoggedIn(true)
                            loginDebug = debug.toString()
                            return@withContext Result.success("done")
                        }
                        body.contains("\"result\":\"1\"") ||
                                body.contains("\"result\":1") -> {
                            // Session conflict! Fresh LD + retry
                            debug.appendLine(
                                "  Session conflict, fresh LD..."
                            )

                            // Get fresh LD
                            val ld2Body = httpGet(
                                "$base/goform/goform_get_cmd_process?cmd=LD"
                            )
                            val ld2 = extractField(ld2Body, "LD")
                            debug.appendLine("  New LD: ${ld2.take(20)}")

                            // LOGOUT with fresh AD
                            val ad2 = computeAd(base, debug)
                            httpPost(
                                "$base/goform/goform_set_cmd_process",
                                FormBody.Builder()
                                    .add("isTest", "false")
                                    .add("goformId", "LOGOUT")
                                    .add("AD", ad2)
                                    .build()
                            )
                            Thread.sleep(2000)

                            // LOGIN with fresh LD
                            if (ld2.isNotBlank()) {
                                val freshPass = when (label) {
                                    "SHA256(pass+LD)" ->
                                        sha256(password + ld2)
                                    "SHA256(SHA256+LD)" ->
                                        sha256(sha256(password) + ld2)
                                    else -> encodedPass
                                }

                                val retryBody = httpPost(
                                    "$base/goform/goform_set_cmd_process",
                                    FormBody.Builder()
                                        .add("isTest", "false")
                                        .add("goformId", "LOGIN")
                                        .add("password", freshPass)
                                        .add("isForce", "1")
                                        .build()
                                )
                                debug.appendLine("  Retry: $retryBody")

                                if (retryBody.contains("\"result\":\"0\"") ||
                                    retryBody.contains("\"result\":0") ||
                                    retryBody.contains("\"result\":\"1\"") ||
                                    retryBody.contains("\"result\":1")
                                ) {
                                    debug.appendLine("  ACCEPTED!")
                                    storage.saveCredentials(
                                        routerIp, username, password
                                    )
                                    storage.setLoggedIn(true)
                                    loginDebug = debug.toString()
                                    return@withContext Result.success("done")
                                }
                            }
                        }
                        body.contains("\"result\":\"3\"") ||
                                body.contains("\"result\":3") -> {
                            debug.appendLine("  Wrong password")
                        }
                    }
                }

                debug.appendLine("\nALL FAILED")
                loginDebug = debug.toString()
                Result.failure(Exception("login failed"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("error: ${e.message}"))
        }
    }
    
                                
                    

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

    suspend fun blockDevice(
        mac: String,
        currentBlockedList: List<String>
    ): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()
                val base = "http://${storage.getRouterIp()}"

                debug.appendLine("=== BLOCK $macUpper ===")

                if (!ensureLoggedIn(RetrofitClient.getApi(), debug)) {
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext Result.failure(
                        Exception("not logged in")
                    )
                }

                val aclBody = httpGet(
                    "$base/goform/goform_get_cmd_process" +
                            "?cmd=queryDeviceAccessControlList"
                )
                debug.appendLine("ACL: $aclBody")

                val existingMacs = Regex(
                    """"BlackMacList"\s*:\s*"([^"]*?)""""
                ).find(aclBody)?.groupValues?.getOrNull(1)
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

                val ad = computeAd(base, debug)

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

                val resp = httpPost(
                    "$base/goform/goform_set_cmd_process",
                    formBody
                )
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

    suspend fun unblockDevice(
        mac: String,
        currentBlockedList: List<String>
    ): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()
                val base = "http://${storage.getRouterIp()}"

                debug.appendLine("=== UNBLOCK $macUpper ===")
                ensureLoggedIn(RetrofitClient.getApi(), debug)

                val aclBody = httpGet(
                    "$base/goform/goform_get_cmd_process" +
                            "?cmd=queryDeviceAccessControlList"
                )
                val existingMacs = Regex(
                    """"BlackMacList"\s*:\s*"([^"]*?)""""
                ).find(aclBody)?.groupValues?.getOrNull(1)
                    ?.split(";")
                    ?.map { it.trim().uppercase() }
                    ?.filter {
                        it.isNotEmpty() && it.matches(
                            Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
                        )
                    }
                    ?.toMutableList() ?: mutableListOf()

                existingMacs.remove(macUpper)
                val newAclMode =
                    if (existingMacs.isEmpty()) "0" else "2"
                val newBlackList =
                    if (existingMacs.isEmpty()) ""
                    else existingMacs.joinToString(";") + ";"

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

                val body = httpPost(
                    "$base/goform/goform_set_cmd_process",
                    formBody
                )

                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (isSuccess(body)) {
                    Result.success("unblocked $macUpper")
                } else {
                    Result.failure(Exception("failed: $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("error: ${e.message}"))
        }
    }

    suspend fun getConnectedDevices(): Result<List<Device>> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val routerIp = try {
                    storage.getRouterIp()
                } catch (e: Exception) {
                    "192.168.0.1"
                }
                val subnet = routerIp.substringBeforeLast(".")
                debug.appendLine("=== DEVICE SCAN ===")

                try {
                    Runtime.getRuntime().exec(
                        arrayOf("sh", "-c", "ip neigh flush dev wlan0")
                    ).waitFor()
                } catch (e: Exception) {}

                for (i in 1..50) {
                    try {
                        val s = java.net.Socket()
                        s.connect(
                            java.net.InetSocketAddress(
                                "$subnet.$i", 80
                            ), 30
                        )
                        s.close()
                    } catch (e: Exception) {}
                }

                var devices = readArpFromAllSources(debug)
                if (devices.isEmpty()) {
                    devices = readFromRouterApi(debug)
                }

                allCommandsDebug = debug.toString()
                if (devices.isNotEmpty()) {
                    Result.success(devices)
                } else {
                    Result.failure(Exception("no devices"))
                }
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
                val aclBody = httpGet(
                    "$base/goform/goform_get_cmd_process" +
                            "?cmd=queryDeviceAccessControlList"
                )
                val macs = Regex(
                    """"BlackMacList"\s*:\s*"([^"]*?)""""
                ).find(aclBody)?.groupValues?.getOrNull(1)
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
                debug.appendLine("=== TEST ===")
                val loginResult = login(
                    storage.getRouterIp(),
                    storage.getUsername(),
                    storage.getPassword()
                )
                debug.appendLine("Login: ${loginResult.isSuccess}")
                debug.appendLine(loginDebug)
                val base = "http://${storage.getRouterIp()}"
                val acl = httpGet(
                    "$base/goform/goform_get_cmd_process" +
                            "?cmd=queryDeviceAccessControlList"
                )
                debug.appendLine("\nACL: $acl")
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
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.reset()
    }

    private suspend fun readArpFromAllSources(
        debug: StringBuilder
    ): List<Device> {
        var d = readIpNeigh(debug)
        if (d.isNotEmpty()) return d
        d = readArpFromFile()
        if (d.isNotEmpty()) return d
        return readArpFromCommand("cat /proc/net/arp")
    }

    private fun readIpNeigh(
        debug: StringBuilder
    ): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "ip neigh")
            )
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
        } catch (e: Exception) {}
        return devices
    }

    private fun readArpFromFile(): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val f = java.io.File("/proc/net/arp")
            if (!f.exists() || !f.canRead()) return emptyList()
            val r = BufferedReader(
                InputStreamReader(f.inputStream())
            )
            r.readLine()
            var line = r.readLine()
            while (line != null) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4 &&
                    parts[3].uppercase() != "00:00:00:00:00:00" &&
                    parts[2] != "0x0"
                ) {
                    devices.add(
                        makeDevice(parts[0], parts[3].uppercase())
                    )
                }
                line = r.readLine()
            }
            r.close()
        } catch (e: Exception) {}
        return devices
    }

    private fun readArpFromCommand(
        command: String
    ): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", command)
            )
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) {
                parseArpLine(line)?.let { devices.add(it) }
                line = r.readLine()
            }
            p.waitFor()
            p.destroy()
        } catch (e: Exception) {}
        return devices
    }

    private fun parseArpLine(line: String): Device? {
        val mac = Regex(
            "[0-9A-Fa-f]{2}[:\\-]" +
                    "[0-9A-Fa-f]{2}[:\\-]" +
                    "[0-9A-Fa-f]{2}[:\\-]" +
                    "[0-9A-Fa-f]{2}[:\\-]" +
                    "[0-9A-Fa-f]{2}[:\\-]" +
                    "[0-9A-Fa-f]{2}"
        ).find(line)?.value?.uppercase() ?: return null
        if (mac == "00:00:00:00:00:00") return null
        val ip = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
            .find(line)?.value ?: return null
        return makeDevice(ip, mac)
    }

    private suspend fun readFromRouterApi(
        debug: StringBuilder
    ): List<Device> {
        try {
            val base = "http://${storage.getRouterIp()}"
            for (cmd in listOf(
                "station_list",
                "wifi_station_list",
                "dhcp_list"
            )) {
                try {
                    val body = httpGet(
                        "$base/goform/goform_get_cmd_process" +
                                "?cmd=$cmd"
                    )
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
        val rIp = try {
            storage.getRouterIp()
        } catch (e: Exception) {
            ""
        }
        return Device(
            mac = mac,
            ip = ip,
            hostname = nameFor(ip, mac),
            connectionType = if (ip == rIp) "Router" else "WiFi"
        )
    }

    private fun nameFor(ip: String, mac: String): String {
        val v = when {
            mac.startsWith("A4:83") || mac.startsWith("F0:18") ->
                "Apple"
            mac.startsWith("CC:96") || mac.startsWith("58:48") ->
                "Huawei"
            mac.startsWith("70:F9") || mac.startsWith("94:B8") ->
                "Samsung"
            mac.startsWith("6C:B0") || mac.startsWith("54:FA") ->
                "Xiaomi"
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
            val macs = Regex(
                "([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}"
            ).findAll(raw)
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
        } catch (e: Exception) {
            return emptyList()
        }
    }
}
