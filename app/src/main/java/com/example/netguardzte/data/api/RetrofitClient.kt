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

    fun setRouterAddress(ip: String) {
        currentBaseUrl = "http://$ip/"
        retrofit = null
        api = null
    }

    fun setSessionCookie(name: String, value: String) {
        cookieStore[name] = value
        retrofit = null
        api = null
    }

    fun setSessionCookie(cookie: String?) {
        if (cookie != null) cookieStore["zsid"] = cookie
        else cookieStore.clear()
        retrofit = null
        api = null
    }

    fun getSessionCookie(): String? = cookieStore["zsid"]

    fun getCookiesString(): String {
        return cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun getApi(): ZteRouterApi {
        if (api == null) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    // احفظ كل الكوكيز من الاستجابة
                    for (header in response.headers("Set-Cookie")) {
                        val nameValue = header.split(";")[0]
                        val parts = nameValue.split("=", limit = 2)
                        if (parts.size == 2) {
                            cookieStore[parts[0].trim()] = parts[1].trim()
                        }
                    }
                    response
                }
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                    val cookieHeader = getCookiesString()
                    if (cookieHeader.isNotBlank()) {
                        request.addHeader("Cookie", cookieHeader)
                    }
                    // ═══ Headers تُحاكي المتصفح ═══
                    request.addHeader("Referer", currentBaseUrl)
                    request.addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                    request.addHeader("X-Requested-With", "XMLHttpRequest")
                    request.addHeader("Accept-Language", "en-US,en;q=0.9")
                    request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    chain.proceed(request.build())
                }
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                        for (cookie in cookies) {
                            cookieStore[cookie.name] = cookie.value
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

    fun reset() {
        cookieStore.clear()
        retrofit = null
        api = null
    }
}
