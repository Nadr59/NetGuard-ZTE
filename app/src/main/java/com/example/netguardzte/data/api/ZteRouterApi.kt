package com.example.netguardzte.data.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ZteRouterApi {

    // ═══ تسجيل الدخول — مع AD ═══
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
