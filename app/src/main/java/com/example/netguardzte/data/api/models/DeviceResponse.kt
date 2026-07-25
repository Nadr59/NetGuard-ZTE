package com.example.netguardzte.data.api.models

import com.google.gson.JsonElement

data class StationListResponse(
    val station_list: JsonElement? = null
)

data class StationInfo(
    val mac: String = "",
    val ip: String = "",
    val hostname: String = "",
    val conn_type: String = "WiFi"
)
