package com.example.netguardzte.data.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var currentBaseUrl: String = "http://192.168.0.1/"
    private var sessionCookie: String? = null
    private var retrofit: Retrofit? = null
    private var api: ZteRouterApi? = null

    fun setRouterAddress(ip: String) {
        currentBaseUrl = "http://$ip/"
        retrofit = null
        api = null
    }

    fun setSessionCookie(cookie: String?) {
        sessionCookie = cookie
        retrofit = null
        api = null
    }

    fun getSessionCookie(): String? = sessionCookie

    fun getApi(): ZteRouterApi {
        if (api == null) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                    sessionCookie?.let {
                        request.addHeader("Cookie", "zsid=$it")
                    }
                    chain.proceed(request.build())
                }
                .cookieJar(object : CookieJar {
                    private val cookies = mutableMapOf<String, List<Cookie>>()

                    override fun saveFromResponse(url: HttpUrl, cks: List<Cookie>) {
                        cookies[url.host] = cks
                        for (cookie in cks) {
                            if (cookie.name == "zsid") {
                                sessionCookie = cookie.value
                            }
                        }
                    }

                    override fun loadForRequest(url: HttpUrl): List<Cookie> {
                        return cookies[url.host] ?: emptyList()
                    }
                })
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            api = retrofit!!.create(ZteRouterApi::class.java)
        }
        return api!!
    }
}
