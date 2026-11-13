package com.example.saudiaseattracker

import android.app.Application
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Response
import java.util.UUID
import kotlin.random.Random

data class PassengerData(
    val title: String = "Mr", val firstName: String = "Ebrahim", val lastName: String = "Otudi",
    val dateOfBirth: String = "1990-02-18", val nationalId: String = "1065314187",
    val nationality: String = "SA", val gender: String = "male",
    val email: String = "aletwade@gmail.com", val phone: String = "543309909"
) {
    fun isValid(): Boolean = firstName.isNotBlank() && lastName.isNotBlank() && dateOfBirth.isNotBlank() && nationalId.length == 10 && email.contains("@") && phone.length >= 9
}

data class FlightData(
    val from: String = "JED",
    val to: String = "AHB",
    val date: String = "2026-11-17",
    val departureTime: String = "06:40",
    val cabinClass: String = FareFamilies.ALL_HOSPITALITY,
    val directOnly: Boolean = true
) {
    fun isValid(): Boolean =
        from.length == 3 && to.length == 3 &&
        date.isNotBlank() &&
        (departureTime.isBlank() || normalizeTime(departureTime).isNotBlank())

    companion object {
        fun normalizeTime(input: String): String {
            val t = input.trim().replace(":", "")
            return if (t.length == 4 && t.all { it.isDigit() })
                "${t.substring(0, 2)}:${t.substring(2, 4)}"
            else input.trim()
        }
    }
}

data class FoundFlightDetails(
    val flightNumber: String, val date: String, val origin: String,
    val destination: String, val price: Double, val fareFamily: String, val airBoundId: String
)

enum class StepStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }
data class BookingStep(val id: String, val title: String, val status: StepStatus = StepStatus.PENDING, val detail: String = "")

class FlightViewModel(application: Application) : AndroidViewModel(application) {

    private var activeJwt = ""
    private var activeCookie: String? = null
    private var activeSaaSessionId = ""
    private var activeXdToken = ""
    private var jwtExpiryMs: Long = 0L

    private var autoLoopJob: Job? = null

    private val hyperSession: HyperSessionManager by lazy {
        HyperSessionManager(
            context = getApplication<Application>(), rawApiKey = BuildConfig.HYPER_API_KEY,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36",
            acceptLanguage = "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    var passengerData by mutableStateOf(PassengerData())
    var flightData by mutableStateOf(FlightData())
    var monitoringMode by mutableStateOf(false)

    private val _steps = MutableStateFlow(listOf(
        BookingStep("search", "البحث عن الرحلة"), BookingStep("cart", "إنشاء سلة الحجز"),
        BookingStep("passenger", "تأكيد بيانات المسافر"), BookingStep("extension", "حفظ التفعيل (YQENSTATUS)"),
        BookingStep("contacts", "حفظ بيانات الاتصال"), BookingStep("order", "إنشاء الطلب (PNR + SADAD)"),
        BookingStep("payment", "إرسال طلب السداد")
    ))
    val steps: StateFlow<List<BookingStep>> = _steps

    private val _searchLogs = MutableStateFlow<List<String>>(emptyList())
    val searchLogs: StateFlow<List<String>> = _searchLogs

    private val _searchResponseText = MutableStateFlow("بدء البحث...")
    val searchResponseText: StateFlow<String> = _searchResponseText

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _finalPnr = MutableStateFlow("")
    val finalPnr: StateFlow<String> = _finalPnr

    private val _finalSadad = MutableStateFlow("")
    val finalSadad: StateFlow<String> = _finalSadad

    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady

    private val _sessionStatus = MutableStateFlow("⏳ جاري تجهيز جلسة Hyper...")
    val sessionStatus: StateFlow<String> = _sessionStatus

    private val _debugEntries = MutableStateFlow<List<DebugEntry>>(emptyList())
    val debugEntries: StateFlow<List<DebugEntry>> = _debugEntries

    private val _autoLoopActive = MutableStateFlow(false)
    val autoLoopActive: StateFlow<Boolean> = _autoLoopActive
    private val _needsWebViewRefresh = MutableStateFlow(false)
    val needsWebViewRefresh: StateFlow<Boolean> = _needsWebViewRefresh
    private val _autoLoopLastCycle = MutableStateFlow("")
    val autoLoopLastCycle: StateFlow<String> = _autoLoopLastCycle
    private val _autoCaptureMode = MutableStateFlow(false)
    val autoCaptureMode: StateFlow<Boolean> = _autoCaptureMode

    fun pushDebug(entry: DebugEntry) { DebugBus.emit(entry) }
    fun clearDebugLog() { _debugEntries.value = emptyList() }
    fun isSidReady(): Boolean = activeSaaSessionId.isNotBlank()

    init {
        viewModelScope.launch {
            DebugBus.flow.collect { entry ->
                val curr = _debugEntries.value.toMutableList()
                curr.add(0, entry)
                _debugEntries.value = curr.take(300)
            }
        }
        viewModelScope.launch {
            try {
                addLog("🔄 تهيئة جلسة Hyper...")
                withContext(Dispatchers.IO) { hyperSession.ensureFreshToken() }
                markSessionReady()
                addLog("✅ جلسة Hyper جاهزة — فتح WebView تلقائياً...")
                _autoCaptureMode.value = true
                _needsWebViewRefresh.value = true
            } catch (e: Exception) {
                _sessionStatus.value = "⚠️ تعذر التجهيز: ${e.message}"
            }
        }
    }

    private fun parseJwtExpiry(jwt: String): Long {
        return try {
            val clean = jwt.removePrefix("Bearer ").trim()
            val parts = clean.split(".")
            if (parts.size < 2) return 0L
            val part = parts[1]
            val padded = part + "=".repeat((4 - part.length % 4) % 4)
            val payload = String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP))
            JSONObject(payload).optLong("exp", 0L) * 1000L
        } catch (e: Exception) { 0L }
    }

