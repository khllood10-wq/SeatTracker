package com.example.saudiaseattracker

import android.app.Application
import android.content.Context
import android.util.Base64
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

enum class SearchState { IDLE, SEARCHING, FOUND, ERROR, TOKEN_EXPIRED }

class FlightViewModel(application: Application) : AndroidViewModel(application) {
    var searchOrigin by mutableStateOf("JED")
    var searchDestination by mutableStateOf("AHB")
    var searchDate by mutableStateOf("2026-11-17")

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
    private val _passengerConfirmResponse = MutableStateFlow<String?>(null)
    val passengerConfirmResponse: StateFlow<String?> = _passengerConfirmResponse

    fun saveTokens(auth: String, xDToken: String, sessionId: String, userAgent: String) {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("auth_token", auth)
            .putString("xd_token", xDToken)
            .putString("session_id", sessionId)
            .putString("user_agent", userAgent)
            .apply()
        addLog("✅ تم التقاط التوكن (${auth.take(20)}...)")
    }

    private fun getSavedAuthToken(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("auth_token", null)
    private fun getSavedXDToken(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("xd_token", null)
    private fun getSavedSessionId(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("session_id", null)

    // ═══ إصلاح: إدارة كوكيز ذكية (تستبدل ولا تكرر) ═══
    private fun saveCookiesFromResponse(response: Response<*>) {
        val setCookieHeaders = response.headers().values("Set-Cookie")
        if (setCookieHeaders.isEmpty()) return

        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val current = prefs.getString("dynamic_cookies", "") ?: ""

        val cookieMap = mutableMapOf<String, String>()
        if (current.isNotEmpty()) {
            current.split("; ").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) cookieMap[parts[0].trim()] = parts[1].trim()
            }
        }
        setCookieHeaders.forEach { header ->
            val firstPart = header.split(";").first().trim()
            val parts = firstPart.split("=", limit = 2)
            if (parts.size == 2) cookieMap[parts[0].trim()] = parts[1].trim()
        }
        val updated = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        prefs.edit().putString("dynamic_cookies", updated).apply()
    }

    private fun getSavedDynamicCookies(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("dynamic_cookies", null)

    // ═══ إصلاح: ترويسات مختلفة للـ web endpoints (مطابقة HAR) ═══
    private fun buildHeaders(apiVersion: String, isWebEndpoint: Boolean = false): Map<String, String> {
        val authToken = getSavedAuthToken() ?: ""
        val xDToken = getSavedXDToken() ?: ""
        val sessionId = getSavedSessionId() ?: ""

        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)

        // الترويسات الأساسية (موجودة في كل طلبات HAR)
        val headers = mutableMapOf(
            "Authorization" to authToken,
            "x-d-token" to xDToken,
            "saa-session-id" to sessionId,
            "x-api-version" to apiVersion,
            "platform" to "Web",
            "version" to "27.0",
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "request-id" to "|$traceId.$spanId",
            "traceparent" to "00-$traceId-$spanId-01",
            "x-datadog-origin" to "rum",
            "x-datadog-parent-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString(),
            "x-datadog-sampling-priority" to "1",
            "x-datadog-trace-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString()
        )

        if (isWebEndpoint) {
            // للـ /web/ endpoints: ترويسات المتصفح فقط، بدون browserid/channel/x-v-token
            headers["Origin"] = "https://www.saudia.com"
            headers["Referer"] = "https://www.saudia.com/ar-SA/booking/passengerDetails"
            headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            headers["Accept-Language"] = "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7"
            // ⚠️ لا نضيف: browserid, channel, x-v-token (غير موجودة في HAR PATCH)
        } else {
            // للـ endpoints العادية: الترويسات الكاملة
            headers["Origin"] = "https://www.saudia.com"
            headers["Referer"] = "https://www.saudia.com/"
            headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            headers["Accept-Language"] = "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7"
            headers["browserid"] = "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9"
            headers["channel"] = "MOBILE_WEB"
        }

        val cookies = getSavedDynamicCookies()
        if (!cookies.isNullOrEmpty()) {
            headers["Cookie"] = cookies
        }
        return headers
    }

    fun startSearchingFlight(flightNumber: String) {
        if (_uiState.value == SearchState.SEARCHING) return
        if (getSavedAuthToken() == null) {
            addLog("❌ لا يوجد توكن!")
            _uiState.value = SearchState.TOKEN_EXPIRED
            return
        }
        _uiState.value = SearchState.SEARCHING
        _searchLogs.value = emptyList()
        _foundFlight.value = null
        addLog("بدء البحث عن الرحلة: $flightNumber")

        viewModelScope.launch {
            var attempts = 1
            val dateStr = "${searchDate}T00:00:00.000"
            while (attempts <= 100 && _uiState.value == SearchState.SEARCHING) {
                addLog("المحاولة $attempts...")
                try {
                    val request = SaudiaSearchRequest(
                        airBoundsSearchInputs = AirBoundsSearchInputs(
                            itineraries = listOf(Itinerary(searchOrigin, searchDestination, dateStr)),
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
                        val data = response.body()
                        val matching = data?.data?.airBoundGroups?.filter { group ->
                            group.boundDetails?.segments?.any {
                                it.flightId?.contains(flightNumber, ignoreCase = true) == true
                            } == true
                        }
                        if (matching.isNullOrEmpty()) {
                            addLog("⚠️ لم يتم العثور على الرحلة $flightNumber")
                        } else {
                            val group = matching.first()
                            val fares = group.airBounds?.filter { it.status?.value != "soldOut" } ?: emptyList()
                            if (fares.isNotEmpty()) {
                                val cheapest = fares.minByOrNull { it.airOffer?.totalPrice?.value ?: Double.MAX_VALUE }
                                _foundFlight.value = FoundFlightDetails(
                                    flightNumber = flightNumber,
                                    date = searchDate,
                                    origin = group.boundDetails?.origin ?: searchOrigin,
                                    destination = group.boundDetails?.destination ?: searchDestination,
                                    price = (cheapest?.airOffer?.totalPrice?.value ?: 0.0) / 100.0,
                                    fareFamily = cheapest?.fareFamilyCode ?: "غير معروف",
                                    airBoundId = cheapest?.airBoundId ?: ""
                                )
                                addLog("🎉 وُجد مقعد متاح!")
                                _uiState.value = SearchState.FOUND
                                return@launch
                            } else {
                                addLog("🚫 الرحلة ممتلئة. انتظار 15ث...")
                            }
                        }
                    } else {
                        addLog("❌ فشل HTTP ${response.code()}")
                        if (response.code() == 401 || response.code() == 403) {
                            _uiState.value = SearchState.TOKEN_EXPIRED
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    addLog("❌ خطأ: ${e.message}")
                }
                delay(15000)
                attempts++
            }
        }
    }

    fun stopSearch() {
        _uiState.value = SearchState.IDLE
        addLog("تم الإيقاف يدوياً")
    }

    fun createCartAndProceedToPassenger() {
        val flight = _foundFlight.value ?: return
        addLog("جاري إنشاء السلة...")
        viewModelScope.launch {
            try {
                val createResp = RetrofitClient.api.createCart(
                    headers = buildHeaders("2"),
                    request = CreateCartRequest(listOf(flight.airBoundId))
                )
                saveCookiesFromResponse(createResp)
                if (createResp.isSuccessful) {
                    val body = createResp.body()?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    val id = json.getAsJsonObject("data")?.get("id")?.asString
                        ?: json.get("id")?.asString ?: ""
                    if (id.isNotEmpty()) {
                        _cartId.value = id
                        addLog("✅ cartId = $id")
                        val getResp = RetrofitClient.api.getCartDetails(
                            cartId = id,
                            headers = buildHeaders("3")
                        )
                        saveCookiesFromResponse(getResp)
                        if (getResp.isSuccessful) {
                            _rawCartResponse.value = getResp.body()?.string() ?: ""
                            addLog("✅ تم جلب بيانات المسافرين بنجاح!")
                            // 🔍 لوج للتأكد من الكوكيز
                            val savedCookies = getSavedDynamicCookies()
                            if (savedCookies.isNullOrEmpty()) {
                                addLog("⚠️ لا توجد كوكيز محفوظة!")
                            } else {
                                addLog("🍪 كوكيز محفوظة: ${savedCookies.length} حرف")
                            }
                        } else {
                            addLog("❌ فشل جلب البيانات: ${getResp.code()}")
                        }
                    } else addLog("⚠️ لا يوجد cartId")
                } else {
                    addLog("❌ فشل إنشاء السلة: ${createResp.code()}")
                }
            } catch (e: Exception) {
                addLog("❌ ${e.message}")
            }
        }
    }

    fun confirmPassengerData(
        firstName: String,
        lastName: String,
        documentNumber: String,
        dateOfBirth: String,
        travelerId: String = "SKH-1-EXT"
    ) {
        val cartId = _cartId.value
        if (cartId.isNullOrEmpty()) {
            addLog("❌ لا يوجد cartId!")
            return
        }

        viewModelScope.launch {
            _passengerConfirmResponse.value = null

            try {
                addLog("🔍 [1/3] التحقق من الهوية...")
                val verifyResp = RetrofitClient.api.verifyElmProfile(
                    headers = buildHeaders("2"),
                    request = ElmVerifyRequest(
                        documentType = "I",
                        documentNumber = documentNumber,
                        nationality = "SA",
                        dateOfBirth = dateOfBirth
                    )
                )
                saveCookiesFromResponse(verifyResp)
                addLog("✅ التحقق: HTTP ${verifyResp.code()}")

                addLog("📎 [2/3] إضافة Extension...")
                val extensionContent = Base64.encodeToString(
                    """{"status":"VERIFIED","healthCheckStatus":"NO_RESPONSE"}""".toByteArray(),
                    Base64.NO_WRAP
                )
                val extResp = RetrofitClient.api.addCartExtension(
                    cartId = cartId,
                    headers = buildHeaders("2"),
                    request = listOf(
                        CartExtensionRequest(content = extensionContent, travelerId = travelerId)
                    )
                )
                saveCookiesFromResponse(extResp)
                addLog("✅ Extension: HTTP ${extResp.code()}")

                addLog("💾 [3/3] حفظ بيانات المسافر (ترويسات web)...")
                val patchResp = RetrofitClient.api.updateTravelerWeb(
                    cartId = cartId,
                    travelerId = travelerId,
                    headers = buildHeaders("2", isWebEndpoint = true),
                    request = TravelerWebUpdateRequest(
                        firstName = firstName,
                        lastName = lastName,
                        documentNumber = documentNumber,
                        dateOfBirth = dateOfBirth
                    )
                )
                saveCookiesFromResponse(patchResp)

                val responseBody = if (patchResp.isSuccessful) {
                    patchResp.body()?.string() ?: ""
                } else {
                    patchResp.errorBody()?.string() ?: ""
                }

                _passengerConfirmResponse.value =
                    "HTTP ${patchResp.code()}\n\n${responseBody.ifEmpty { "(empty body)" }}"

                addLog("📡 PATCH result: HTTP ${patchResp.code()}")

                when {
                    patchResp.isSuccessful -> addLog("🎉 تم حفظ بيانات المسافر!")
                    patchResp.code() in listOf(400, 415, 422) -> addLog("⚠️ السيرفر رفض JSON عادي")
                    patchResp.code() == 403 -> addLog("🚫 403 — ممنوع حتى مع الترويسات المصححة")
                    else -> addLog("⚠️ رد غير متوقع: HTTP ${patchResp.code()}")
                }

            } catch (e: Exception) {
                addLog("❌ استثناء: ${e.message}")
                _passengerConfirmResponse.value = "Exception: ${e.message}"
            }
        }
    }

    fun addLog(message: String) {
        val curr = _searchLogs.value.toMutableList()
        curr.add(0, message)
        _searchLogs.value = curr
    }
}
