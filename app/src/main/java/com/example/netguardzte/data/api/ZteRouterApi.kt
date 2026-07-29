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
        @Field("password") password: String,
        @Field("AD") ad: String = ""
    ): Response<ResponseBody>

    @GET("goform/goform_get_cmd_process")
    suspend fun getGenericCmd(
        @Query("cmd") cmd: String,
        @Query("multimode") multimode: String = "0"
    ): Response<ResponseBody>

    // ═══ الصفحة الرئيسية (صفحة التحويل) ═══
    @GET(".")
    suspend fun getMainPage(): Response<ResponseBody>

    // ═══ صفحة الدخول الحقيقية ═══
    @GET("m/index.html")
    suspend fun getLoginPage(): Response<ResponseBody>

    // ═══ config.js (في مجلد m) ═══
    @GET("m/config.js")
    suspend fun getConfigJs(): Response<ResponseBody>

    @POST("goform/goform_set_cmd_process")
    suspend fun postRaw(
        @Body body: RequestBody
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun logout(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "LOGOUT"
    ): Response<ResponseBody>
}
