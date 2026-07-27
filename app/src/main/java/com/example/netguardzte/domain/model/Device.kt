package com.example.netguardzte.domain.model

data class Device(
    val mac: String,
    val ip: String = "",
    val hostname: String = "",
    val connectionType: String = "WiFi",
    val isBlocked: Boolean = false
)
