package com.example.saudiaseattracker

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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
        @Query("pointOfSale") pointOfSale: String = "SA",
        @Query("flow") flow: String = "booking",
        @Query("pageIdentity") pageIdentity: String = "PassengerDetails",
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>
    ): Response<ResponseBody>

    @GET("shopping/services/by-cart")
    suspend fun getServicesByCart(
        @Query("cartId") cartId: String,
        @Query("pointOfSale") pointOfSale: String = "SA",
        @Query("flow") flow: String = "booking",
        @Query("pageIdentity") pageIdentity: String = "PassengerDetails",
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>
    ): Response<ResponseBody>

    @POST("utility/get-gaca-mandate")
    suspend fun getGacaMandate(
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: GacaMandateRequest
    ): Response<ResponseBody>

    @POST("security/elm-profiles/status-verification")
    suspend fun verifyElmProfile(
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: ElmVerifyRequest
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
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08AA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: RequestBody
    ): Response<ResponseBody>

    @POST("purchase/orders")
    suspend fun createOrderRaw(
        @Query("cartId") cartId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08AA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: RequestBody
    ): Response<ResponseBody>

    // ⭐⭐ جديد: الدفع (SADAD) — بدون تشفير
    @POST("checkout/payment/orders/{orderId}/payment-records")
    suspend fun submitPayment(
        @Path("orderId") orderId: String,
        @Query("lastName") lastName: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08AA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: RequestBody
    ): Response<ResponseBody>

    // ⭐⭐ جديد: فحص حالة الطلب
    @GET("purchase/orders/{orderId}")
    suspend fun getOrder(
        @Path("orderId") orderId: String,
        @Query("lastName") lastName: String,
        @Query("mcpCache") mcpCache: String = "true",
        @Query("showPlusgrade") showPlusgrade: String = "false",
        @Query("showOrderEligibilities") showOrderEligibilities: String = "true",
        @Query("pointOfSale") pointOfSale: String = "SA",
        @Query("flow") flow: String = "booking",
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08AA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>
    ): Response<ResponseBody>
}

data class CreateCartRequest(val airBoundIds: List<String>)

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
