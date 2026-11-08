package com.example.saudiaseattracker

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface SaudiaApi {
    @POST("airsearch/search/air-bounds")
    suspend fun searchAirBounds(
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @Header("Authorization") authToken: String,
        @Header("x-d-token") xDToken: String,
        @Header("saa-session-id") sessionId: String, // <--- تمت الإضافة هنا
        @Header("x-api-version") xApiVersion: String = "4",
        @Header("platform") platform: String = "Web",
        @Header("version") version: String = "27.0",
        @Header("Accept") accept: String = "application/json",
        @Header("Origin") origin: String = "https://www.saudia.com",
        @Header("Referer") referer: String = "https://www.saudia.com/",
        @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        @Body request: SaudiaSearchRequest
    ): Response<ResponseBody>
}

object RetrofitClient {
    private const val BASE_URL = "https://dapi.saudia.com/"

    val api: SaudiaApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SaudiaApi::class.java)
    }
}
