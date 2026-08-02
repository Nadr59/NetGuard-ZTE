    // ═══════════════════════════════════════════
    // اكتشاف أوامر استهلاك البيانات
    // ═══════════════════════════════════════════

    suspend fun discoverTrafficCommands(): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val latch = CountDownLatch(1)
                var result = ""

                executor.executeLogin(
                    storage.getRouterIp(),
                    storage.getPassword()
                ) { ok, _ ->
                    if (!ok) {
                        debug.appendLine("Login failed")
                        latch.countDown()
                        return@executeLogin
                    }

                    // ═══ جرب كل أمر ممكن ═══
                    val commands = listOf(
                        // Traffic commands
                        "data_counter",
                        "monthly_data",
                        "traffic_statistics",
                        "station_traffic",
                        "wifi_station_traffic",
                        "lan_station_info",
                        "station_list",
                        "dhcp_list",
                        "connected_device_info",
                        "device_traffic",
                        "device_data_usage",
                        "wifi_client_list",
                        "client_list",
                        "current_station_list",
                        "wlan_station_list",
                        "station_statistics",
                        "traffic_flow",
                        "monthly_statistics",
                        "data_usage",
                        "bandwidth_list",
                        "qos_list",
                        "monthly_rx_tx",
                        "curr_month_download",
                        "curr_month_upload",
                        "curr_day_download",
                        "curr_day_upload",
                        "total_rx_bytes",
                        "total_tx_bytes",
                        "monthly_time",
                        // Traffic monitoring
                        "monitor_main",
                        "traffic_record",
                        "traffic_monitor",
                        "data_flow_record",
                        "monthly_data_statistics",
                        "monthly_data_flow",
                        "data_flow",
                        "traffic_data",
                        "device_data_flow",
                        "all_data_flow",
                        "station_data",
                        "ap_station_list",
                        // TR-069 related
                        "tr069_traffic",
                        // General info
                        "wps_info",
                        "modem_main_state",
                        "network_type",
                        "signalbar",
                        "wifi_coverage",
                        "dhcp_clients"
                    )

                    for (cmd in commands) {
                        try {
                            val js = """
                                (function() {
                                    try {
                                        var xhr = new XMLHttpRequest();
                                        xhr.open('GET', '/goform/goform_get_cmd_process?cmd=$cmd', false);
                                        xhr.send();
                                        return xhr.responseText;
                                    } catch(e) {
                                        return 'ERROR: ' + e;
                                    }
                                })();
                            """.trimIndent()

                            val cmdLatch = CountDownLatch(1)
                            var cmdResult = ""

                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                executor.webViewRef?.evaluateJavascript(js) { r ->
                                    cmdResult = r?.replace("\\\"", "\"")?.trim('"') ?: ""
                                    cmdLatch.countDown()
                                }
                            }

                            cmdLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)

                            if (cmdResult.isNotBlank() &&
                                !cmdResult.contains("ERROR") &&
                                !cmdResult.contains("null") &&
                                cmdResult.length > 10
                            ) {
                                debug.appendLine("\n=== $cmd ===")
                                debug.appendLine(cmdResult.take(500))
                            }
                        } catch (e: Exception) {
                            debug.appendLine("$cmd error: ${e.message}")
                        }
                    }

                    result = debug.toString()
                    latch.countDown()
                }

                latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
                allCommandsDebug = result
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(Exception("error: ${e.message}"))
        }
    }
