package com.example.saudiaseattracker

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

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

    private val prefs = application.getSharedPreferences("saudia_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _uiState = MutableStateFlow(SearchState.IDLE)
    val uiState: StateFlow<SearchState> = _uiState.asStateFlow()

    private val _searchLogs = MutableStateFlow<List<String>>(emptyList())
    val searchLogs: StateFlow<List<String>> = _searchLogs.asStateFlow()

    private val _foundFlight = MutableStateFlow<FoundFlightDetails?>(null)
    val foundFlight: StateFlow<FoundFlightDetails?> = _foundFlight.asStateFlow()

    private val _cartId = MutableStateFlow<String?>(null)
    val cartId: StateFlow<String?> = _cartId.asStateFlow()

    private val _rawCartResponse = MutableStateFlow<String>("")
    val rawCartResponse: StateFlow<String> = _rawCartResponse.asStateFlow()

    var searchOrigin by mutableStateOf("JED")
    var searchDestination by mutableStateOf("RUH")
    var searchDate by mutableStateOf("2026-11-15")

    private var searchJob: Job? = null

    companion object {
        private const val TAG = "SaudiaVM"
        private const val PREFS_AUTH       = "auth_token"
        private const val PREFS_XD_TOKEN   = "xd_token"
        private const val PREFS_SESSION_ID = "session_id"
        private const val PREFS_USER_AGENT = "user_agent"

        private const val GUEST_OFFICE_ID  = "JEDSV08MA"
        private const val LANG             = "ar-SA"
        private const val PLATFORM         = "Web"
        private const val VERSION          = "27.0"
        private const val CHANNEL          = "MOBILE_WEB"
        private const val BROWSER_ID       = "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9"
        private const val SEARCH_INTERVAL_MS = 15_000L

        private const val WINDOWS_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    // ═══════════════════════════════════════════════════════
    //   حفظ / استرجاع (التوكن يُحفظ كما هو مع Bearer)
    // ═══════════════════════════════════════════════════════

    fun saveTokens(authToken: String, xdToken: String, sessionId: String, userAgent: String) {
        prefs.edit()
            .putString(PREFS_AUTH, authToken)
            .putString(PREFS_XD_TOKEN, xdToken)
            .putString(PREFS_SESSION_ID, sessionId)
            .putString(PREFS_USER_AGENT, userAgent)
            .apply()
        addLog("💾 حفظ التوكن (${authToken.take(30)}...)")
    }

    fun getSavedAuthToken(): String? = prefs.getString(PREFS_AUTH, null)
    fun getSavedXDToken(): String? = prefs.getString(PREFS_XD_TOKEN, null)
    fun getSavedSessionId(): String? = prefs.getString(PREFS_SESSION_ID, null)
    fun getSavedUserAgent(): String? = prefs.getString(PREFS_USER_AGENT, null)

    // ═══════════════════════════════════════════════════════
    //   الترويسات — بدون Cookie
    // ═══════════════════════════════════════════════════════

    fun buildHeaders(apiVersion: String): Map<String, String> {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId  = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

        return mutableMapOf(
            // ✅ التوكن محفوظ مع "Bearer " — نرسله كما هو
            "Authorization"    to getSavedAuthToken().orEmpty(),
            "x-d-token"        to getSavedXDToken().orEmpty(),
            "saa-session-id"   to getSavedSessionId().orEmpty(),
            "x-api-version"    to apiVersion,
            "platform"         to PLATFORM,
            "version"          to VERSION,
            "channel"          to CHANNEL,
            "browserid"        to BROWSER_ID,
            "Origin"           to "https://www.saudia.com",
            "Referer"          to "https://www.saudia.com/",
            "User-Agent"       to WINDOWS_UA,
            "Accept"           to "application/json",
            "Accept-Language"  to "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "Content-Type"     to "application/json",
            "request-id"       to "|$traceId.$spanId",
            "traceparent"      to "00-$traceId-$spanId-01",
            "x-datadog-origin" to "rum",
            "x-datadog-parent-id" to ThreadLocalRandom.current().nextLong().toString(),
            "x-datadog-sampling-priority" to "1",
            "x-datadog-trace-id" to ThreadLocalRandom.current().nextLong().toString()
        )
    }

    fun addLog(message: String) {
        Log.d(TAG, message)
        _searchLogs.value = _searchLogs.value + message
    }

    fun clearLogs() { _searchLogs.value = emptyList() }

    // ═══════════════════════════════════════════════════════
    //   البحث
    // ═══════════════════════════════════════════════════════

    fun startSearchingFlight(
        flightNumber: String,
        origin: String = searchOrigin,
        destination: String = searchDestination,
        date: String = "${searchDate}T00:00:00"
    ) {
        searchJob?.cancel()
        _uiState.value = SearchState.SEARCHING
        _foundFlight.value = null
        addLog("🔍 البحث عن $flightNumber ($origin → $destination @ $date)")

        searchJob = viewModelScope.launch {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    addLog("─── محاولة #$attempt ───")
                    val request = SaudiaSearchRequest(
                        airBoundsSearchInputs = AirBoundsSearchInputs(
                            itineraries = listOf(Itinerary(origin, destination, date)),
                            travelers = listOf(Traveler()),
                            searchPreferences = SearchPreferences()
                        ),
                        offerCodeAvailabilityInput = OfferCodeAvailabilityInput()
                    )
                    val resp = RetrofitClient.api.searchAirBounds(
                        guestOfficeId = GUEST_OFFICE_ID,
                        lang = LANG,
                        headers = buildHeaders("4"),
                        request = request
                    )
                    when {
                        resp.code() == 401 -> {
                            addLog("🔒 401 — التوكن انتهت")
                            _uiState.value = SearchState.TOKEN_EXPIRED
                            return@launch
                        }
                        resp.code() == 403 -> addLog("🚫 403 Imperva")
                        !resp.isSuccessful -> addLog("❌ HTTP ${resp.code()}")
                        else -> {
                            val found = parseSearchResponse(resp.body(), flightNumber)
                            if (found != null) {
                                _foundFlight.value = found
                                _uiState.value = SearchState.FOUND
                                addLog("✅ وُجد مقعد! ${found.price} SAR — ${found.fareFamily}")
                                return@launch
                            } else {
                                addLog("⏳ لا يوجد مقعد — إعادة بعد ${SEARCH_INTERVAL_MS / 1000}ث")
                            }
                        }
                    }
                } catch (e: Exception) {
                    addLog("❌ استثناء: ${e.message}")
                }
                delay(SEARCH_INTERVAL_MS)
            }
        }
    }

    fun stopSearch() {
        searchJob?.cancel(); searchJob = null
        _uiState.value = SearchState.IDLE
        addLog("⏹️ تم الإيقاف")
    }

    private fun parseSearchResponse(response: SaudiaResponse?, flightNumber: String): FoundFlightDetails? {
        val groups = response?.data?.airBoundGroups ?: return null
        for (group in groups) {
            for (bound in group.airBounds ?: continue) {
                if (bound.status?.value == "soldOut") continue
                val price = bound.airOffer?.totalPrice?.value ?: continue
                val airBoundId = bound.airBoundId ?: continue
                return FoundFlightDetails(
                    flightNumber = flightNumber,
                    date = "",
                    origin = group.boundDetails?.origin ?: "",
                    destination = group.boundDetails?.destination ?: "",
                    price = price / 100.0,
                    fareFamily = bound.fareFamilyCode ?: "UNKNOWN",
                    airBoundId = airBoundId
                )
            }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════
    //   السلة
    // ═══════════════════════════════════════════════════════

    fun createCartAndProceedToPassenger() {
        viewModelScope.launch {
            val found = _foundFlight.value ?: run {
                addLog("❌ لا توجد رحلة"); return@launch
            }
            try {
                addLog("🛒 [1/2] إنشاء السلة...")
                val resp = RetrofitClient.api.createCart(
                    guestOfficeId = GUEST_OFFICE_ID,
                    lang = LANG,
                    headers = buildHeaders("2"),
                    request = CreateCartRequest(listOf(found.airBoundId))
                )
                if (!resp.isSuccessful) {
                    addLog("❌ HTTP ${resp.code()} — ${resp.errorBody()?.string()}")
                    return@launch
                }
                val raw = resp.body()?.string().orEmpty()
                _rawCartResponse.value = raw
                val id = extractCartId(raw) ?: run {
                    addLog("❌ تعذّر استخراج cartId"); return@launch
                }
                _cartId.value = id
                addLog("✅ [1/2] cartId = $id")

                addLog("📥 [2/2] جلب التفاصيل...")
                val d = RetrofitClient.api.getCartDetails(cartId = id, headers = buildHeaders("3"))
                if (d.isSuccessful) {
                    _rawCartResponse.value = d.body()?.string().orEmpty()
                    addLog("✅ [2/2] تم")
                } else addLog("⚠️ HTTP ${d.code()}")
            } catch (e: Exception) { addLog("❌ ${e.message}") }
        }
    }

    private fun extractCartId(rawJson: String): String? = try {
        @Suppress("UNCHECKED_CAST")
        val map = gson.fromJson(rawJson, Map::class.java) as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        (map?.get("data") as? Map<String, Any?>)?.get("id") as? String
    } catch (e: Exception) { null }
}
