package com.example.saudiaseattracker

import androidx.lifecycle.ViewModel
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

class FlightViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SearchState.IDLE)
    val uiState: StateFlow<SearchState> = _uiState

    private val _searchLogs = MutableStateFlow<List<String>>(emptyList())
    val searchLogs: StateFlow<List<String>> = _searchLogs

    // ⚠️ ضع التوكنات هنا من ملف HAR اللي أرسلته سابقاً
    // ملاحظة: اكتب كلمة Bearer وبعدها مسافة قبل التوكن
    private val authToken = "Bearer ضع_التوكن_هنا_اللي_يبدأ_بـ_eyJ" 
    private val xDToken = "ضع_x-d-token_هنا"

    fun startSearchingFlight(flightNumber: String) {
        if (_uiState.value == SearchState.SEARCHING) return
        
        _uiState.value = SearchState.SEARCHING
        _searchLogs.value = emptyList()
        addLog("بدء البحث الحقيقي عن الرحلة: $flightNumber")
        
        viewModelScope.launch {
            var attempts = 1
            val maxAttempts = 15 
            
            while (attempts <= maxAttempts && _uiState.value == SearchState.SEARCHING) {
                addLog("المحاولة $attempts: جاري الاتصال بسيرفر الخطوط السعودية...")
                
                try {
                    // هنا نرسل الطلب الحقيقي (تجربة البحث من أبها لجدة كبيانات مبدئية)
                    val request = SaudiaSearchRequest(
                        airBoundsSearchInputs = AirBoundsSearchInputs(
                            itineraries = listOf(
                                Itinerary(
                                    originLocationCode = "AHB", // يمكنك تغييرها لاحقاً
                                    destinationLocationCode = "JED",
                                    departureDateTime = "2026-11-07T00:00:00.000"
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
                        
                        // 💡 بما أننا لا نملك شكل الاستجابة الحقيقي (JSON)، سنقوم فقط بطباعة أول 100 حرف للفحص
                        val preview = if (responseBody.length > 100) responseBody.take(100) else responseBody
                        addLog("📄 مقتطف من البيانات: $preview...")
                        
                        // هنا مستقبلاً سنبحث في الـ JSON عن كلمة "AVAILABLE" أو "SOLD_OUT"
                        // حالياً، سنفترض أننا وجدنا الرحلة في المحاولة رقم 3 للتجربة
                        if (attempts == 3) {
                            addLog("🎉 توفر مقعد! جاري الانتقال...")
                            _uiState.value = SearchState.FOUND
                            return@launch
                        } else {
                            addLog("الرحلة ممتلئة (محاكاة)، انتظار 5 ثوانٍ...")
                        }

                    } else {
                        addLog("❌ فشل الاتصال. رمز الخطأ: ${response.code()}")
                        if (response.code() == 401) {
                            addLog("⚠️ انتهت صلاحية التوكن (Token Expired). يرجى تحديث التوكنات في الكود.")
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
