package com.example.netguardzte.data.repository

import android.content.Context
import com.example.netguardzte.App
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.api.RouterCommandExecutor
import com.example.netguardzte.data.api.ZteRouterApi
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RouterRepository(
    private val storage: SecureStorage,
    private val context: Context
) {

    var lastRawResponse: String = ""
        private set
    var loginDebug: String = ""
        private set
    var allCommandsDebug: String = ""
        private set

    private val executor get() = (context.applicationContext as App).commandExecutor

    private fun isSuccess(body: String): Boolean =
        body.contains("\"result\":\"success\"") ||
                body.contains("\"result\":0") ||
                body.contains("\"result\":\"0\"")

    // ═══════════════════════════════════════════
    // LOGIN — عبر WebView
    // ═══════════════════════════════════════════

    suspend fun login(
        routerIp: String,
        username: String,
        password: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            val latch = CountDownLatch(1)
            var resultOk = false
            var resultMsg = ""

            RetrofitClient.setRouterAddress(routerIp)

            executor.executeLogin(routerIp, password) { ok, msg ->
                resultOk = ok
                resultMsg = msg
                latch.countDown()
            }

            latch.await(30, TimeUnit.SECONDS)

            if (resultOk) {
                storage.saveCredentials(routerIp, username, password)
                storage.setLoggedIn(true)
                loginDebug = resultMsg
                Result.success("done")
            } else {
                loginDebug = resultMsg
                Result.failure(Exception("login failed: $resultMsg"))
            }
        }
    }

    fun saveCredentials(ip: String, username: String, password: String) {
        storage.saveCredentials(ip, username, password)
        storage.setLoggedIn(true)
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

    // ═══════════════════════════════════════════
    // BLOCK — عبر WebView
    // ═══════════════════════════════════════════

    suspend fun blockDevice(
        mac: String,
        currentBlockedList: List<String>
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            val debug = StringBuilder()
            val macUpper = mac.uppercase().trim()

            debug.appendLine("=== BLOCK $macUpper ===")

            if (!ensureLoggedIn(RetrofitClient.getApi(), debug)) {
                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()
                return@withContext Result.failure(Exception("not logged in"))
            }

            val aclLatch = CountDownLatch(1)
            var aclBody = ""
            executor.executeGetAcl(storage.getRouterIp()) { result ->
                aclBody = result
                aclLatch.countDown()
            }
            aclLatch.await(15, TimeUnit.SECONDS)
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

            val blockLatch = CountDownLatch(1)
            var blockOk = false
            var blockResult = ""
            executor.executeBlock(storage.getRouterIp(), newBlackList) { ok, result ->
                blockOk = ok
                blockResult = result
                blockLatch.countDown()
            }
            blockLatch.await(15, TimeUnit.SECONDS)

            debug.appendLine("Result: $blockResult")
            lastRawResponse = debug.toString()
            allCommandsDebug = debug.toString()

            if (blockOk) {
                Result.success("blocked $macUpper")
            } else {
                Result.failure(Exception("failed: $blockResult"))
            }
        }
    }

    // ═══════════════════════════════════════════
    // UNBLOCK — عبر WebView
    // ═══════════════════════════════════════════

    suspend fun unblockDevice(
        mac: String,
        currentBlockedList: List<String>
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            val debug = StringBuilder()
            val macUpper = mac.uppercase().trim()

            debug.appendLine("=== UNBLOCK $macUpper ===")
            ensureLoggedIn(RetrofitClient.getApi(), debug)

            val aclLatch = CountDownLatch(1)
            var aclBody = ""
            executor.executeGetAcl(storage.getRouterIp()) { result ->
                aclBody = result
                aclLatch.countDown()
            }
            aclLatch.await(15, TimeUnit.SECONDS)

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

            val unblockLatch = CountDownLatch(1)
            var unblockOk = false
            var unblockResult = ""
            executor.executeUnblock(
                storage.getRouterIp(),
                newAclMode,
                newBlackList
            ) { ok, result ->
                unblockOk = ok
                unblockResult = result
                unblockLatch.countDown()
            }
            unblockLatch.await(15, TimeUnit.SECONDS)

            lastRawResponse = debug.toString()
            allCommandsDebug = debug.toString()

            if (unblockOk) {
                Result.success("unblocked $macUpper")
            } else {
                Result.failure(Exception("failed: $unblockResult"))
            }
        }
    }

    // ═══════════════════════════════════════════
    // DISCOVER TRAFFIC COMMANDS
    // ═══════════════════════════════════════════

    suspend fun discoverTrafficCommands(): Result<String> {
        return withContext(Dispatchers.IO) {
            val debug = StringBuilder()
            val latch = CountDownLatch(1)

            executor.executeLogin(
                storage.getRouterIp(),
                storage.getPassword()
            ) { ok, _ ->
                if (!ok) {
                    debug.appendLine("Login failed")
                    latch.countDown()
                    return@executeLogin
                }

                val commands = listOf(
                    "data_counter", "monthly_data", "traffic_statistics",
                    "station_traffic", "wifi_station_traffic",
                    "lan_station_info", "station_list", "dhcp_list",
                    "connected_device_info", "device_traffic",
                    "device_data_usage", "wifi_client_list",
                    "client_list", "current_station_list",
                    "wlan_station_list", "station_statistics",
                    "traffic_flow", "monthly_statistics",
                    "data_usage", "bandwidth_list", "qos_list",
                    "monthly_rx_tx", "curr_month_download",
                    "curr_month_upload", "curr_day_download",
                    "curr_day_upload", "total_rx_bytes",
                    "total_tx_bytes", "monthly_time",
                    "monitor_main", "traffic_record",
                    "traffic_monitor", "data_flow_record",
                    "monthly_data_statistics", "monthly_data_flow",
                    "data_flow", "traffic_data", "device_data_flow",
                    "all_data_flow", "station_data",
                    "ap_station_list", "wps_info",
                    "modem_main_state", "network_type",
                    "signalbar", "dhcp_clients"
                )

                for (cmd in commands) {
                    try {
                        val cmdLatch = CountDownLatch(1)
                        var cmdResult = ""

                        executor.executeGet(cmd) { r ->
                            cmdResult = r
                            cmdLatch.countDown()
                        }

                        cmdLatch.await(5, TimeUnit.SECONDS)

                        if (cmdResult.isNotBlank() &&
                            !cmdResult.contains("ERROR") &&
                            !cmdResult.contains("null") &&
                            cmdResult.length > 10 &&
                            cmdResult != "\"\"" &&
                            cmdResult != "{}"
                        ) {
                            debug.appendLine("\n=== $cmd ===")
                            debug.appendLine(cmdResult.take(500))
                        }
                    } catch (e: Exception) {
                        debug.appendLine("$cmd error: ${e.message}")
                    }
                }

                debug.appendLine("\n=== DONE ===")
                latch.countDown()
            }

            latch.await(120, TimeUnit.SECONDS)
            allCommandsDebug = debug.toString()
            Result.success(debug.toString())
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
        return withContext(Dispatchers.IO) {
            val debug = StringBuilder()
            ensureLoggedIn(RetrofitClient.getApi(), debug)

            val latch = CountDownLatch(1)
            var aclBody = ""
            executor.executeGetAcl(storage.getRouterIp()) { result ->
                aclBody = result
                latch.countDown()
            }
            latch.await(15, TimeUnit.SECONDS)

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
    }

    suspend fun testRouterConnection(): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                debug.appendLine("=== TEST ===")
                val result = login(
                    storage.getRouterIp(),
                    storage.getUsername(),
                    storage.getPassword()
                )
                debug.appendLine("Login: ${result.isSuccess}")
                debug.appendLine(loginDebug)
                Result.success(debug.toString())
            }
        } catch (e: Exception) {
            Result.failure(Exception("error: ${e.message}"))
        }
    }

    suspend fun logout() {
        storage.setLoggedIn(false)
        RetrofitClient.reset()
        executor.destroy()
    }

    // ═══════════════════════════════════════════
    // ARP
    // ═══════════════════════════════════════════

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
            for (cmd in listOf(
                "station_list", "wifi_station_list", "dhcp_list"
            )) {
                try {
                    val latch = CountDownLatch(1)
                    var body = ""

                    executor.executeGet(cmd) { result ->
                        body = result
                        latch.countDown()
                    }
                    latch.await(10, TimeUnit.SECONDS)

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
