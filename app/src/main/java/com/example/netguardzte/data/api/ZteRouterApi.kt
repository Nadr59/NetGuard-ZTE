package com.example.netguardzte.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ZteRouterApi {

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun login(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "LOGIN",
        @Field("password") password: String
    ): Response<ResponseBody>

    // ═══ جلب الأجهزة — نجرب عدة أوامر ═══

    @GET("goform/goform_get_cmd_process")
    suspend fun getStationList(
        @Query("cmd") cmd: String = "station_list",
        @Query("multimode") multimode: String = "0"
    ): Response<ResponseBody>

    @GET("goform/goform_get_cmd_process")
    suspend fun getDhcpList(
        @Query("cmd") cmd: String = "dhcp_list"
    ): Response<ResponseBody>

    @GET("goform/goform_get_cmd_process")
    suspend fun getClientList(
        @Query("cmd") cmd: String = "client_list"
    ): Response<ResponseBody>

    @GET("goform/goform_get_cmd_process")
    suspend fun getLanStationList(
        @Query("cmd") cmd: String = "lan_station_list"
    ): Response<ResponseBody>

    @GET("goform/goform_get_cmd_process")
    suspend fun getWifiClientList(
        @Query("cmd") cmd: String = "wifi_client_list"
    ): Response<ResponseBody>

    @GET("goform/goform_get_cmd_process")
    suspend fun getGenericCmd(
        @Query("cmd") cmd: String,
        @Query("multimode") multimode: String = "0"
    ): Response<ResponseBody>

    // ═══ MAC Filter ═══

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun setMacFilter(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "SET_WIFI_MAC_FILTER",
        @Field("mac_filter_enabled") enabled: String = "1",
        @Field("mac_filter_mode") mode: String = "0",
        @Field("mac_filter_list") macList: String
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun disableMacFilter(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "SET_WIFI_MAC_FILTER",
        @Field("mac_filter_enabled") enabled: String = "0"
    ): Response<ResponseBody>

    @GET("goform/goform_get_cmd_process")
    suspend fun getMacFilterList(
        @Query("cmd") cmd: String = "mac_filter_list"
    ): Response<ResponseBody>

    // ═══ تسجيل الخروج ═══

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun logout(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "LOGOUT"
    ): Response<ResponseBody>
}
