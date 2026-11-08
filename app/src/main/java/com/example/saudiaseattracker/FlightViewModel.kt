package com.example.saudiaseattracker

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import retrofit2.Response
import java.util.UUID

// ═══════════════════════════════════════════════════════════
//   نماذج البيانات المساعدة
// ═══════════════════════════════════════════════════════════

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
    IDLE,
    SEARCHING,
    FOUND,
    ERROR,
    TOKEN_EXPIRED
}

// ═══════════════════════════════════════════════════════════
//   ViewModel
// ═══════════════════════════════════════════════════════════

class FlightViewModel(application: Application) : AndroidViewModel(application) {

    // ── Preferences ────────────────────────────────────────
    private val prefs = application.getSharedPreferences(
        "saudia_prefs", Context.MODE_PRIVATE
    )

    private val gson = Gson()

    // ═══════════════════════════════════════════════════════
    //   StateFlows الأساسية
    // ═══════════════════════════════════════════════════════

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

    // ── حالات جديدة للتدفق التلقائي ────────────────────────
    private val _seatsReady = MutableStateFlow(false)
    val seatsReady: StateFlow<Boolean> = _seatsReady.asStateFlow()

    private val _paymentReady = MutableStateFlow(false)
    val paymentReady: StateFlow<Boolean> = _paymentReady.asStateFlow()

    // ── حالة البحث ─────────────────────────────────────────
    private var searchJob: Job? = null

    // ═══════════════════════════════════════════════════════
    //   ثوابت
    // ═══════════════════════════════════════════════════════

    companion object {
        private const val TAG = "SaudiaVM"
        private const val PREFS_AUTH       = "auth_token"
        private const val PREFS_XD_TOKEN   = "xd_token"
        private const val PREFS_SESSION_ID = "session_id"
        private const val PREFS_USER_AGENT = "user_agent"
        private const val PREFS_COOKIES    = "dynamic_cookies"

        private const val GUEST_OFFICE_ID  = "JEDSV08MA"
        private const val LANG             = "ar-SA"
        private const val PLATFORM         = "Web"
        private const val VERSION          = "27.0"
        private const val CHANNEL          = "MOBILE_WEB"
        private const val BROWSER_ID       = "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9"

        private const val SEARCH_INTERVAL_MS = 15_000L

        // بيانات المسافر التجريبية
        const val TEST_DOCUMENT_NUMBER = "1071580342"
        const val TEST_DATE_OF_BIRTH   = "1991-05-10"
        const val FIRST_TRAVELER_ID    = "SKH-1-EXT"

        // ⚠️ القيم المشفّرة من HAR (2026-11-08) للمسافر التجريبي
        private const val HAR_ENCRYPTED_KEY =
            "XkemQfguUodRJM/FrIbiU9QYGL9U7F2b0MWXyOO1FRa3VtlFelkhRvc7iP9DE34fSkJDlIP5ji6v7PpN21gmwaJR//vfeBTHums1dLkxIUDCz8DivhP2IbZqhFgK/9IADtFED6SJkdJJJeFaSmcIxNFDv5kTAj6lXK/JWu2zkRsjz0elXt6WKqetn5XpI7Ls09aUeKETCdarXRSwsLx/6kMo9F1NVWQHhT4CsoAo/1ad3BqxERmi4HIaT7B4osZhGETqZKgSCAg2G4keaIPnhOrifPzQtNMnBInoQXU4yfI1516rgcdPaNRPMbpkGjrB+4HBtj5oW7dyF56DXH67dUT/5z7Tcu8i7rHQMslo9UAbrtwpXdex9mJng0rZzFWSxd7Ed0RGNJRuQ8Xafjk1aA3FVYw6f01BP92reYJatj2ZeAoOfcL7GdDZ7EzdKNFgNzMG41MbLG5GzZXGPRRYMceULb5w3G6UcjSJBwLBgb6WSZH6M4lZH6UHDwgQ5SwCsqpHCHd4SuyK8VLHTUVRHjf9fowM08cb1zu21IygkkxRNIeXBofWAhqfr54jTlLW4g74bTlsbRHuZ76hmVZSz6ZxvXSs75S3f2giZ5eP9Kf0V/lYN0ONM8IFixIOALiO+7+n6nS0LYOEl7v8bM2xHfpEKPOChTZp0eHlOt5WvVw="

        private const val HAR_ENCRYPTED_BODY =
            "73371f7a85ee58fad676d0d3e12ac45bf28470a88e7eb50e6089f1585cbcb6c39e97ac3cd9a03a40fdbdb5931e7bf5975afd8f2e224db709e99e3dad5d8a673a9b38d09826f65218df35fc61516f3fa7f6ac1941eba92b826583f281979975562c82c05bae4d6128d8989f42fde23a0b984c0d6d4a4d092d9d5580e2e1797ae83eb1f317277908c0eb4b60ca7149901858236e0cfd419116834cd7fe3817bba8b7ce7d775f607eea3525ca110580e0356cf034d08829d5dee5c59df2cde5011724b5e232f1795a87ae0efbd121122d138925264a1a9c19449a681757c215cc1cb372ccbc1d0c2110093d308a8974c0304ee8c2f7747775eb13438eec0fc5e0ff294d08a47563560bf98339b980af61ca354daacd8d3abe83ee075129d5bc34304479e792b95b472eba68f6c83e5ac49c0c7f4cf56dc5b805f6e40085cd5a72bbb04a1bb4d99a8526d69f96bf122858bcd72c0688a1e78b49a42a30b018be86738cf73731c5139b0a9be873498e40ffbdb4d4645cb25e4b385e748d35ffb0bbec1d20ff59f1e68f4bd5c1196793d5b57f75c6740b37954a6b6f63fe48b6bd107fe00165a3ed79a28d30a180ba6c6d357d32965d8ba0dc0b602888c202578f06c19b8495056d2744d10569d9caa4b5179961151f01ee3debf7237235e65d898b0fc1cfc8d7f9582af51ee558598716886a"
    }

