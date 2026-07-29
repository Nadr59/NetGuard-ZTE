package com.example.netguardzte.data.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var currentBaseUrl: String = "http://192.168.0.1/"
    private var retrofit: Retrofit? = null
    private var api: ZteRouterApi? = null
    private var httpClient: OkHttpClient? = null
    private val cookieStore = mutableMapOf<String, String>()

    fun setRouterAddress(ip: String) {
        currentBaseUrl = "http://$ip/"
        retrofit = null
        api = null
        httpClient = null
    }

    fun setSessionCookie(name: String, value: String) {
        cookieStore[name] = value
    }

    fun getCookiesString(): String =
        cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun getHttpClient(): OkHttpClient {
        if (httpClient == null) {
            httpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
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
                    val original = chain.request()
                    val builder = original.newBuilder()

                    // ═══ أرسل الكوكيز ═══
                    val cookieHeader = getCookiesString()
                    if (cookieHeader.isNotBlank()) {
                        builder.addHeader("Cookie", cookieHeader)
                    }

                    // ═══ أضف headers حسب نوع الطلب ═══
                    val url = original.url.toString()
                    if (url.contains("/goform/")) {
                        // API request = مثل المتصفح مع AJAX
                        builder.addHeader("X-Requested-With", "XMLHttpRequest")
                        builder.addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                        builder.addHeader("Referer", currentBaseUrl + "m/index.html")
                    } else {
                        // Page request = مثل المتصفح عادي (بدون XHR!)
                        builder.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    }

                    builder.addHeader("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")

                    chain.proceed(builder.build())
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
