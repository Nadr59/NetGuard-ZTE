package com.example.netguardzte.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.netguardzte.domain.model.DeviceTraffic
import com.example.netguardzte.domain.model.TrafficSnapshot
import org.json.JSONArray
import org.json.JSONObject

class TrafficStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "traffic_data",
        Context.MODE_PRIVATE
    )

    // ═══ حفظ لقطة ═══
    fun saveSnapshot(snapshot: TrafficSnapshot) {
        val key = "snap_${snapshot.timestamp}"
        val json = JSONObject().apply {
            put("timestamp", snapshot.timestamp)
            put("totalRx", snapshot.totalRx)
            put("totalTx", snapshot.totalTx)
            put("devices", JSONArray().apply {
                for (d in snapshot.devices) {
                    put(JSONObject().apply {
                        put("mac", d.mac)
                        put("hostname", d.hostname)
                        put("ip", d.ip)
                        put("ssidIndex", d.ssidIndex)
                        put("txTotal", d.txTotal)
                        put("rxTotal", d.rxTotal)
                        put("txSpeed", d.txSpeed)
                        put("rxSpeed", d.rxSpeed)
                        put("rssi", d.rssi)
                    })
                }
            })
        }
        prefs.edit().putString(key, json.toString()).apply()

        // احذف اللقطات القديمة (أكثر من 30 يوم)
        cleanOldSnapshots()
    }

    // ═══ جلب كل اللقطات ═══
    fun getAllSnapshots(): List<TrafficSnapshot> {
        val snapshots = mutableListOf<TrafficSnapshot>()
        val all = prefs.all

        for ((key, value) in all) {
            if (!key.startsWith("snap_")) continue
            try {
                val json = JSONObject(value.toString())
                val devices = mutableListOf<DeviceTraffic>()
                val devicesArr = json.getJSONArray("devices")
                for (i in 0 until devicesArr.length()) {
                    val d = devicesArr.getJSONObject(i)
                    devices.add(
                        DeviceTraffic(
                            mac = d.getString("mac"),
                            hostname = d.optString("hostname", ""),
                            ip = d.optString("ip", ""),
                            ssidIndex = d.optString("ssidIndex", ""),
                            txTotal = d.optLong("txTotal", 0),
                            rxTotal = d.optLong("rxTotal", 0),
                            txSpeed = d.optLong("txSpeed", 0),
                            rxSpeed = d.optLong("rxSpeed", 0),
                            rssi = d.optInt("rssi", 0)
                        )
                    )
                }
                snapshots.add(
                    TrafficSnapshot(
                        timestamp = json.getLong("timestamp"),
                        devices = devices,
                        totalRx = json.optLong("totalRx", 0),
                        totalTx = json.optLong("totalTx", 0)
                    )
                )
            } catch (_: Exception) {}
        }

        return snapshots.sortedBy { it.timestamp }
    }

    // ═══ جلب لقطات اليوم ═══
    fun getTodaySnapshots(): List<TrafficSnapshot> {
        val todayStart = getDayStart(System.currentTimeMillis())
        return getAllSnapshots().filter { it.timestamp >= todayStart }
    }

    // ═══ جلب لقطات الشهر ═══
    fun getMonthSnapshots(): List<TrafficSnapshot> {
        val monthStart = getMonthStart(System.currentTimeMillis())
        return getAllSnapshots().filter { it.timestamp >= monthStart }
    }

    // ═══ حساب استهلاك جهاز اليوم ═══
    fun getDeviceTodayUsage(mac: String): Pair<Long, Long> {
        val snapshots = getTodaySnapshots()
        if (snapshots.size < 2) return Pair(0, 0)

        val first = snapshots.first()
        val last = snapshots.last()

        val firstDevice = first.devices.find { it.mac.equals(mac, true) }
        val lastDevice = last.devices.find { it.mac.equals(mac, true) }

        if (firstDevice == null || lastDevice == null) return Pair(0, 0)

        val download = maxOf(0, lastDevice.rxTotal - firstDevice.rxTotal)
        val upload = maxOf(0, lastDevice.txTotal - firstDevice.txTotal)

        return Pair(download, upload)
    }

    // ═══ حساب استهلاك جهاز الشهر ═══
    fun getDeviceMonthUsage(mac: String): Pair<Long, Long> {
        val snapshots = getMonthSnapshots()
        if (snapshots.size < 2) return Pair(0, 0)

        val first = snapshots.first()
        val last = snapshots.last()

        val firstDevice = first.devices.find { it.mac.equals(mac, true) }
        val lastDevice = last.devices.find { it.mac.equals(mac, true) }

        if (firstDevice == null || lastDevice == null) return Pair(0, 0)

        val download = maxOf(0, lastDevice.rxTotal - firstDevice.rxTotal)
        val upload = maxOf(0, lastDevice.txTotal - firstDevice.txTotal)

        return Pair(download, upload)
    }

    // ═══ تنظيف القديم ═══
    private fun cleanOldSnapshots() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val editor = prefs.edit()
        val toRemove = mutableListOf<String>()

        for (key in prefs.all.keys) {
            if (!key.startsWith("snap_")) continue
            try {
                val ts = key.removePrefix("snap_").toLong()
                if (ts < cutoff) toRemove.add(key)
            } catch (_: Exception) {
                toRemove.add(key)
            }
        }

        for (key in toRemove) editor.remove(key)
        editor.apply()
    }

    private fun getDayStart(timestamp: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getMonthStart(timestamp: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
