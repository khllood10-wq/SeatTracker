package com.example.saudiaseattracker

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.Response
import java.util.UUID
import kotlin.random.Random

data class FoundFlightDetails(
    val flightNumber: String,
    val date: String,
    val origin: String,
    val destination: String,
    val price: Double,
    val fareFamily: String,
    val airBoundId: String
)

enum class SearchState {
    IDLE, SEARCHING, FOUND, ERROR, TOKEN_EXPIRED
}

class FlightViewModel(application: Application) : AndroidViewModel(application) {
    var searchOrigin by mutableStateOf("JED")
    var searchDestination by mutableStateOf("AHB")
    var searchDate by mutableStateOf("2026-11-10")

    private val _uiState = MutableStateFlow(SearchState.IDLE)
    val uiState: StateFlow<SearchState> = _uiState

    private val _searchLogs = MutableStateFlow<List<String>>(emptyList())
    val searchLogs: StateFlow<List<String>> = _searchLogs

    private val _foundFlight = MutableStateFlow<FoundFlightDetails?>(null)
    val foundFlight: StateFlow<FoundFlightDetails?> = _foundFlight

    private val _cartId = MutableStateFlow<String?>(null)
    val cartId: StateFlow<String?> = _cartId

    private val _rawCartResponse = MutableStateFlow<String?>(null)
    val rawCartResponse: StateFlow<String?> = _rawCartResponse

    // ✨ جديد: حالة المقاعد
    private val _seatsReady = MutableStateFlow(false)
    val seatsReady: StateFlow<Boolean> = _seatsReady

    private companion object {
        const val HAR_ENCRYPTED_KEY =
            "XkemQfguUodRJM/FrIbiU9QYGL9U7F2b0MWXyOO1FRa3VtlFelkhRvc7iP9DE34fSkJDlIP5ji6v7PpN21gmwaJR//vfeBTHums1dLkxIUDCz8DivhP2IbZqhFgK/9IADtFED6SJkdJJJeFaSmcIxNFDv5kTAj6lXK/JWu2zkRsjz0elXt6WKqetn5XpI7Ls09aUeKETCdarXRSwsLx/6kMo9F1NVWQHhT4CsoAo/1ad3BqxERmi4HIaT7B4osZhGETqZKgSCAg2G4keaIPnhOrifPzQtNMnBInoQXU4yfI1516rgcdPaNRPMbpkGjrB+4HBtj5oW7dyF56DXH67dUT/5z7Tcu8i7rHQMslo9UAbrtwpXdex9mJng0rZzFWSxd7Ed0RGNJRuQ8Xafjk1aA3FVYw6f01BP92reYJatj2ZeAoOfcL7GdDZ7EzdKNFgNzMG41MbLG5GzZXGPRRYMceULb5w3G6UcjSJBwLBgb6WSZH6M4lZH6UHDwgQ5SwCsqpHCHd4SuyK8VLHTUVRHjf9fowM08cb1zu21IygkkxRNIeXBofWAhqfr54jTlLW4g74bTlsbRHuZ76hmVZSz6ZxvXSs75S3f2giZ5eP9Kf0V/lYN0ONM8IFixIOALiO+7+n6nS0LYOEl7v8bM2xHfpEKPOChTZp0eHlOt5WvVw="

        const val HAR_ENCRYPTED_BODY =
            "73371f7a85ee58fad676d0d3e12ac45bf28470a88e7eb50e6089f1585cbcb6c39e97ac3cd9a03a40fdbdb5931e7bf5975afd8f2e224db709e99e3dad5d8a673a9b38d09826f65218df35fc61516f3fa7f6ac1941eba92b826583f281979975562c82c05bae4d6128d8989f42fde23a0b984c0d6d4a4d092d9d5580e2e1797ae83eb1f317277908c0eb4b60ca7149901858236e0cfd419116834cd7fe3817bba8b7ce7d775f607eea3525ca110580e0356cf034d08829d5dee5c59df2cde5011724b5e232f1795a87ae0efbd121122d138925264a1a9c19449a681757c215cc1cb372ccbc1d0c2110093d308a8974c0304ee8c2f7747775eb13438eec0fc5e0ff294d08a47563560bf98339b980af61ca354daacd8d3abe83ee075129d5bc34304479e792b95b472eba68f6c83e5ac49c0c7f4cf56dc5b805f6e40085cd5a72bbb04a1bb4d99a8526d69f96bf122858bcd72c0688a1e78b49a42a30b018be86738cf73731c5139b0a9be873498e40ffbdb4d4645cb25e4b385e748d35ffb0bbec1d20ff59f1e68f4bd5c1196793d5b57f75c6740b37954a6b6f63fe48b6bd107fe00165a3ed79a28d30a180ba6c6d357d32965d8ba0dc0b602888c202578f06c19b8495056d2744d10569d9caa4b5179961151f01ee3debf7237235e65d898b0fc1cfc8d7f9582af51ee558598716886a"

        const val FIRST_TRAVELER_ID = "SKH-1-EXT"
        const val TEST_DOCUMENT_NUMBER = "1071580342"
        const val TEST_DATE_OF_BIRTH = "1991-05-10"
    }

