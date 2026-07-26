package com.example.netguardzte.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ZteRouterApi {

    // ═══ تسجيل الدخول ═══
    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun login(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "LOGIN",
        @Field("password") password: String
    ): Response<ResponseBody>

    // ═══ جلب أوامر عامة ═══
    @GET("goform/goform_get_cmd_process")
    suspend fun getGenericCmd(
        @Query("cmd") cmd: String,
        @Query("multimode") multimode: String = "0"
    ): Response<ResponseBody>

    // ═══ جلب معلومات النظام (ل激活 الجلسة) ═══
    @GET("goform/goform_get_cmd_process")
    suspend fun getSystemInfo(
        @Query("cmd") cmd: String = "Language,cr_version,wa_inner_version"
    ): Response<ResponseBody>

    // ═══ الصفحة الرئيسية HTML ═══
    @GET(".")
    suspend fun getMainPage(): Response<ResponseBody>

    @GET("index.html")
    suspend fun getIndexPage(): Response<ResponseBody>

    // ═══ صفحات الحالة ═══
    @GET("status.html")
    suspend fun getStatusPage(): Response<ResponseBody>

    @GET("wifi.html")
    suspend fun getWifiPage(): Response<ResponseBody>

    // ═══ جلب بـ POST (بعض الراوترات تحتاج POST) ═══
    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun postGetStationList(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "GET_STATION_LIST"
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
