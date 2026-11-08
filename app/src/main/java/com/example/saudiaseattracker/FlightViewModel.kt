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

    // 🔥 دالة لحفظ الكوكيز التي يرسلها السيرفر
    private fun saveCookiesFromResponse(response: Response<*>) {
        val setCookieHeaders = response.headers().values("Set-Cookie")
        if (setCookieHeaders.isNotEmpty()) {
            val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            val currentCookies = prefs.getString("dynamic_cookies", "") ?: ""
            
            // استخراج اسم وقيمة الكوكي فقط (تجاهل Path و Domain و Expires)
            val newCookies = setCookieHeaders.joinToString("; ") { it.split(";").first() }
            
            // دمج الجديد مع القديم لتجنب التكرار (يمكن تحسينه لاحقاً)
            val updatedCookies = if (currentCookies.isEmpty()) newCookies else "$currentCookies; $newCookies"
            
            prefs.edit().putString("dynamic_cookies", updatedCookies).apply()
            addLog("🍪 تم التقاط كوكيز جديدة من السيرفر.")
        }
    }

    private fun getSavedDynamicCookies(): String? {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        return prefs.getString("dynamic_cookies", null)
    }

    private fun buildHeaders(): Map<String, String> {
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
            "x-api-version" to "3", 
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

        // 🔥 إضافة الكوكيز المحفوظة للطلب
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
                        headers = buildHeaders(),
                        request = request
                    )
                    
                    // التقاط الكوكيز من الرد
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
                val request = CreateCartRequest(airBoundIds = listOf(flight.airBoundId))
                val response = RetrofitClient.api.createCart(
                    headers = buildHeaders(),
                    request = request
                )
                
                // التقاط الكوكيز من رد إنشاء السلة
                saveCookiesFromResponse(response)

                if (response.isSuccessful) {
                    val responseBody = response.body()?.string() ?: ""
                    addLog("📄 رد إنشاء السلة: $responseBody")
                    
                    // محاولة استخراج cartId من الرد (غالباً يكون في حقل اسمه cartId)
                    try {
                        val jsonObject = JsonParser.parseString(responseBody).asJsonObject
                        val extractedCartId = jsonObject.get("cartId")?.asString 
                            ?: jsonObject.get("id")?.asString 
                            ?: ""
                        
                        if (extractedCartId.isNotEmpty()) {
                            _cartId.value = extractedCartId
                            addLog("✅ تم إنشاء السلة بنجاح! رقم السلة: $extractedCartId")
                        } else {
                            addLog("⚠️ لم نتمكن من قراءة رقم السلة من الرد.")
                        }
                    } catch (e: Exception) {
                         addLog("⚠️ فشل تحليل الرد: ${e.message}")
                    }
                    
                } else {
                    addLog("❌ فشل إنشاء السلة. رمز الخطأ: ${response.code()}")
                }
            } catch (e: Exception) {
                addLog("❌ حدث خطأ أثناء إنشاء السلة: ${e.message}")
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
