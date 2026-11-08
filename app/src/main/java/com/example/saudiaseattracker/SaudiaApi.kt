package com.example.saudiaseattracker

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Query

interface SaudiaApi {
    @POST("airsearch/search/air-bounds")
    suspend fun searchAirBounds(
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: SaudiaSearchRequest
    ): Response<ResponseBody>
}

object RetrofitClient {
    private const val BASE_URL = "https://dapi.saudia.com/"

    // 1. إنشاء CookieJar يقرأ من متصفح الأندرويد تلقائياً
    private val webViewCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cookieManager = CookieManager.getInstance()
            for (cookie in cookies) {
                cookieManager.setCookie(url.toString(), cookie.toString())
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookieManager = CookieManager.getInstance()
            val cookieString = cookieManager.getCookie(url.toString()) ?: return emptyList()
            return cookieString.split(";").mapNotNull { 
                Cookie.parse(url, it.trim()) 
            }
        }
    }

    // 2. إعداد عميل OkHttp لاستخدام الكوكيز
    private val client = OkHttpClient.Builder()
        .cookieJar(webViewCookieJar)
        .build()

    val api: SaudiaApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SaudiaApi::class.java)
    }
}
