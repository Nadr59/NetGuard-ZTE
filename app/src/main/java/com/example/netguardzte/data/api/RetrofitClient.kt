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
    private var retrofit: Retrofit? = null
    private var api: ZteRouterApi? = null

    private val cookieStore = mutableMapOf<String, String>()

    // ═══ OkHttp مشترك لكل العمليات ═══
    private var httpClient: OkHttpClient? = null

    fun setRouterAddress(ip: String) {
        currentBaseUrl = "http://$ip/"
        retrofit = null
        api = null
        httpClient = null
    }

    fun setSessionCookie(name: String, value: String) {
        cookieStore[name] = value
    }

    fun getSessionCookie(): String? = cookieStore["zsid"]

    fun getCookiesString(): String {
        return cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    // ═══ الوصول لـ OkHttp client مباشرة ═══
    fun getHttpClient(): OkHttpClient {
        if (httpClient == null) {
            httpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                        for (c in cookies) {
                            cookieStore[c.name] = c.value
                        }
                    }
                    override fun loadForRequest(url: HttpUrl): List<Cookie> {
                        return cookieStore.map { (name, value) ->
                            Cookie.Builder()
                                .domain(url.host)
                                .path("/")
                                .name(name)
                                .value(value)
                                .build()
                        }
                    }
                })
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                    val cookieHeader = getCookiesString()
                    if (cookieHeader.isNotBlank()) {
                        request.addHeader("Cookie", cookieHeader)
                    }
                    request.addHeader("Referer", currentBaseUrl + "m/index.html")
                    request.addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                    request.addHeader("X-Requested-With", "XMLHttpRequest")
                    request.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                    chain.proceed(request.build())
                }
                .build()
        }
        return httpClient!!
    }

    fun getApi(): ZteRouterApi {
        if (api == null) {
            retrofit = Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .client(getHttpClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            api = retrofit!!.create(ZteRouterApi::class.java)
        }
        return api!!
    }

    fun reset() {
        cookieStore.clear()
        retrofit = null
        api = null
        httpClient = null
    }
}
