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

enum class SearchState {
    IDLE,
    SEARCHING,
    FOUND,
    ERROR,
    TOKEN_EXPIRED
}

class FlightViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SearchState.IDLE)
    val uiState: StateFlow<SearchState> = _uiState

    private val _searchLogs = MutableStateFlow<List<String>>(emptyList())
    val searchLogs: StateFlow<List<String>> = _searchLogs

    fun saveTokens(auth: String, xDToken: String, sessionId: String) {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("auth_token", auth)
            .putString("xd_token", xDToken)
            .putString("session_id", sessionId)
            .apply()
        addLog("✅ تم التقاط التوكن (${auth.take(20)}...) والجلسة ($sessionId)")
    }

    private fun getSavedAuthToken(): String? {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        return prefs.getString("auth_token", null)
    }

    private fun getSavedXDToken(): String? {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        return prefs.getString("xd_token", null)
    }
    
    private fun getSavedSessionId(): String? {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        return prefs.getString("session_id", null)
    }

    // دالة مساعدة لتوليد الترويسات
    private fun buildHeaders(authToken: String, xDToken: String, sessionId: String): Map<String, String> {
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
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
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

        if (authToken == null) {
            addLog("❌ لا يوجد توكن! جاري التحديث...")
            _uiState.value = SearchState.TOKEN_EXPIRED
            return
        }

        _uiState.value = SearchState.SEARCHING
        _searchLogs.value = emptyList()
        addLog("بدء البحث الحقيقي عن الرحلة: $flightNumber")
        
        viewModelScope.launch {
            var attempts = 1
            val maxAttempts = 15 
            
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, 2)
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val futureDate = sdf.format(calendar.time)
            val departureDateString = "${futureDate}T00:00:00.000"
            
            addLog("تاريخ البحث المحدد: $futureDate")

            while (attempts <= maxAttempts && _uiState.value == SearchState.SEARCHING) {
                addLog("المحاولة $attempts: جاري الاتصال...")
                
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

                    // توليد ترويسات جديدة لكل محاولة
                    val headers = buildHeaders(authToken, xDToken, sessionId)

                    val response = RetrofitClient.api.searchAirBounds(
                        headers = headers,
                        request = request
                    )

                    if (response.isSuccessful) {
                        addLog("✅ استجابة ناجحة! رمز الحالة: ${response.code()}")
                        _uiState.value = SearchState.FOUND
                        return@launch
                    } else {
                        addLog("❌ فشل الاتصال. رمز الخطأ: ${response.code()}")
                        
                        if (response.code() == 401) {
                            addLog("⚠️ انتهت الصلاحية (401)! جاري تحديث التوكن...")
                            _uiState.value = SearchState.TOKEN_EXPIRED
                            return@launch
                        } else if (response.code() == 403) {
                            addLog("❌ ممنوع (403)! السيرفر رفض الطلب.")
                            _uiState.value = SearchState.ERROR
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    addLog("❌ حدث خطأ في الشبكة: ${e.message}")
                }
                
                delay(5000) 
                attempts++
            }
            
            if (_uiState.value == SearchState.SEARCHING) {
                addLog("⚠️ توقف البحث: لم يتم العثور على مقعد.")
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
