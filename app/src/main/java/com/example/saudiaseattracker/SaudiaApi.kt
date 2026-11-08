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

    // ── البحث (موجود) ────────────────────────────────────────
    @POST("airsearch/search/air-bounds")
    suspend fun searchAirBounds(
        @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
        @Query("lang") lang: String = "ar-SA",
        @HeaderMap headers: Map<String, String>,
        @Body request: SaudiaSearchRequest
    ): Response<SaudiaResponse>

    // ── السلة (موجود) ─────────────────────────────────────────
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

    // ── المسافرون → المقاعد (جديد من HAR) ─────────────────────

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