    // ═══════════════════════════════════════════════════════
    //   حفظ / استرجاع البيانات الحساسة
    // ═══════════════════════════════════════════════════════

    fun saveTokens(
        authToken: String,
        xdToken: String,
        sessionId: String,
        userAgent: String
    ) {
        prefs.edit()
            .putString(PREFS_AUTH, authToken)
            .putString(PREFS_XD_TOKEN, xdToken)
            .putString(PREFS_SESSION_ID, sessionId)
            .putString(PREFS_USER_AGENT, userAgent)
            .apply()
        addLog("💾 تم حفظ التوكن والجلسة")
    }

    fun getSavedAuthToken(): String? = prefs.getString(PREFS_AUTH, null)
    fun getSavedXDToken(): String? = prefs.getString(PREFS_XD_TOKEN, null)
    fun getSavedSessionId(): String? = prefs.getString(PREFS_SESSION_ID, null)
    fun getSavedUserAgent(): String? = prefs.getString(PREFS_USER_AGENT, null)

    private fun saveCookiesFromResponse(response: Response<*>) {
        val headers = response.headers().values("Set-Cookie")
        if (headers.isEmpty()) return
        val existing = prefs.getString(PREFS_COOKIES, "").orEmpty()
        val map = existing.split("; ")
            .filter { it.contains("=") }
            .associate {
                val idx = it.indexOf("=")
                it.substring(0, idx) to it.substring(idx + 1)
            }
            .toMutableMap()
        headers.forEach { raw ->
            val firstPart = raw.substringBefore(";")
            val idx = firstPart.indexOf("=")
            if (idx > 0) {
                val name = firstPart.substring(0, idx).trim()
                val value = firstPart.substring(idx + 1).trim()
                map[name] = value
            }
        }
        val merged = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        prefs.edit().putString(PREFS_COOKIES, merged).apply()
    }

    private fun getSavedDynamicCookies(): String =
        prefs.getString(PREFS_COOKIES, "").orEmpty()

    // ═══════════════════════════════════════════════════════
    //   بناء الترويسات ديناميكياً
    // ═══════════════════════════════════════════════════════