    fun isJwtFresh(minRemainingMs: Long = 5 * 60_000L): Boolean {
        if (activeJwt.isBlank() || jwtExpiryMs == 0L) return false
        return (jwtExpiryMs - System.currentTimeMillis()) > minRemainingMs
    }

    fun jwtRemainingMinutes(): Long {
        if (jwtExpiryMs == 0L) return 0L
        return ((jwtExpiryMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L)
    }

    fun hasToken(): Boolean = isJwtFresh() && hyperSession.hasFreshToken()

    fun markSessionReady() {
        _sessionReady.value = hyperSession.hasFreshToken()
        _sessionStatus.value = when {
            isJwtFresh(0) && activeSaaSessionId.isNotBlank() -> "✅ كل شيء جاهز (JWT: ${jwtRemainingMinutes()}د)"
            isJwtFresh(0) && activeSaaSessionId.isBlank() -> "⚠️ JWT موجود — sid مفقود"
            hyperSession.hasFreshToken() -> "✅ Hyper جاهز — فتح WebView لالتقاط JWT"
            else -> "⏳ جاهزة عند أول طلب"
        }
    }

    fun markSessionPreparing() {
        _sessionReady.value = false
        _sessionStatus.value = "⏳ جاري تجهيز الجلسة..."
    }

    fun addLog(message: String) {
        val curr = _searchLogs.value.toMutableList()
        curr.add(0, message)
        _searchLogs.value = curr.take(100)
    }

    private fun setStep(id: String, status: StepStatus, detail: String = "") {
        _steps.value = _steps.value.map { if (it.id == id) it.copy(status = status, detail = detail) else it }
    }

    fun clearResults() {
        _steps.value = _steps.value.map { it.copy(status = StepStatus.PENDING, detail = "") }
        _finalPnr.value = ""
        _finalSadad.value = ""
        _searchLogs.value = emptyList()
        _searchResponseText.value = "بدء البحث..."
    }

    fun sessionExpiryMs(): Long = hyperSession.expiryAt()

    fun refreshSession() {
        viewModelScope.launch {
            try {
                markSessionPreparing()
                withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
                markSessionReady()
                addLog("✅ تم تجديد reese84")
            } catch (e: Exception) {
                addLog("❌ فشل التجديد: ${e.message}")
                markSessionPreparing()
            }
        }
    }

    fun showWebViewCapture(): String? = hyperSession.getToken()

    fun onWebViewJwtCaptured(jwt: String, sid: String) {
        activeJwt = jwt
        if (sid.isNotBlank()) activeSaaSessionId = sid
        activeCookie = hyperSession.getCookieHeader()
        jwtExpiryMs = parseJwtExpiry(jwt)

        val remainingMin = if (jwtExpiryMs > 0) ((jwtExpiryMs - System.currentTimeMillis()) / 60_000L) else 0L
        val sidStatus = if (sid.isNotBlank()) "✅" else "⏳"
        addLog("🎉 JWT محفوظ — صالح $remainingMin دقيقة (sid: $sidStatus)")
        markSessionReady()
        if (activeJwt.isNotBlank() && activeSaaSessionId.isNotBlank()) {
            _needsWebViewRefresh.value = false
            _autoCaptureMode.value = false
        }
    }

    fun dismissWebView() {
        _needsWebViewRefresh.value = false
        _autoCaptureMode.value = false
    }

    fun startAutoLoop() {
        if (autoLoopJob?.isActive == true) return
        _autoLoopActive.value = true
        _needsWebViewRefresh.value = false

        autoLoopJob = viewModelScope.launch {
            addLog("🔁 بدء البحث...")
            _searchResponseText.value = "بدء البحث..."
            var cycle = 0

            while (isActive && _autoLoopActive.value) {
                cycle++
                val cycleTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                _autoLoopLastCycle.value = "#$cycle @ $cycleTime"
                addLog("🔄 دورة #$cycle")

                try {
                    withContext(Dispatchers.IO) { hyperSession.ensureFreshToken() }
                    if (activeCookie.isNullOrBlank()) activeCookie = hyperSession.getCookieHeader()

                    if (!isJwtFresh(minRemainingMs = 5 * 60_000L) || activeSaaSessionId.isBlank()) {
                        addLog("⏸️ توقف البحث — تجديد الجلسة الكاملة...")
                        withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
                        activeCookie = hyperSession.getCookieHeader()
                        addLog("✅ reese84 جديد — فتح WebView...")
                        _needsWebViewRefresh.value = true
                        _autoCaptureMode.value = true
                        while (_needsWebViewRefresh.value && isActive && _autoLoopActive.value) { delay(2000) }
                        if (!isJwtFresh(0) || activeSaaSessionId.isBlank()) {
                            addLog("⏸️ فشل التجديد — إيقاف")
                            break
                        }
                        addLog("✅ الجلسة جُددت — استئناف البحث")
                    }

                    addLog("🔍 البحث...")
                    _searchResponseText.value = "جاري البحث عن الرحلة..."
                    val flight = searchUntilFound(max = 1)

                    if (flight == null && (!isJwtFresh(0) || activeSaaSessionId.isBlank())) {
                        addLog("⏸️ انتهت الجلسة أثناء البحث — تجديد كامل...")
                        withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
                        activeCookie = hyperSession.getCookieHeader()
                        addLog("✅ reese84 جديد — فتح WebView...")
                        _needsWebViewRefresh.value = true
                        _autoCaptureMode.value = true
                        while (_needsWebViewRefresh.value && isActive && _autoLoopActive.value) { delay(2000) }
                        if (!isJwtFresh(0) || activeSaaSessionId.isBlank()) {
                            addLog("⏸️ فشل التجديد — إيقاف")
                            break
                        }
                        addLog("✅ الجلسة جُددت — العودة للبحث فوراً")
                        continue
                    }

                    if (flight == null) {
                        addLog("⚠️ لم توجد الرحلة — انتظار 10 دقائق")
                    } else {
                        addLog("✅ وجدت: ${flight.price} SAR — بدء الحجز")
                        clearResults()
                        val success = executeBooking(flight)
                        if (success) {
                            _autoLoopActive.value = false
                            break
                        }
                    }
                } catch (e: Exception) {
                    addLog("❌ خطأ في الدورة: ${e.message}")
                }
                addLog("⏳ انتظار 10 دقائق...")
                delay(10 * 60_000L)
            }
            _autoLoopActive.value = false
        }
    }

    fun stopAutoLoop() {
        autoLoopJob?.cancel(); autoLoopJob = null
        _autoLoopActive.value = false; _needsWebViewRefresh.value = false; _autoCaptureMode.value = false
    }

    // ═══════════════════════════════════════════════════════════
    //  💳 تنفيذ الحجز الكامل مع التحقق من السلة
    // ═══════════════════════════════════════════════════════════
    private suspend fun executeBooking(flight: FoundFlightDetails): Boolean {
        try {
            setStep("search", StepStatus.SUCCESS, "${flight.price} SAR")

            // ═══ 1. إنشاء السلة + التحقق ═══
            setStep("cart", StepStatus.RUNNING)
            val cid = createCart(flight.airBoundId) ?: run { setStep("cart", StepStatus.FAILED); return false }

            // 🆕 فحص محتوى السلة (السعر + الفئة)
            val cartDetails = fetchCartDetails(cid)
            if (cartDetails != null) {
                val actualPrice = cartDetails.first
                val actualFare = cartDetails.second
                val priceMismatch = kotlin.math.abs(actualPrice - flight.price) > 1.0
                val fareMismatch = actualFare.isNotBlank() && actualFare != flight.fareFamily

                if (priceMismatch || fareMismatch) {
                    addLog("⚠️ تحذير: السلة مختلفة!")
                    addLog("   طلبنا: ${flight.fareFamily} @ ${flight.price} SAR")
                    addLog("   السلة: $actualFare @ $actualPrice SAR")
                    _searchResponseText.value = buildString {
                        appendLine("⚠️ تحذير: بيانات السلة مختلفة عن المطلوبة")
                        appendLine()
                        appendLine("🎯 المطلوب:")
                        appendLine("   الفئة: ${flight.fareFamily}")
                        appendLine("   السعر: ${flight.price} SAR")
                        appendLine()
                        appendLine("📦 الفعلي في السلة:")
                        appendLine("   الفئة: ${actualFare.ifBlank { "غير محدد" }}")
                        appendLine("   السعر: $actualPrice SAR")
                        appendLine()
                        appendLine("Cart ID: $cid")
                    }
                } else {
                    addLog("✅ السلة مطابقة: $actualFare @ $actualPrice SAR")
                }
            }

            setStep("cart", StepStatus.SUCCESS, "$cid | ${flight.fareFamily} @ ${flight.price} SAR")

            // ═══ 2. المسافر ═══
            setStep("passenger", StepStatus.RUNNING)
            if (!confirmPassenger(cid)) { setStep("passenger", StepStatus.FAILED); return false }
            setStep("passenger", StepStatus.SUCCESS)

            // ═══ 3. التفعيل ═══
            setStep("extension", StepStatus.RUNNING)
            if (!addExtension(cid)) { setStep("extension", StepStatus.FAILED); return false }
            setStep("extension", StepStatus.SUCCESS)

            // ═══ 4. الاتصال ═══
            setStep("contacts", StepStatus.RUNNING)
            if (!saveContacts(cid)) { setStep("contacts", StepStatus.FAILED); return false }
            setStep("contacts", StepStatus.SUCCESS)

            // ═══ 5. الطلب ═══
            setStep("order", StepStatus.RUNNING)
            val ord = createOrder(cid) ?: run { setStep("order", StepStatus.FAILED); return false }
            _finalPnr.value = ord.first; _finalSadad.value = ord.second
            setStep("order", StepStatus.SUCCESS, "PNR: ${ord.first} | SADAD: ${ord.second}")

            if (monitoringMode) {
                setStep("payment", StepStatus.SKIPPED, "👁️ المراقبة فقط")
                return true
            }

            // ═══ 6. السداد ═══
            setStep("payment", StepStatus.RUNNING)
            if (!submitPayment(ord.first)) { setStep("payment", StepStatus.FAILED); return false }
            setStep("payment", StepStatus.SUCCESS, "✅ SADAD: ${ord.second}")

            return true
        } catch (e: Exception) {
            addLog("❌ خطأ في الحجز: ${e.message}")
            return false
        }
    }

    fun runOnceBooking(activity: android.app.Activity) {
        if (_isRunning.value) return
        if (!isJwtFresh(0) || activeSaaSessionId.isBlank()) { addLog("❌ لا JWT/sid"); return }

        clearResults()
        _isRunning.value = true

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { hyperSession.ensureFreshToken() }
                if (activeCookie.isNullOrBlank()) activeCookie = hyperSession.getCookieHeader()

                _searchResponseText.value = "بدء البحث..."
                setStep("search", StepStatus.RUNNING)
                val flight = searchUntilFound(max = 3)
                if (flight == null) {
                    setStep("search", StepStatus.FAILED); _isRunning.value = false; return@launch
                }
                executeBooking(flight)
            } catch (e: Exception) {
                addLog("❌ ${e.message}")
            } finally { _isRunning.value = false }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  🔍 البحث الذكي — عرض جميع الرحلات دائماً
    // ═══════════════════════════════════════════════════════════
    private suspend fun searchUntilFound(max: Int = 100): FoundFlightDetails? {
        var attempt = 0

        val wantedTime = FlightData.normalizeTime(flightData.departureTime)
        val wantedCodes = FareFamilies.FARE_CODE_MAP[flightData.cabinClass] ?: emptyList()

        addLog("🎯 بحث: وقت=${wantedTime.ifBlank { "أي" }} | فئة=${FareFamilies.displayName(flightData.cabinClass)} | مباشر=${flightData.directOnly}")

        while (attempt < max) {
            attempt++
            setStep("search", StepStatus.RUNNING, "محاولة $attempt/$max")
            _searchResponseText.value = "⏳ جاري إرسال الطلب... (محاولة $attempt)"

            try {
                val resp = RetrofitClient.api.searchAirBounds(
                    headers = buildHeaders("4"),
                    request = SaudiaSearchRequest(
                        airBoundsSearchInputs = AirBoundsSearchInputs(
                            itineraries = listOf(Itinerary(
                                originLocationCode = flightData.from,
                                destinationLocationCode = flightData.to,
                                departureDateTime = "${flightData.date}T00:00:00.000",
                                directFlights = flightData.directOnly
                            )),
                            travelers = listOf(Traveler()),
                            searchPreferences = SearchPreferences()
                        ),
                        offerCodeAvailabilityInput = OfferCodeAvailabilityInput()
                    )
                )
                saveCookiesFromResponse(resp)

                if (resp.code() == 401) {
                    _searchResponseText.value = "🚨 انتهت الجلسة (401)"
                    jwtExpiryMs = 0L
                    return null
                }
                if (resp.code() == 403) {
                    _searchResponseText.value = "🚨 Imperva رفض (403)"
                    return null
                }

                if (resp.isSuccessful) {
                    val body = resp.body() ?: continue
                    val flights = parseAirBounds(body)
                    addLog("📡 استلمنا ${flights.size} رحلة")

                    val match = findMatchingFlight(flights, wantedTime, wantedCodes)
                    val matchedFlightNumber = match?.first?.flightNumber
                    val matchedFareCode = match?.second?.code

                    _searchResponseText.value = buildString {
                        appendLine("📊 ${flights.size} رحلة | محاولة $attempt | HTTP ${resp.code()}")
                        appendLine("🎯 المطلوب: وقت=${wantedTime.ifBlank { "أي" }} | فئة=${wantedCodes.joinToString(",")} | مباشر=${if (flightData.directOnly) "✅" else "❌"}")
                        appendLine("────────────────────────")
                        appendLine()

                        flights.forEachIndexed { idx, f ->
                            val isMatched = f.flightNumber == matchedFlightNumber
                            val prefix = if (isMatched) "🎯" else "  "
                            val directStr = if (f.isDirect) "مباشر" else "${f.stops}st"

                            appendLine("$prefix ${idx + 1}. ${f.flightNumber} @ ${f.departTime} | $directStr")

                            f.fares.forEach { fare ->
                                val status = if (fare.isAvailable) "✅" else "❌"
                                val star = if (isMatched && fare.code == matchedFareCode) " ⬅ المختارة" else ""
                                appendLine("     $status ${fare.code} | ${fare.price} SAR | quota=${fare.quota}$star")
                            }
                            appendLine()
                        }

                        appendLine("────────────────────────")
                        if (match != null) appendLine("✅ تم العثور على مطابقة") else appendLine("⚠️ لا مطابقة — سيُعاد المحاولة")
                    }

                    if (match != null) {
                        val (flight, fare) = match
                        addLog("🎯 مطابقة: ${flight.flightNumber} @ ${flight.departTime} | ${fare.code} | ${fare.price} SAR")
                        return FoundFlightDetails(
                            flightNumber = flight.flightNumber,
                            date = flightData.date,
                            origin = flight.origin,
                            destination = flight.destination,
                            price = fare.price,
                            fareFamily = fare.code,
                            airBoundId = fare.airBoundId
                        )
                    } else {
                        addLog("⚠️ لا مطابقة — ${flights.size} رحلة تم فحصها")
                    }
                } else {
                    _searchResponseText.value = "⚠️ استجابة غير متوقعة: HTTP ${resp.code()}"
                }
            } catch (e: Exception) {
                _searchResponseText.value = "❌ خطأ في الاتصال:\n${e.message}"
            }
            if (attempt < max) delay(Random.nextLong(5_000L, 8_000L))
        }
        return null
    }

    private fun parseAirBounds(response: SaudiaResponse): List<ParsedFlight> {
        val groups = response.data?.airBoundGroups ?: return emptyList()
        val flights = mutableListOf<ParsedFlight>()

        for (group in groups) {
            val bd = group.boundDetails ?: continue
            val segments = bd.segments ?: continue
            if (segments.isEmpty()) continue

            val flightId = segments[0].flightId ?: continue

            val fnMatch = Regex("SEG-([A-Z]{2}\\d+)-").find(flightId)
            val flightNumber = fnMatch?.groupValues?.get(1) ?: "?"

            val dtMatch = Regex("-(\\d{4})$").find(flightId)
            val timeRaw = dtMatch?.groupValues?.get(1) ?: "0000"
            val departTime = "${timeRaw.substring(0, 2)}:${timeRaw.substring(2, 4)}"

            val fares = mutableListOf<ParsedFare>()
            for (ab in group.airBounds ?: emptyList()) {
                val code = ab.fareFamilyCode ?: "?"
                val isSoldOut = ab.status?.value == "soldOut"
                val avail = ab.availabilityDetails?.firstOrNull()
                val quota = avail?.quota ?: 0
                val statusCode = avail?.statusCode ?: ""
                val isAvailable = !isSoldOut && quota > 0 && statusCode == "HK"
                val price = (ab.airOffer?.totalPrice?.value ?: 0.0) / 100.0
                fares.add(ParsedFare(
                    code = code,
                    quota = quota,
                    statusCode = statusCode,
                    price = price,
                    isAvailable = isAvailable,
                    airBoundId = ab.airBoundId ?: ""
                ))
            }

            flights.add(ParsedFlight(
                flightNumber = flightNumber,
                departTime = departTime,
                origin = bd.origin ?: "",
                destination = bd.destination ?: "",
                isDirect = segments.size == 1,
                stops = segments.size - 1,
                fares = fares
            ))
        }
        return flights
    }

    private fun findMatchingFlight(
        flights: List<ParsedFlight>,
        wantedTime: String,
        wantedCodes: List<String>
    ): Pair<ParsedFlight, ParsedFare>? {
        for (flight in flights) {
            if (flightData.directOnly && !flight.isDirect) continue
            if (wantedTime.isNotBlank() && flight.departTime != wantedTime) continue

            val matchedFare = flight.fares.firstOrNull {
                it.isAvailable && wantedCodes.contains(it.code)
            } ?: continue

            return Pair(flight, matchedFare)
        }
        return null
    }

    private fun buildHeaders(apiVersion: String, referer: String = "https://www.saudia.com/"): Map<String, String> {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)
        val headers = mutableMapOf(
            "Authorization" to activeJwt, "x-d-token" to activeXdToken, "saa-session-id" to activeSaaSessionId,
            "x-api-version" to apiVersion, "platform" to "Web", "version" to "27.2", "Accept" to "application/json",
            "Content-Type" to "application/json", "Origin" to "https://www.saudia.com", "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36",
            "Accept-Language" to "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7", "request-id" to "|$traceId.$spanId",
            "traceparent" to "00-$traceId-$spanId-01", "x-datadog-origin" to "rum",
            "x-datadog-parent-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString(),
            "x-datadog-sampling-priority" to "1",
            "x-datadog-trace-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString(),
            "browserid" to "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9", "channel" to "MOBILE_WEB"
        )
        if (!activeCookie.isNullOrBlank()) headers["Cookie"] = activeCookie!!
        return headers
    }

