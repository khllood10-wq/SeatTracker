package com.example.saudiaseattracker

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

    // البحث
    @POST("airsearch/search/air-bounds")
    suspend fun searchAirBounds(
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: SaudiaSearchRequest
    ): Response<SaudiaResponse>

    // السلة
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

    // المسافرون
    @POST("security/elm-profiles/status-verification")
    suspend fun verifyElmProfile(
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: ElmVerificationRequest
    ): Response<ResponseBody>

    @GET("shopping/carts/{cartId}/extensions")
    suspend fun getCartExtensions(
        @Path("cartId") cartId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>
    ): Response<ResponseBody>

    @PATCH("shopping/carts/{cartId}/extensions")
    suspend fun patchCartExtensions(
        @Path("cartId") cartId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: List<CartExtensionRequest>
    ): Response<ResponseBody>

    @PATCH("shopping/carts/{cartId}/travelers/web/{travelerId}")
    suspend fun patchTravelerWeb(
        @Path("cartId") cartId: String,
        @Path("travelerId") travelerId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: EncryptedTravelerRequest
    ): Response<ResponseBody>

    // الدفع
    @POST("shopping/carts/{cartId}/contacts")
    suspend fun addContacts(
        @Path("cartId") cartId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: List<AddContactRequest>
    ): Response<ResponseBody>

    @POST("purchase/orders/web")
    suspend fun createOrder(
        @Query("cartId") cartId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: CreateOrderRequest
    ): Response<ResponseBody>

    @GET("shopping/services/by-order")
    suspend fun getOrderServices(
        @Query("orderId") orderId: String,
        @Query("lastName") lastName: String,
        @Query("pointOfSale") pointOfSale: String = "SA",
        @Query("flow") flow: String = "booking",
        @Query("pageIdentity") pageIdentity: String = "BookingPayment",
        @Query("order_id") orderId2: String = orderId,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("friendlyId") friendlyId: String = "offer_customer_validation",
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

    @POST("checkout/payment/payment-methods")
    suspend fun getPaymentMethods(
        @Query("lastName") lastName: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: PaymentMethodsRequest
    ): Response<ResponseBody>

    @GET("checkout/payment/payment-methods/miles-payment")
    suspend fun getMilesPayment(
        @Query("lastName") lastName: String,
        @Query("orderId") orderId: String,
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("servicesAndSeatsOnly") servicesAndSeatsOnly: Boolean = false,
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
