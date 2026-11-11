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
        @Query("guestOfficeId") g: String = "JEDSV08MA",
        @Query("lang") l: String = "ar-SA",
        @HeaderMap h: Map<String, String>,
        @Body r: SaudiaSearchRequest
    ): Response<SaudiaResponse>

    @POST("shopping/carts")
    suspend fun createCart(
        @Query("guestOfficeId") g: String = "JEDSV08MA",
        @Query("lang") l: String = "ar-SA",
        @HeaderMap h: Map<String, String>,
        @Body r: CreateCartRequest
    ): Response<ResponseBody>

    @GET("shopping/carts/{cartId}")
    suspend fun getCartDetails(
        @Path("cartId") c: String,
        @Query("pageIdentity") p: String = "PassengerDetails",
        @Query("guestOfficeId") g: String = "JEDSV08MA",
        @Query("lang") l: String = "ar-SA",
        @HeaderMap h: Map<String, String>
    ): Response<ResponseBody>

    @POST("shopping/carts/{cartId}/extensions")
    suspend fun addCartExtension(
        @Path("cartId") c: String,
        @Query("guestOfficeId") g: String = "JEDSV08MA",
        @Query("lang") l: String = "ar-SA",
        @HeaderMap h: Map<String, String>,
        @Body r: RequestBody
    ): Response<ResponseBody>

    @PATCH("shopping/carts/{cartId}/travelers/{travelerId}")
    suspend fun updateTravelerRaw(
        @Path("cartId") c: String,
        @Path("travelerId") t: String,
        @Query("guestOfficeId") g: String = "JEDSV08MA",
        @Query("lang") l: String = "ar-SA",
        @HeaderMap h: Map<String, String>,
        @Body r: RequestBody
    ): Response<ResponseBody>

    @POST("shopping/carts/{cartId}/contacts")
    suspend fun saveContacts(
        @Path("cartId") c: String,
        @Query("guestOfficeId") g: String = "JEDSV08AA",
        @Query("lang") l: String = "ar-SA",
        @HeaderMap h: Map<String, String>,
        @Body r: RequestBody
    ): Response<ResponseBody>

    @POST("purchase/orders")
    suspend fun createOrderRaw(
        @Query("cartId") c: String,
        @Query("guestOfficeId") g: String = "JEDSV08AA",
        @Query("lang") l: String = "ar-SA",
        @HeaderMap h: Map<String, String>,
        @Body r: RequestBody
    ): Response<ResponseBody>

    @POST("checkout/payment/orders/{orderId}/payment-records")
    suspend fun submitPayment(
        @Path("orderId") o: String,
        @Query("lastName") ln: String,
        @Query("guestOfficeId") g: String = "JEDSV08AA",
        @Query("lang") l: String = "ar-SA",
        @HeaderMap h: Map<String, String>,
        @Body r: RequestBody
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
