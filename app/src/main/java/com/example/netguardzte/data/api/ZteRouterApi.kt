package com.example.netguardzte.data.api

import okhttp3.RequestBody
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

    @GET("goform/goform_get_cmd_process")
    suspend fun getGenericCmd(
        @Query("cmd") cmd: String,
        @Query("multimode") multimode: String = "0"
    ): Response<ResponseBody>

    // ═══ جديد: جلب معاملات NV (LD, RD) ═══
    @GET("goform/goform_get_cmd_process")
    suspend fun getNvParam(
        @Query("nv") nv: String
    ): Response<ResponseBody>

    @GET("goform/goform_get_cmd_process")
    suspend fun getSystemInfo(
        @Query("cmd") cmd: String = "Language,cr_version,wa_inner_version"
    ): Response<ResponseBody>

    @GET(".")
    suspend fun getMainPage(): Response<ResponseBody>

    @GET("index.html")
    suspend fun getIndexPage(): Response<ResponseBody>

    @GET("status.html")
    suspend fun getStatusPage(): Response<ResponseBody>

    @GET("wifi.html")
    suspend fun getWifiPage(): Response<ResponseBody>

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun postGetStationList(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "GET_STATION_LIST"
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun postGoformId(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String
    ): Response<ResponseBody>

    // ═══ MAC Filter — الطريقة القديمة ═══
    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun setMacFilter(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "SET_WIFI_MAC_FILTER",
        @Field("mac_filter_enabled") enabled: String = "1",
        @Field("mac_filter_mode") mode: String = "0",
        @Field("mac_filter_list") macList: String
    ): Response<ResponseBody>

    // ═══ MAC Filter — الطريقة الجديدة (enable + set) ═══
    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun enableMacFilter(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "SET_WIFI_MAC_FILTER",
        @Field("mac_filter_enabled") enabled: String = "1",
        @Field("mac_filter_mode") mode: String = "0",
        @Field("mac_filter_list") macList: String
    ): Response<ResponseBody>

    // ═══ إيقاف MAC Filter ═══
    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun disableMacFilter(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "SET_WIFI_MAC_FILTER",
        @Field("mac_filter_enabled") enabled: String = "0"
    ): Response<ResponseBody>

    // ═══ قراءة MAC Filter ═══
    @GET("goform/goform_get_cmd_process")
    suspend fun getMacFilterList(
        @Query("cmd") cmd: String = "mac_filter_list"
    ): Response<ResponseBody>

    // ═══ POST خام (للحظر اليدوي) ═══
    @POST("goform/goform_set_cmd_process")
    suspend fun postRaw(
        @Body body: RequestBody
    ): Response<ResponseBody>

    // ═══ تسجيل الخروج ═══
    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun logout(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "LOGOUT"
    ): Response<ResponseBody>
}
