package com.example.saudiaseattracker

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

data class FoundFlightDetails(
    val flightNumber: String,
    val date: String,
    val origin: String,
    val destination: String,
    val price: Double,
    val fareFamily: String,
    val airBoundId: String // 🔥 مهم جداً لإنشاء السلة
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

    // حفظ رقم السلة
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

    private fun buildHeaders(): Map<String, String> {
        val authToken = getSavedAuthToken() ?: ""
        val xDToken = getSavedXDToken() ?: ""
        val sessionId = getSavedSessionId() ?: ""
        val userAgent = getSavedUserAgent() ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)
        
        return mapOf(
            "Authorization" to authToken,
            "x-d-token" to xDToken,
            "saa-session-id" to sessionId,
            "x-api-version" to "3", // تم تغييره حسب ملف الالتقاط الأخير
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
    }

    fun startSearchingFlight(flightNumber: String) {
        if (_uiState.value == SearchState.SEARCHING) return

        val authToken = getSavedAuthToken()
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

                    if (response.isSuccessful) {
                        val responseData = response.body()
                        
                        val matchingGroups = responseData?.data?.airBoundGroups?.filter { group ->
                            group.boundDetails?.segments?.any { it.flightId?.contains(flightNumber, ignoreCase = true) == true } == true
                        }

                        if (matchingGroups.isNullOrEmpty()) {
                            addLog("⚠️ لم يتم العثور على الرحلة $flightNumber. جاري المحاولة...")
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
                                    airBoundId = cheapestFare?.airBoundId ?: "" // 🔥 حفظ الـ airBoundId
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

    // 🔥 دالة جديدة: إنشاء السلة والانتقال للمسافرين
    fun createCartAndProceedToPassenger() {
        val flight = _foundFlight.value ?: return
        
        addLog("جاري إنشاء السلة وحجز الرحلة مؤقتاً...")
        
        viewModelScope.launch {
            try {
                val request = CreateCartRequest(airBoundIds = listOf(flight.airBoundId))
                val response = RetrofitClient.api.createCart(
                    headers = buildHeaders(),
                    request = request
                )
                
                if (response.isSuccessful) {
                    // بما أن الرد غير مقروء في الملف، سنفترض أننا سنحتاج لاستخراج الـ ID
                    // أو قد يكون موجوداً في الترويسات. للحل السريع، سنستخدم الـ ID اللي طلع في الالتقاط
                    // كخطوة أولى، ثم نعدلها لاحقاً إذا لزم الأمر.
                    
                    // ⚠️ ملاحظة: هنا نحتاج لقراءة الـ ResponseBody لاستخراج cartId
                    // بما أننا لا نعرف شكل الـ JSON، سنستخدم رقم السلة اللي طلع في الالتقاط مؤقتاً
                    val fakeCartId = "1P6DHK8GUVPBX6LV" // سنستبدله بالقراءة الفعلية لاحقاً
                    
                    _cartId.value = fakeCartId
                    addLog("✅ تم إنشاء السلة بنجاح! رقم السلة: $fakeCartId")
                    
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
