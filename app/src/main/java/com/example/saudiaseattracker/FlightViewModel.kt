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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    private fun buildHeaders(
        apiVersion: String,
        isWebEndpoint: Boolean = false,
        referer: String = "https://www.saudia.com/"
    ): Map<String, String> {
        val authToken = getSavedAuthToken() ?: ""
        val xDToken = getSavedXDToken() ?: ""
        val sessionId = getSavedSessionId() ?: ""
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
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept-Language" to "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "request-id" to "|$traceId.$spanId",
            "traceparent" to "00-$traceId-$spanId-01",
            "x-datadog-origin" to "rum",
            "x-datadog-parent-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString(),
            "x-datadog-sampling-priority" to "1",
            "x-datadog-trace-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString()
        )

        if (isWebEndpoint) {
            headers["x-v-token"] = "YTA2MmU3YTZhMDM5MjgyMDIxNzFkZWEyOWEyMjIxZDEwOGNmMWFkNTJkODk4YmE3ZThlMzI4MzU1MjExNzViOWY0MTBlMzRlOTNlNGU5YmY4YThlZTAwNzdhM2Q3NWU2YzM5ZTAzOWQxNDBhOTdiZDZjMmE2MjlkNzIxNDM4YzU="
        } else {
            headers["browserid"] = "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9"
            headers["channel"] = "MOBILE_WEB"
        }
        val cookies = getSavedDynamicCookies()
        if (!cookies.isNullOrEmpty()) headers["Cookie"] = cookies
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
        addLog("═".repeat(40))
        addLog("🎬 بدء تسلسل صفحة المسافرين...")
        viewModelScope.launch {
            try {
                addLog("📦 [1/5] إنشاء السلة...")
                val createResp = RetrofitClient.api.createCart(
                    headers = buildHeaders("2", referer = "https://www.saudia.com/"),
                    request = CreateCartRequest(listOf(flight.airBoundId))
                )
                saveCookiesFromResponse(createResp)
                if (!createResp.isSuccessful) {
                    addLog("❌ فشل إنشاء السلة: HTTP ${createResp.code()}")
                    return@launch
                }
                val body = createResp.body()?.string() ?: ""
                val json = JsonParser.parseString(body).asJsonObject
                val id = json.getAsJsonObject("data")?.get("id")?.asString
                    ?: json.get("id")?.asString ?: ""
                if (id.isEmpty()) {
                    addLog("⚠️ لا يوجد cartId")
                    return@launch
                }
                _cartId.value = id
                addLog("✅ [1/5] cartId = $id")

                addLog("📄 [2/5] تحميل صفحة السلة...")
                RetrofitClient.api.getCartDetails(
                    cartId = id,
                    pageIdentity = "FlightResults",
                    headers = buildHeaders("3", referer = "https://www.saudia.com/ar-SA/booking")
                ).let {
                    saveCookiesFromResponse(it)
                    addLog("✅ [2/5] FlightResults: HTTP ${it.code()}")
                }

                addLog("👤 [3/5] تحميل صفحة المسافرين...")
                val passengerResp = RetrofitClient.api.getCartDetails(
                    cartId = id,
                    pageIdentity = "PassengerDetails",
                    headers = buildHeaders("3", referer = "https://www.saudia.com/ar-SA/booking")
                )
                saveCookiesFromResponse(passengerResp)
                if (passengerResp.isSuccessful) {
                    _rawCartResponse.value = passengerResp.body()?.string() ?: ""
                    addLog("✅ [3/5] PassengerDetails: HTTP ${passengerResp.code()}")
                }

                addLog("🎫 [4/5] تحميل الخدمات...")
                RetrofitClient.api.getServicesByCart(
                    cartId = id,
                    pageIdentity = "PassengerDetails",
                    headers = buildHeaders("3", referer = "https://www.saudia.com/ar-SA/booking/passengerDetails")
                ).let {
                    saveCookiesFromResponse(it)
                    addLog("✅ [4/5] الخدمات: HTTP ${it.code()}")
                }

                addLog("🛂 [5/5] فحص GACA...")
                RetrofitClient.api.getGacaMandate(
                    headers = buildHeaders("1", isWebEndpoint = true, referer = "https://www.saudia.com/ar-SA/booking/passengerDetails"),
                    request = GacaMandateRequest()
                ).let {
                    saveCookiesFromResponse(it)
                    addLog("✅ [5/5] GACA: HTTP ${it.code()}")
                }

                addLog("═".repeat(40))
                addLog("🎯 جاهز للتأكيد (PATCH).")
            } catch (e: Exception) {
                addLog("❌ استثناء: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  🧪 اختبار 3 متغيرات RSA + headers بدون x-v-token
    // ═══════════════════════════════════════════════════════════
    fun sendEncryptedPassengerPayload() {
        val cartId = _cartId.value
        if (cartId.isNullOrEmpty()) {
            addLog("❌ لا يوجد cartId!")
            return
        }

        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val rsaPem = prefs.getString("rsa_public_key", null)
        if (rsaPem.isNullOrEmpty()) {
            addLog("❌ لا يوجد مفتاح محفوظ")
            return
        }

        addLog("🔑 المفتاح المحفوظ (${rsaPem.length} حرف): ${rsaPem.take(60)}...")

        viewModelScope.launch {
            _passengerConfirmResponse.value = null
            addLog("═══════════════════════════════════════")
            addLog("🧪 اختبار 3 متغيرات (بدون x-v-token + bodyid)")

            val plainJson = """{"passengerTypeCode":"ADT"}"""

            val rsaModes = listOf(
                HybridEncryptor.RsaMode.PKCS1,
                HybridEncryptor.RsaMode.OAEP_SHA256_MGF1_SHA256,
                HybridEncryptor.RsaMode.OAEP_SHA256_MGF1_SHA1,
            )

            for (mode in rsaModes) {
                try {
                    addLog("─── ${mode.name} ───")
                    val (keyB64, bodyHex) = HybridEncryptor.encrypt(plainJson, rsaPem, mode)
                    addLog("key=${keyB64.length}ch, body=${bodyHex.length}ch")

                    val payload = """{"key":"$keyB64","body":"$bodyHex"}"""
                    val requestBody = payload.toRequestBody("application/json".toMediaType())

                    // استخدام headers بدون x-v-token
                    val headers = buildHeaders(
                        "2",
                        isWebEndpoint = false,
                        referer = "https://www.saudia.com/ar-SA/booking/passengerDetails"
                    )

                    val resp = RetrofitClient.api.updateTravelerRaw(
                        cartId = cartId,
                        travelerId = "SKH-1-EXT",
                        headers = headers,
                        request = requestBody
                    )
                    saveCookiesFromResponse(resp)
                    val code = resp.code()
                    val respBody = resp.body()?.string() ?: resp.errorBody()?.string() ?: ""

                    addLog("📡 ${mode.name} → HTTP $code")
                    val displayBody = if (respBody.isEmpty()) "(empty)" else respBody.take(200)
                    _passengerConfirmResponse.value = "${mode.name} → HTTP $code\n\n$displayBody"

                    if (code == 200) {
                        addLog("🎉🎉🎉 نجح! المتغير: ${mode.name}")
                        return@launch
                    }
                } catch (e: Exception) {
                    addLog("❌ ${mode.name}: ${e.message}")
                }
            }
            addLog("⚠️ كل المتغيرات فشلت")
        }
    }

    fun addLog(message: String) {
        val curr = _searchLogs.value.toMutableList()
        curr.add(0, message)
        _searchLogs.value = curr
    }
}
