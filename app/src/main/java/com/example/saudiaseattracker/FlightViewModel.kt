package com.example.saudiaseattracker

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

// نموذج لحفظ بيانات الرحلة التي تم العثور عليها
data class FoundFlightDetails(
    val flightNumber: String,
    val date: String,
    val origin: String,
    val destination: String,
    val price: Double,
    val fareFamily: String
)

enum class SearchState {
    IDLE, SEARCHING, FOUND, ERROR, TOKEN_EXPIRED
}

class FlightViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SearchState.IDLE)
    val uiState: StateFlow<SearchState> = _uiState

    private val _searchLogs = MutableStateFlow<List<String>>(emptyList())
    val searchLogs: StateFlow<List<String>> = _searchLogs
    
    // حفظ تفاصيل الرحلة المكتشفة
    private val _foundFlight = MutableStateFlow<FoundFlightDetails?>(null)
    val foundFlight: StateFlow<FoundFlightDetails?> = _foundFlight

    fun saveTokens(auth: String, xDToken: String, sessionId: String, userAgent: String) {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("auth_token", auth)
            .putString("xd_token", xDToken)
            .putString("session_id", sessionId)
            .putString("user_agent", userAgent)
            .apply()
        addLog("✅ تم حفظ التوكن و User-Agent بنجاح!")
    }

    private fun getSavedAuthToken(): String? = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("auth_token", null)
    private fun getSavedXDToken(): String? = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("xd_token", null)
    private fun getSavedSessionId(): String? = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("session_id", null)
    private fun getSavedUserAgent(): String? = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("user_agent", null)

    private fun buildHeaders(authToken: String, xDToken: String, sessionId: String, userAgent: String): Map<String, String> {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)
        val requestId = "|$traceId.$spanId"
        val traceparent = "00-$traceId-$spanId-01"
        val ddParentId = Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString()
        val ddTraceId = Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString()

        return mapOf(
            "Authorization" to authToken,
            "x-d-token" to xDToken,
            "saa-session-id" to sessionId,
            "x-api-version" to "4",
            "platform" to "Web",
            "version" to "27.0",
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Origin" to "https://www.saudia.com",
            "Referer" to "https://www.saudia.com/",
            "User-Agent" to userAgent,
            "Accept-Language" to "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "request-id" to requestId,
            "traceparent" to traceparent,
            "x-datadog-origin" to "rum",
            "x-datadog-parent-id" to ddParentId,
            "x-datadog-sampling-priority" to "1",
            "x-datadog-trace-id" to ddTraceId
        )
    }

    fun startSearchingFlight(flightNumber: String) {
        if (_uiState.value == SearchState.SEARCHING) return

        val authToken = getSavedAuthToken()
        val xDToken = getSavedXDToken() ?: ""
        val sessionId = getSavedSessionId() ?: ""
        val userAgent = getSavedUserAgent() ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        if (authToken == null) {
            addLog("❌ لا يوجد توكن! جاري التحديث...")
            _uiState.value = SearchState.TOKEN_EXPIRED
            return
        }

        _uiState.value = SearchState.SEARCHING
        _searchLogs.value = emptyList()
        _foundFlight.value = null
        addLog("بدء البحث الحقيقي عن الرحلة: $flightNumber")
        
        viewModelScope.launch {
            var attempts = 1
            val maxAttempts = 100
            
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, 2)
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val futureDate = sdf.format(calendar.time)
            val departureDateString = "${futureDate}T00:00:00.000"

            while (attempts <= maxAttempts && _uiState.value == SearchState.SEARCHING) {
                addLog("المحاولة $attempts: جاري فحص الرحلات...")
                
                try {
                    val request = SaudiaSearchRequest(
                        airBoundsSearchInputs = AirBoundsSearchInputs(
                            itineraries = listOf(
                                Itinerary(
                                    originLocationCode = "JED",
                                    destinationLocationCode = "AHB",
                                    departureDateTime = departureDateString
                                )
                            ),
                            travelers = listOf(Traveler()),
                            searchPreferences = SearchPreferences()
                        ),
                        offerCodeAvailabilityInput = OfferCodeAvailabilityInput()
                    )

                    val headers = buildHeaders(authToken, xDToken, sessionId, userAgent)

                    val response = RetrofitClient.api.searchAirBounds(
                        headers = headers,
                        request = request
                    )

                    if (response.isSuccessful) {
                        val responseData = response.body()
                        
                        // البحث عن الرحلة المطلوبة
                        val matchingGroups = responseData?.data?.airBoundGroups?.filter { group ->
                            group.boundDetails?.segments?.any { it.flightId?.contains(flightNumber, ignoreCase = true) == true } == true
                        }

                        if (matchingGroups.isNullOrEmpty()) {
                            addLog("⚠️ لم يتم العثور على الرحلة $flightNumber في نتائج البحث. جاري المحاولة...")
                        } else {
                            val targetGroup = matchingGroups.first()
                            // فلترة الأسعار المتاحة
                            val availableFares = targetGroup.airBounds?.filter { it.status?.value != "soldOut" } ?: emptyList()

                            if (availableFares.isNotEmpty()) {
                                val cheapestFare = availableFares.minByOrNull { it.airOffer?.totalPrice?.value ?: Double.MAX_VALUE }
                                val priceValue = cheapestFare?.airOffer?.totalPrice?.value ?: 0.0
                                val finalPrice = priceValue / 100.0
                                val fareFamily = cheapestFare?.fareFamilyCode ?: "غير معروف"
                                
                                // استخراج رقم الرحلة الفعلي من الـ flightId
                                val rawFlightId = targetGroup.boundDetails?.segments?.firstOrNull()?.flightId ?: ""
                                val actualFlightNum = rawFlightId.split("-").getOrNull(1) ?: flightNumber

                                _foundFlight.value = FoundFlightDetails(
                                    flightNumber = actualFlightNum,
                                    date = futureDate,
                                    origin = targetGroup.boundDetails?.originLocationCode ?: "JED",
                                    destination = targetGroup.boundDetails?.destinationLocationCode ?: "AHB",
                                    price = finalPrice,
                                    fareFamily = fareFamily
                                )
                                
                                addLog("🎉 مبروك! تم العثور على مقعد متاح.")
                                _uiState.value = SearchState.FOUND
                                return@launch
                            } else {
                                addLog("🚫 الرحلة $flightNumber ممتلئة بالكامل (Sold Out). انتظار 15 ثانية...")
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
            
            if (_uiState.value == SearchState.SEARCHING) {
                addLog("⚠️ توقف البحث: انتهت المحاولات.")
                _uiState.value = SearchState.ERROR
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
