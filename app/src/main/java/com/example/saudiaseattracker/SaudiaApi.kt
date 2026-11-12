package com.example.saudiaseattracker

import com.github.zhkl0228.impersonator.ImpersonatorApi
import com.github.zhkl0228.impersonator.ImpersonatorFactory
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

interface SaudiaApi {
    @POST("airsearch/search/air-bounds")
    suspend fun searchAirBounds(
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: SaudiaSearchRequest
    ): Response<SaudiaResponse>

    @POST("shopping/carts")
    suspend fun createCart(
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: CreateCartRequest
    ): Response<ResponseBody>

    @GET("shopping/carts/{cartId}")
    suspend fun getCartDetails(
        @Path("cartId") cartId: String,
        @Query("pageIdentity") pageIdentity: String = "PassengerDetails",
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>
    ): Response<ResponseBody>

    @POST("shopping/carts/{cartId}/extensions")
    suspend fun addCartExtension(
        @Path("cartId") cartId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: RequestBody
    ): Response<ResponseBody>

    @PATCH("shopping/carts/{cartId}/travelers/{travelerId}")
    suspend fun updateTravelerRaw(
        @Path("cartId") cartId: String,
        @Path("travelerId") travelerId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: RequestBody
    ): Response<ResponseBody>

    @POST("shopping/carts/{cartId}/contacts")
    suspend fun saveContacts(
        @Path("cartId") cartId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: RequestBody
    ): Response<ResponseBody>

    @POST("purchase/orders")
    suspend fun createOrderRaw(
        @Query("cartId") cartId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: RequestBody
    ): Response<ResponseBody>

    @POST("checkout/payment/orders/{orderId}/payment-records")
    suspend fun submitPayment(
        @Path("orderId") orderId: String,
        @Query("lastName") lastName: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: RequestBody
    ): Response<ResponseBody>
}

data class CreateCartRequest(val airBoundIds: List<String>)

object RetrofitClient {
    private const val BASE_URL = "https://dapi.saudia.com/"

    // 👇 SSLContext يحاكي بصمة Chrome (Android profile)[reference:1]
    private val chromeSslContext: SSLContext by lazy {
        val api: ImpersonatorApi = ImpersonatorFactory.android()
        api.newSSLContext(null, null)
    }

    // 👇 TrustManager افتراضي (يقبل كل الشهادات) لأن SSLContext من impersonator لا يوفر TrustManager مباشرة
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    // 👇 OkHttpClient عادي، لكن مع SSLContext المعدّل
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(chromeSslContext.socketFactory, trustAllManager)
            .build()
    }

    val api: SaudiaApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SaudiaApi::class.java)
    }
}
