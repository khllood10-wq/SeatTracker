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

    fun saveTokens(auth: String, xDToken: String, sessionId: String, userAgent: String) {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("auth_token", auth)
            .putString("xd_token", xDToken)
            .putString("session_id", sessionId)
            .putString("user_agent", userAgent)
            .apply()
        addLog("✅ تم حفظ التوكن بنجاح!")
    }

    private fun getSavedAuthToken(): String? = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("auth_token", null)
    private fun getSavedXDToken(): String? = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("xd_token", null)
    private fun getSavedSessionId(): String? = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("session_id", null)
    private fun getSavedUserAgent(): String? = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("user_agent", null)

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
        val userAgent = getSavedUserAgent() ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
                        headers = buildHeaders("4"), // إصدار البحث 4
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

    // 🔥 هنا السحر: إنشاء السلة ثم جلب بيانات صفحة المسافرين فوراً
    fun createCartAndProceedToPassenger() {
        val flight = _foundFlight.value ?: return
        
        addLog("جاري إنشاء السلة...")
        
        viewModelScope.launch {
            try {
                // 1. إنشاء السلة
                val createRequest = CreateCartRequest(airBoundIds = listOf(flight.airBoundId))
                val createResponse = RetrofitClient.api.createCart(
                    headers = buildHeaders("2"), // إصدار إنشاء السلة 2
                    request = createRequest
                )
                
                saveCookiesFromResponse(createResponse)

                if (createResponse.isSuccessful) {
                    val responseBody = createResponse.body()?.string() ?: ""
                    
                    // استخراج رقم السلة
                    val jsonObject = JsonParser.parseString(responseBody).asJsonObject
                    val extractedCartId = jsonObject.getAsJsonObject("data")?.get("id")?.asString 
                        ?: jsonObject.get("id")?.asString ?: ""
                    
                    if (extractedCartId.isNotEmpty()) {
                        _cartId.value = extractedCartId
                        addLog("✅ تم إنشاء السلة: $extractedCartId")
                        
                        // 2. جلب بيانات صفحة المسافرين باستخدام رقم السلة
                        addLog("جاري جلب بيانات صفحة المسافرين...")
                        val getCartResponse = RetrofitClient.api.getCartDetails(
                            cartId = extractedCartId,
                            headers = buildHeaders("3") // إصدار جلب السلة 3
                        )
                        
                        saveCookiesFromResponse(getCartResponse)

                        if (getCartResponse.isSuccessful) {
                            val passengerPageData = getCartResponse.body()?.string() ?: ""
                            _rawCartResponse.value = passengerPageData
                            addLog("✅ تم جلب بيانات صفحة المسافرين بنجاح!")
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

    fun stopSearch() {
        _uiState.value = SearchState.IDLE
        addLog("تم إيقاف البحث يدوياً.")
    }

    private fun addLog(message: String) {
        val currentLogs = _searchLogs.value.toMutableList()
        currentLogs.add(0, message) 
        _searchLogs.value = currentLogs
    }
}
