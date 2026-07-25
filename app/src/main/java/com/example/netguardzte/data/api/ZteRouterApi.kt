package com.example.netguardzte.data.api

import com.example.netguardzte.data.api.models.StationListResponse
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
    suspend fun getStationList(
        @Query("cmd") cmd: String = "station_list",
        @Query("multimode") multimode: String = "0"
    ): Response<StationListResponse>

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

    @FormUrlEncoded
    @POST("goform/goform_set_cmd_process")
    suspend fun logout(
        @Field("isTest") isTest: String = "false",
        @Field("goformId") goformId: String = "LOGOUT"
    ): Response<ResponseBody>
}
