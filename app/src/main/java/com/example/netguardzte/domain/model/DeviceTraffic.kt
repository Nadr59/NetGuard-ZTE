package com.example.netguardzte.domain.model

data class DeviceTraffic(
    val mac: String,
    val hostname: String,
    val ip: String,
    val ssidIndex: String,
    val txTotal: Long,      // إجمالي الرفع (بايت)
    val rxTotal: Long,      // إجمالي التحميل (بايت)
    val txSpeed: Long,      // سرعة الرفع الحالية
    val rxSpeed: Long,      // سرعة التحميل الحالية
    val rssi: Int,           // قوة الإشارة
    val lastSeen: Long = System.currentTimeMillis()
)

data class TrafficSnapshot(
    val timestamp: Long,
    val devices: List<DeviceTraffic>,
    val totalRx: Long,
    val totalTx: Long
)
