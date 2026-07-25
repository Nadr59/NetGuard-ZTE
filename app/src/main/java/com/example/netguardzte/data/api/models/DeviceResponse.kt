package com.example.netguardzte.data.api.models

import com.google.gson.annotations.SerializedName

data class StationListResponse(
    @SerializedName("station_list")
    val stationList: List<StationInfo>? = null
)

data class StationInfo(
    val mac: String = "",
    val ip: String = "",
    val hostname: String = "",
    @SerializedName("conn_type")
    val connType: String = "WiFi"
)
