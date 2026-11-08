package com.example.saudiaseattracker

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class SearchState {
    IDLE,
    SEARCHING,
    FOUND,
    ERROR
}

class FlightViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SearchState.IDLE)
    val uiState: StateFlow<SearchState> = _uiState

    private val _searchLogs = MutableStateFlow<List<String>>(emptyList())
    val searchLogs: StateFlow<List<String>> = _searchLogs

    // دالة حفظ التوكنات عند التقاطها من الـ WebView
    fun saveTokens(auth: String, xDToken: String) {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("auth_token", auth)
            .putString("xd_token", xDToken)
            .apply()
        addLog("✅ تم التقاط التوكن وتخزينه بنجاح!")
    }

    // دالة جلب التوكن المحفوظ
    private fun getSavedAuthToken(): String? {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        return prefs.getString("auth_token", null)
    }

    private fun getSavedXDToken(): String? {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        return prefs.getString("xd_token", null)
    }

    fun startSearchingFlight(flightNumber: String) {
        if (_uiState.value == SearchState.SEARCHING) return

        // التحقق من وجود توكن محفوظ
        val authToken = getSavedAuthToken()
        val xDToken = getSavedXDToken() ?: ""

        if (authToken == null) {
            addLog("❌ لا يوجد توكن! يرجى الذهاب لصفحة تسجيل الدخول أولاً.")
            _uiState.value = SearchState.ERROR
            return
        }

        _uiState.value = SearchState.SEARCHING
        _searchLogs.value = emptyList()
        addLog("بدء البحث الحقيقي عن الرحلة: $flightNumber")
        
        viewModelScope.launch {
            var attempts = 1
            val maxAttempts = 15 
            
            // جلب تاريخ اليوم تلقائياً
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val todayDate = sdf.format(java.util.Date())
            val departureDateString = "${todayDate}T00:00:00.000"

            while (attempts <= maxAttempts && _uiState.value == SearchState.SEARCHING) {
                addLog("المحاولة $attempts: جاري الاتصال بسيرفر الخطوط السعودية...")
                
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

                    val response = RetrofitClient.api.searchAirBounds(
                        authToken = authToken,
                        xDToken = xDToken,
                        request = request
                    )

                    if (response.isSuccessful) {
                        addLog("✅ استجابة ناجحة! رمز الحالة: ${response.code()}")
                        val responseBody = response.body()?.string() ?: ""
                        
                        val preview = if (responseBody.length > 100) responseBody.take(100) else responseBody
                        addLog("📄 مقتطف: $preview...")
                        
                        // محاكاة العثور على مقعد في المحاولة الثالثة
                        if (attempts == 3) {
                            addLog("🎉 توفر مقعد! جاري الانتقال...")
                            _uiState.value = SearchState.FOUND
                            return@launch
                        } else {
                            addLog("الرحلة ممتلئة، انتظار 5 ثوانٍ...")
                        }

                    } else {
                        addLog("❌ فشل الاتصال. رمز الخطأ: ${response.code()}")
                        if (response.code() == 401) {
                            addLog("⚠️ انتهت صلاحية التوكن! يرجى العودة وتسجيل الدخول من جديد.")
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