    fun buildHeaders(apiVersion: String): Map<String, String> {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId  = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        val ddParent = (0..Long.MAX_VALUE).random().toString()
        val ddTrace  = (0..Long.MAX_VALUE).random().toString()

        val headers = mutableMapOf(
            "Authorization"    to "Bearer ${getSavedAuthToken().orEmpty()}",
            "x-d-token"        to getSavedXDToken().orEmpty(),
            "saa-session-id"   to getSavedSessionId().orEmpty(),
            "x-api-version"    to apiVersion,
            "platform"         to PLATFORM,
            "version"          to VERSION,
            "channel"          to CHANNEL,
            "browserid"        to BROWSER_ID,
            "Origin"           to "https://www.saudia.com",
            "Referer"          to "https://www.saudia.com/",
            "User-Agent"       to (getSavedUserAgent()
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                   "AppleWebKit/537.36 (KHTML, like Gecko) " +
                   "Chrome/120.0.0.0 Safari/537.36"),
            "Accept"           to "application/json",
            "Accept-Language"  to "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "Content-Type"     to "application/json",
            "request-id"       to "|$traceId.$spanId",
            "traceparent"      to "00-$traceId-$spanId-01",
            "x-datadog-origin" to "rum",
            "x-datadog-parent-id" to ddParent,
            "x-datadog-sampling-priority" to "1",
            "x-datadog-trace-id" to ddTrace
        )
        val cookies = getSavedDynamicCookies()
        if (cookies.isNotBlank()) headers["Cookie"] = cookies
        return headers
    }

    // ═══════════════════════════════════════════════════════
    //   اللوج
    // ═══════════════════════════════════════════════════════

    fun addLog(message: String) {
        Log.d(TAG, message)
        _searchLogs.value = _searchLogs.value + message
    }

    fun clearLogs() {
        _searchLogs.value = emptyList()
    }

    // ═══════════════════════════════════════════════════════
    //   البحث الدوري عن الرحلات
    // ═══════════════════════════════════════════════════════

