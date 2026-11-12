package com.example.saudiaseattracker

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

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

    @POST("shopping/carts/{cartId}/extensions")
    suspend fun addCartExtension(
        @Path("cartId") cartId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08AA",
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

    @POST("checkout/payment/orders/{orderId}/payment-records")
    suspend fun submitPayment(
        @Path("orderId") orderId: String,
        @Query("lastName") lastName: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08AA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: RequestBody
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