    fun saveTokens(auth: String, xDToken: String, sessionId: String, userAgent: String) {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("auth_token", auth)
            .putString("xd_token", xDToken)
            .putString("session_id", sessionId)
            .putString("user_agent", userAgent)
            .apply()
        addLog("✅ تم التقاط التوكن بنجاح من المتصفح!")
    }

    private fun getSavedAuthToken(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("auth_token", null)
    private fun getSavedXDToken(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("xd_token", null)
    private fun getSavedSessionId(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("session_id", null)
    private fun getSavedUserAgent(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("user_agent", null)

    private fun saveCookiesFromResponse(response: Response<*>) {
        val setCookieHeaders = response.headers().values("Set-Cookie")
        if (setCookieHeaders.isNotEmpty()) {
            val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            val currentCookies = prefs.getString("dynamic_cookies", "") ?: ""
            val newCookies = setCookieHeaders.joinToString("; ") { it.split(";").first() }
            val updatedCookies = if (currentCookies.isEmpty()) newCookies else "$currentCookies; $newCookies"
            prefs.edit().putString("dynamic_cookies", updatedCookies).apply()
            addLog("🍪 تم التقاط كوكيز جديدة.")
        }
    }

    private fun getSavedDynamicCookies(): String? {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        return prefs.getString("dynamic_cookies", null)
    }

    private fun buildHeaders(apiVersion: String): Map<String, String> {
        val authToken = getSavedAuthToken() ?: ""
        val xDToken = getSavedXDToken() ?: ""
        val sessionId = getSavedSessionId() ?: ""
        val userAgent = getSavedUserAgent()
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)

        val headers = mutableMapOf(
            "Authorization" to authToken,
            "x-d-token" to xDToken,
            "saa-session-id" to sessionId,
            "x-api-version" to apiVersion,
            "platform" to "Web",
            "version" to "27.0",
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Origin" to "https://www.saudia.com",
            "Referer" to "https://www.saudia.com/",
            "User-Agent" to userAgent,
            "Accept-Language" to "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "channel" to "MOBILE_WEB",
            "browserid" to "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9",
            "request-id" to "|$traceId.$spanId",
            "traceparent" to "00-$traceId-$spanId-01",
            "x-datadog-origin" to "rum",
            "x-datadog-parent-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString(),
            "x-datadog-sampling-priority" to "1",
            "x-datadog-trace-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString()
        )

        val dynamicCookies = getSavedDynamicCookies()
        if (!dynamicCookies.isNullOrEmpty()) {
            headers["Cookie"] = dynamicCookies
        }

        return headers
    }

    fun startSearchingFlight(flightNumber: String) {
        if (_uiState.value == SearchState.SEARCHING) return

        val authToken = getSavedAuthToken()
        if (authToken == null) {
            addLog("❌ لا يوجد توكن!")
            _uiState.value = SearchState.TOKEN_EXPIRED
            return
        }

        _uiState.value = SearchState.SEARCHING
        _searchLogs.value = emptyList()
        _foundFlight.value = null
        addLog("بدء البحث الحقيقي عن الرحلة: $flightNumber")

        viewModelScope.launch {
            var attempts = 1
            val departureDateString = "${searchDate}T00:00:00.000"

            while (attempts <= 100 && _uiState.value == SearchState.SEARCHING) {
                addLog("المحاولة $attempts: جاري فحص الرحلات...")

                try {
                    val request = SaudiaSearchRequest(
                        airBoundsSearchInputs = AirBoundsSearchInputs(
                            itineraries = listOf(
                                Itinerary(
                                    originLocationCode = searchOrigin,
                                    destinationLocationCode = searchDestination,
                                    departureDateTime = departureDateString
                                )
                            ),
                            travelers = listOf(Traveler()),
                            searchPreferences = SearchPreferences()
                        ),
                        offerCodeAvailabilityInput = OfferCodeAvailabilityInput()
                    )

                    val response = RetrofitClient.api.searchAirBounds(
                        headers = buildHeaders("4"),
                        request = request
                    )

                    saveCookiesFromResponse(response)

                    if (response.isSuccessful) {
                        val responseData = response.body()

                        val matchingGroups = responseData?.data?.airBoundGroups?.filter { group ->
                            group.boundDetails?.segments?.any { it.flightId?.contains(flightNumber, ignoreCase = true) == true } == true
                        }

                        if (matchingGroups.isNullOrEmpty()) {
                            addLog("⚠️ لم يتم العثور على الرحلة $flightNumber.")
                        } else {
                            val targetGroup = matchingGroups.first()
                            val availableFares = targetGroup.airBounds?.filter { it.status?.value != "soldOut" } ?: emptyList()

                            if (availableFares.isNotEmpty()) {
                                val cheapestFare = availableFares.minByOrNull { it.airOffer?.totalPrice?.value ?: Double.MAX_VALUE }
                                val priceValue = cheapestFare?.airOffer?.totalPrice?.value ?: 0.0
                                val finalPrice = priceValue / 100.0

                                _foundFlight.value = FoundFlightDetails(
                                    flightNumber = flightNumber,
                                    date = searchDate,
                                    origin = targetGroup.boundDetails?.origin ?: searchOrigin,
                                    destination = targetGroup.boundDetails?.destination ?: searchDestination,
                                    price = finalPrice,
                                    fareFamily = cheapestFare?.fareFamilyCode ?: "غير معروف",
                                    airBoundId = cheapestFare?.airBoundId ?: ""
                                )

                                addLog("🎉 مبروك! تم العثور على مقعد متاح.")
                                _uiState.value = SearchState.FOUND
                                return@launch
                            } else {
                                addLog("🚫 الرحلة ممتلئة. انتظار 15 ثانية...")
                            }
                        }
                    } else {
                        addLog("❌ فشل الاتصال. رمز الخطأ: ${response.code()}")
                        if (response.code() == 401 || response.code() == 403) {
                            _uiState.value = SearchState.TOKEN_EXPIRED
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    addLog("❌ حدث خطأ: ${e.message}")
                }

                delay(15000)
                attempts++
            }
        }
    }

    fun createCartAndProceedToPassenger() {
        val flight = _foundFlight.value ?: return

        addLog("جاري إنشاء السلة...")

        viewModelScope.launch {
            try {
                val createRequest = CreateCartRequest(airBoundIds = listOf(flight.airBoundId))
                val createResponse = RetrofitClient.api.createCart(
                    headers = buildHeaders("2"),
                    request = createRequest
                )

                saveCookiesFromResponse(createResponse)

                if (createResponse.isSuccessful) {
                    val responseBody = createResponse.body()?.string() ?: ""

                    val jsonObject = JsonParser.parseString(responseBody).asJsonObject
                    val extractedCartId = jsonObject.getAsJsonObject("data")?.get("id")?.asString
                        ?: jsonObject.get("id")?.asString ?: ""

                    if (extractedCartId.isNotEmpty()) {
                        _cartId.value = extractedCartId
                        addLog("✅ تم إنشاء السلة: $extractedCartId")

                        addLog("جاري جلب بيانات صفحة المسافرين...")
                        val getCartResponse = RetrofitClient.api.getCartDetails(
                            cartId = extractedCartId,
                            headers = buildHeaders("3")
                        )

                        saveCookiesFromResponse(getCartResponse)

                        if (getCartResponse.isSuccessful) {
                            val passengerPageData = getCartResponse.body()?.string() ?: ""
                            _rawCartResponse.value = passengerPageData
                            addLog("✅ تم جلب بيانات صفحة المسافرين بنجاح!")

                            // 🔥 جديد: ابدأ طلبات المسافر → المقاعد
                            submitPassengersAndGoToSeats(extractedCartId)
                        } else {
                            addLog("❌ فشل جلب بيانات المسافرين. رمز الخطأ: ${getCartResponse.code()}")
                            _rawCartResponse.value = getCartResponse.errorBody()?.string()
                        }

                    } else {
                        addLog("⚠️ لم نجد cartId في الرد.")
                    }
                } else {
                    addLog("❌ فشل إنشاء السلة. رمز الخطأ: ${createResponse.code()}")
                    _rawCartResponse.value = createResponse.errorBody()?.string()
                }
            } catch (e: Exception) {
                addLog("❌ حدث خطأ: ${e.message}")
                _rawCartResponse.value = "Exception: ${e.message}"
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //   المسافرون → المقاعد (جديد — 4 طلبات)
    // ═══════════════════════════════════════════════════════

    private fun submitPassengersAndGoToSeats(cartId: String) {
        viewModelScope.launch {
            val travelerId = FIRST_TRAVELER_ID

            // ═══ [1/4] ELM Verification ═══
            try {
                addLog("🔐 [1/4] تحقق الهوية ELM...")
                val resp = RetrofitClient.api.verifyElmProfile(
                    headers = buildHeaders("2"),
                    request = ElmVerificationRequest(
                        documentNumber = TEST_DOCUMENT_NUMBER,
                        dateOfBirth = TEST_DATE_OF_BIRTH
                    )
                )
                saveCookiesFromResponse(resp)
                if (!resp.isSuccessful) {
                    addLog("❌ [1/4] HTTP ${resp.code()} — ${resp.errorBody()?.string()}")
                    return@launch
                }
                addLog("✅ [1/4] تم")
            } catch (e: Exception) {
                addLog("❌ [1/4] ${e.message}"); return@launch
            }

            // ═══ [2/4] Get Extensions ═══
            try {
                addLog("📥 [2/4] جلب الملحقات...")
                val resp = RetrofitClient.api.getCartExtensions(
                    cartId = cartId,
                    headers = buildHeaders("2")
                )
                saveCookiesFromResponse(resp)
                if (!resp.isSuccessful) {
                    addLog("❌ [2/4] HTTP ${resp.code()}")
                    return@launch
                }
                addLog("✅ [2/4] تم")
            } catch (e: Exception) {
                addLog("❌ [2/4] ${e.message}"); return@launch
            }

            // ═══ [3/4] Patch Extensions (YQENSTATUS) ═══
            try {
                addLog("🩺 [3/4] حالة الصحة YQENSTATUS...")
                val inner = """{"status":"VERIFIED","healthCheckStatus":"NO_RESPONSE"}"""
                val b64 = android.util.Base64.encodeToString(
                    inner.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                val resp = RetrofitClient.api.patchCartExtensions(
                    cartId = cartId,
                    headers = buildHeaders("2"),
                    request = listOf(
                        CartExtensionRequest(content = b64, travelerId = travelerId)
                    )
                )
                saveCookiesFromResponse(resp)
                if (!resp.isSuccessful) {
                    addLog("❌ [3/4] HTTP ${resp.code()} — ${resp.errorBody()?.string()}")
                    return@launch
                }
                addLog("✅ [3/4] تم")
            } catch (e: Exception) {
                addLog("❌ [3/4] ${e.message}"); return@launch
            }

            // ═══ [4/4] Patch Traveler (مشفّر من HAR) ═══
            try {
                addLog("👤 [4/4] بيانات المسافر...")
                val resp = RetrofitClient.api.patchTravelerWeb(
                    cartId = cartId,
                    travelerId = travelerId,
                    headers = buildHeaders("2"),
                    request = EncryptedTravelerRequest(
                        key = HAR_ENCRYPTED_KEY,
                        body = HAR_ENCRYPTED_BODY
                    )
                )
                saveCookiesFromResponse(resp)
                if (!resp.isSuccessful) {
                    addLog("❌ [4/4] HTTP ${resp.code()} — ${resp.errorBody()?.string()}")
                    return@launch
                }
                addLog("✅ [4/4] المسافر مقبول — الانتقال للمقاعد!")
                _seatsReady.value = true
            } catch (e: Exception) {
                addLog("❌ [4/4] ${e.message}")
            }
        }
    }

    fun stopSearch() {
        _uiState.value = SearchState.IDLE
        addLog("تم إيقاف البحث يدوياً.")
    }

    fun addLog(message: String) {
        val currentLogs = _searchLogs.value.toMutableList()
        currentLogs.add(0, message)
        _searchLogs.value = currentLogs
    }
}