    fun startSearchingFlight(
        flightNumber: String,
        origin: String = "JED",
        destination: String = "RUH",
        date: String = "2026-11-15T00:00:00"
    ) {
        searchJob?.cancel()
        _uiState.value = SearchState.SEARCHING
        _foundFlight.value = null
        addLog("🔍 بدء البحث عن الرحلة $flightNumber")
        addLog("   من $origin إلى $destination بتاريخ $date")

        searchJob = viewModelScope.launch {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    addLog("─── محاولة #$attempt ───")

                    val request = SaudiaSearchRequest(
                        airBoundsSearchInputs = AirBoundsSearchInputs(
                            itineraries = listOf(
                                Itinerary(
                                    originLocationCode = origin,
                                    destinationLocationCode = destination,
                                    departureDateTime = date
                                )
                            ),
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
                    saveCookiesFromResponse(resp)

                    when {
                        resp.code() == 401 -> {
                            addLog("🔒 التوكن انتهت — يجب إعادة تسجيل الدخول")
                            _uiState.value = SearchState.TOKEN_EXPIRED
                            return@launch
                        }
                        resp.code() == 403 -> {
                            addLog("🚫 403 — Imperva رفض الطلب (VPN أو انتظر)")
                        }
                        !resp.isSuccessful -> {
                            addLog("❌ فشل HTTP ${resp.code()}")
                        }
                        else -> {
                            val body = resp.body()
                            val found = parseSearchResponse(body, flightNumber)
                            if (found != null) {
                                _foundFlight.value = found
                                _uiState.value = SearchState.FOUND
                                addLog("✅ وُجد مقعد! السعر: ${found.price} SAR — ${found.fareFamily}")
                                return@launch
                            } else {
                                addLog("⏳ لا يوجد مقعد متاح بعد — سأعيد بعد ${SEARCH_INTERVAL_MS / 1000}ث")
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
        searchJob?.cancel()
        searchJob = null
        _uiState.value = SearchState.IDLE
        addLog("⏹️ تم إيقاف البحث")
    }

    private fun parseSearchResponse(
        response: SaudiaResponse?,
        flightNumber: String
    ): FoundFlightDetails? {
        val groups = response?.data?.airBoundGroups ?: return null
        for (group in groups) {
            val bounds = group.airBounds ?: continue
            for (bound in bounds) {
                val statusValue = bound.status?.value
                val isSoldOut = statusValue == "soldOut"
                if (isSoldOut) continue
                val price = bound.airOffer?.totalPrice?.value ?: continue
                val fareFamily = bound.fareFamilyCode ?: "UNKNOWN"
                val airBoundId = bound.airBoundId ?: continue
                val origin = group.boundDetails?.origin ?: ""
                val destination = group.boundDetails?.destination ?: ""
                return FoundFlightDetails(
                    flightNumber = flightNumber,
                    date = "",
                    origin = origin,
                    destination = destination,
                    price = price / 100.0,
                    fareFamily = fareFamily,
                    airBoundId = airBoundId
                )
            }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════
    //   إنشاء السلة وجلب تفاصيلها (الصفحة السابقة — المسافرون)
    // ═══════════════════════════════════════════════════════

    fun createCartAndProceedToPassenger() {
        viewModelScope.launch {
            val found = _foundFlight.value ?: run {
                addLog("❌ لا توجد رحلة محددة")
                return@launch
            }

            try {
                addLog("🛒 [1/2] إنشاء السلة...")
                val createResp = RetrofitClient.api.createCart(
                    guestOfficeId = GUEST_OFFICE_ID,
                    lang = LANG,
                    headers = buildHeaders("2"),
                    request = CreateCartRequest(airBoundIds = listOf(found.airBoundId))
                )
                saveCookiesFromResponse(createResp)

                if (!createResp.isSuccessful) {
                    addLog("❌ فشل إنشاء السلة: HTTP ${createResp.code()}")
                    addLog("   ${createResp.errorBody()?.string()}")
                    return@launch
                }

                val raw = createResp.body()?.string().orEmpty()
                _rawCartResponse.value = raw
                addLog("📦 استجابة السلة: $raw")

                val id = extractCartId(raw)
                if (id == null) {
                    addLog("❌ تعذّر استخراج cartId")
                    return@launch
                }
                _cartId.value = id
                addLog("✅ [1/2] cartId = $id")

                addLog("📥 [2/2] جلب تفاصيل السلة...")
                val detailsResp = RetrofitClient.api.getCartDetails(
                    cartId = id,
                    headers = buildHeaders("3")
                )
                saveCookiesFromResponse(detailsResp)
                if (detailsResp.isSuccessful) {
                    _rawCartResponse.value = detailsResp.body()?.string().orEmpty()
                    addLog("✅ [2/2] تم")
                } else {
                    addLog("⚠️ فشل جلب التفاصيل: HTTP ${detailsResp.code()}")
                }
            } catch (e: Exception) {
                addLog("❌ استثناء: ${e.message}")
            }
        }
    }

    private fun extractCartId(rawJson: String): String? {
        return try {
            val map = gson.fromJson(rawJson, Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val data = map["data"] as? Map<String, Any?>
            data?.get("id") as? String
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════
    //   المسافرون → المقاعد (الطلبات 1..4 من HAR)
    // ═══════════════════════════════════════════════════════

    /**
     * يبدأ تلقائياً عند فتح PassengersScreen (بعد توفر cartId).
     * عند النجاح: يرفع seatsReady = true → ينتقل التطبيق تلقائياً إلى SeatsScreen.
     */
    fun submitPassengersAndGoToSeats(
        documentNumber: String = TEST_DOCUMENT_NUMBER,
        dateOfBirth: String = TEST_DATE_OF_BIRTH
    ) {
        viewModelScope.launch {
            val currentCartId = _cartId.value ?: run {
                addLog("❌ لا يوجد cartId — لا يمكن إرسال المسافر")
                return@launch
            }
            val travelerId = FIRST_TRAVELER_ID

            // ── [1/4] ELM Verification ─────────────────────
            try {
                addLog("🔐 [1/4] تحقق الهوية ELM...")
                val resp = RetrofitClient.api.verifyElmProfile(
                    guestOfficeId = GUEST_OFFICE_ID,
                    lang = LANG,
                    headers = buildHeaders("2"),
                    request = ElmVerificationRequest(
                        documentNumber = documentNumber,
                        dateOfBirth = dateOfBirth
                    )
                )
                saveCookiesFromResponse(resp)
                if (!resp.isSuccessful) {
                    addLog("❌ [1/4] HTTP ${resp.code()} — ${resp.errorBody()?.string()}")
                    return@launch
                }
                addLog("✅ [1/4] تم التحقق من الهوية")
            } catch (e: Exception) {
                addLog("❌ [1/4] استثناء: ${e.message}")
                return@launch
            }

            // ── [2/4] Get Cart Extensions ──────────────────
            try {
                addLog("📥 [2/4] جلب ملحقات السلة...")
                val resp = RetrofitClient.api.getCartExtensions(
                    cartId = currentCartId,
                    guestOfficeId = GUEST_OFFICE_ID,
                    lang = LANG,
                    headers = buildHeaders("2")
                )
                saveCookiesFromResponse(resp)
                if (!resp.isSuccessful) {
                    addLog("❌ [2/4] HTTP ${resp.code()}")
                    return@launch
                }
                addLog("✅ [2/4] تم")
            } catch (e: Exception) {
                addLog("❌ [2/4] استثناء: ${e.message}")
                return@launch
            }

            // ── [3/4] Patch Cart Extensions (YQENSTATUS) ───
            try {
                addLog("🩺 [3/4] تعيين حالة الصحة YQENSTATUS...")
                val innerJson = """{"status":"VERIFIED","healthCheckStatus":"NO_RESPONSE"}"""
                val contentB64 = Base64.encodeToString(
                    innerJson.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                val resp = RetrofitClient.api.patchCartExtensions(
                    cartId = currentCartId,
                    guestOfficeId = GUEST_OFFICE_ID,
                    lang = LANG,
                    headers = buildHeaders("2"),
                    request = listOf(
                        CartExtensionRequest(
                            content = contentB64,
                            travelerId = travelerId
                        )
                    )
                )
                saveCookiesFromResponse(resp)
                if (!resp.isSuccessful) {
                    addLog("❌ [3/4] HTTP ${resp.code()} — ${resp.errorBody()?.string()}")
                    return@launch
                }
                addLog("✅ [3/4] تم")
            } catch (e: Exception) {
                addLog("❌ [3/4] استثناء: ${e.message}")
                return@launch
            }

            // ── [4/4] Patch Traveler Web (بيانات المسافر) ──
            try {
                addLog("👤 [4/4] إرسال بيانات المسافر (مشفّرة)...")
                val resp = RetrofitClient.api.patchTravelerWeb(
                    cartId = currentCartId,
                    travelerId = travelerId,
                    guestOfficeId = GUEST_OFFICE_ID,
                    lang = LANG,
                    headers = buildHeaders("2"),
                    request = EncryptedTravelerRequest(
                        key = HAR_ENCRYPTED_KEY,
                        body = HAR_ENCRYPTED_BODY
                    )
                )
                saveCookiesFromResponse(resp)
                if (!resp.isSuccessful) {
                    addLog("❌ [4/4] HTTP ${resp.code()} — ${resp.errorBody()?.string()}")
                    return@launch
                }
                addLog("✅ [4/4] المسافر مقبول — الانتقال للمقاعد")
                _seatsReady.value = true
            } catch (e: Exception) {
                addLog("❌ [4/4] استثناء: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //   المقاعد → الدفع (بانتظار HAR التالي)
    // ═══════════════════════════════════════════════════════

    /**
     * يبدأ تلقائياً عند فتح SeatsScreen.
     * عند النجاح: يرفع paymentReady = true → ينتقل التطبيق تلقائياً إلى PaymentScreen.
     *
     * ⚠️ مؤقتاً: يكتفي باللوج ويرفع paymentReady لاختبار التدفق.
     *    بعد التقاط HAR صفحة المقاعد:
     *      1) GET  shopping/carts/{cartId}/seat-maps
     *      2) PATCH shopping/carts/{cartId}/travelers/...
     *      3) _paymentReady.value = true
     */
    fun selectSeatAndProceedToPayment() {
        viewModelScope.launch {
            val currentCartId = _cartId.value ?: run {
                addLog("❌ لا يوجد cartId — لا يمكن اختيار مقعد")
                return@launch
            }
            addLog("🪑 [Seats] cartId = $currentCartId")
            addLog("⏸️ [Seats] في انتظار طلبات المقاعد من HAR")

            // TODO: نفّذ طلبات المقاعد هنا. مثال مؤقت:
            // val resp = RetrofitClient.api.getSeatMap(...)
            // ... PATCH assign seat ...
            // _paymentReady.value = true

            // ⚠️ هذه الأسطر مؤقتة لاختبار التدفق فقط — احذفها بعد إضافة المنطق الحقيقي
            delay(500)
            addLog("✅ [Seats] جاهز للدفع (مؤقت)")
            _paymentReady.value = true
        }
    }
}
