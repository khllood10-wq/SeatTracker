package com.example.saudiaseattracker

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

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

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 🆕 لا تتبع redirects — كشف حظر Imperva الصامت
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val req = chain.request()
                val t0 = System.currentTimeMillis()
                val resp = chain.proceed(req)
                val dt = System.currentTimeMillis() - t0

                val reqBody = try {
                    val buf = okio.Buffer()
                    req.body?.writeTo(buf)
                    buf.readString(Charsets.UTF_8)
                } catch (e: Exception) { "(unreadable)" }

                val respBody = try {
                    resp.peekBody(200_000).string()
                } catch (e: Exception) { "(unreadable)" }

                DebugBus.emit(
                    DebugEntry(
                        source = DebugSource.RETROFIT,
                        method = req.method,
                        url = req.url.toString(),
                        statusCode = resp.code,
                        title = "${req.method} ${req.url.encodedPath} → ${resp.code} (${dt}ms)",
                        headers = req.headers.joinToString("\n") { "  ${it.first}: ${it.second.take(200)}" },
                        body = buildString {
                            appendLine("═══ REQUEST (${reqBody.length}c) ═══")
                            appendLine(reqBody.take(4000))
                            appendLine()
                            appendLine("═══ RESPONSE (${respBody.length}c) ═══")
                            appendLine(respBody.take(8000))
                        }
                    )
                )
                resp
            }
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