    private fun saveCookiesFromResponse(response: Response<*>) {
        val setCookieHeaders = response.headers().values("Set-Cookie")
        if (setCookieHeaders.isEmpty()) return
        val cookieMap = mutableMapOf<String, String>()
        activeCookie?.split(";")?.forEach { pair ->
            val parts = pair.trim().split("=", limit = 2)
            if (parts.size == 2) cookieMap[parts[0].trim()] = parts[1].trim()
        }
        setCookieHeaders.forEach { header ->
            val firstPart = header.split(";").first().trim()
            val parts = firstPart.split("=", limit = 2)
            if (parts.size == 2) cookieMap[parts[0].trim()] = parts[1].trim()
        }
        activeCookie = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private suspend fun createCart(airBoundId: String): String? = try {
        val r = RetrofitClient.api.createCart(headers = buildHeaders("2"), request = CreateCartRequest(listOf(airBoundId)))
        saveCookiesFromResponse(r)
        if (!r.isSuccessful) null else {
            val bodyStr = r.body()?.string() ?: ""
            JSONObject(bodyStr).optJSONObject("data")?.optString("id")?.takeIf { it.isNotEmpty() } ?: JSONObject(bodyStr).optString("id")
        }
    } catch (e: Exception) { null }

    /**
     * 🆕 يجلب تفاصيل السلة للتحقق من السعر والفئة الفعلية
     */
    private suspend fun fetchCartDetails(cid: String): Pair<Double, String>? = try {
        val r = RetrofitClient.api.getCartDetails(
            cartId = cid,
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails")
        )
        saveCookiesFromResponse(r)
        if (!r.isSuccessful) {
            addLog("⚠️ فحص السلة فشل: HTTP ${r.code()}")
            null
        } else {
            val bodyStr = r.body()?.string() ?: ""
            val json = JSONObject(bodyStr)

            var price = 0.0
            var fareFamily = ""

            // محاولة 1: data.airBoundGroups[0].airBounds[0]
            val data = json.optJSONObject("data")
            val groups = data?.optJSONArray("airBoundGroups")
            if (groups != null && groups.length() > 0) {
                val group = groups.optJSONObject(0)
                val airBounds = group?.optJSONArray("airBounds")
                if (airBounds != null && airBounds.length() > 0) {
                    val ab = airBounds.optJSONObject(0)
                    fareFamily = ab?.optString("fareFamilyCode", "") ?: ""
                    price = (ab?.optJSONObject("airOffer")
                        ?.optJSONObject("totalPrice")
                        ?.optDouble("value", 0.0) ?: 0.0) / 100.0
                }
            }

            // محاولة 2: data.airOffer.totalPrice مباشرة
            if (price == 0.0) {
                price = (data?.optJSONObject("airOffer")
                    ?.optJSONObject("totalPrice")
                    ?.optDouble("value", 0.0) ?: 0.0) / 100.0
            }

            addLog("📦 السلة: fare=$fareFamily | price=$price")
            Pair(price, fareFamily)
        }
    } catch (e: Exception) {
        addLog("❌ فحص السلة خطأ: ${e.message}")
        null
    }

    private suspend fun confirmPassenger(cid: String): Boolean = try {
        val p = passengerData
        val payload = """{"passengerTypeCode":"ADT","gender":"${p.gender}","dateOfBirth":"${p.dateOfBirth}","names":[{"firstName":"${p.firstName}","lastName":"${p.lastName}","title":"${p.title}","nameType":"universal"}],"regulatoryDetails":[{"regulatoryApisType":"ApisDetailRegulatoryDocument","regulatoryDocument":{"number":"${p.nationalId}","name":{"firstName":"${p.firstName.uppercase()}","lastName":"${p.lastName.uppercase()}","nameType":"universal"},"nationalityCode":"${p.nationality}","gender":"${p.gender}","birthDate":"${p.dateOfBirth}","documentType":"identityCard"}}]}"""
        val r = RetrofitClient.api.updateTravelerRaw(cid, "SKH-1-EXT", headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"), request = payload.toRequestBody("application/json".toMediaType()))
        saveCookiesFromResponse(r); r.code() == 200
    } catch (e: Exception) { false }

    private suspend fun addExtension(cid: String): Boolean = try {
        val body = """[{"content":"eyJzdGF0dXMiOiJWRVJJRklFRCIsImhlYWx0aENoZWNrU3RhdHVzIjoiTk9fUkVTUE9OU0UifQ==","travelerId":"SKH-1-EXT","extensionType":"TextExtension","name":"YQENSTATUS"}]"""
        val r = RetrofitClient.api.addCartExtension(cid, guestOfficeId = "JEDSV08AA", headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"), request = body.toRequestBody("application/json".toMediaType()))
        saveCookiesFromResponse(r); r.isSuccessful
    } catch (e: Exception) { false }

    private suspend fun saveContacts(cid: String): Boolean = try {
        val p = passengerData
        val body = """[{"address":"${p.email}","category":"personal","contactType":"Email","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"address":"${p.email}","category":"personal","contactType":"Email","purpose":"standard"},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"${p.phone}","deviceType":"mobile","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"${p.phone}","deviceType":"mobile","purpose":"standard"}]"""
        val r = RetrofitClient.api.saveContacts(cid, headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"), request = body.toRequestBody("application/json".toMediaType()))
        saveCookiesFromResponse(r); r.isSuccessful
    } catch (e: Exception) { false }

    private suspend fun createOrder(cid: String): Pair<String, String>? = try {
        val r = RetrofitClient.api.createOrderRaw(cid, headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"), request = "{}".toRequestBody("application/json".toMediaType()))
        saveCookiesFromResponse(r)
        if (!r.isSuccessful) null else {
            val b = r.body()?.string() ?: return null
            val pnr = Regex("\"id\"\\s*:\\s*\"([A-Z0-9]{4,8})\"").find(b)?.groupValues?.get(1) ?: return null
            val sadad = Regex("\"numericId\"\\s*:\\s*\"([0-9]{10,14})\"").find(b)?.groupValues?.get(1) ?: ""
            Pair(pnr, sadad)
        }
    } catch (e: Exception) { null }

    private suspend fun submitPayment(pnr: String): Boolean = try {
        val p = passengerData
        val body = """{"orderPaymentRequest":{"paymentRequests":[{"paymentMethod":{"paymentType":"CustomPayment","id":"SADAD"}}]},"emailParameters":{"displayParameters":{"alharamain":true,"baggage":true,"bookingCom":true,"eatmarna":false,"electronicVisa":false,"plusgrade":true,"rental":true,"seat":true,"wifi":true,"onDemandTeasers":[]}},"isCardholderAbsent":false,"isQueuePlacementEnabled":false}"""
        val r = RetrofitClient.api.submitPayment(pnr, p.lastName, headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"), request = body.toRequestBody("application/json".toMediaType()))
        saveCookiesFromResponse(r); r.isSuccessful
    } catch (e: Exception) { false }
}
