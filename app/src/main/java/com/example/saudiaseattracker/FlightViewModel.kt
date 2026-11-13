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
    val from: String = "JED", val to: String = "AHB",
    val date: String = "2026-11-17", val departureTime: String = "06:40",
    val cabinClass: String = FareFamilies.ALL_HOSPITALITY, val directOnly: Boolean = true
) {
    fun isValid(): Boolean = from.length == 3 && to.length == 3 && date.isNotBlank() && (departureTime.isBlank() || normalizeTime(departureTime).isNotBlank())
    companion object {
        fun normalizeTime(input: String): String {
            val t = input.trim().replace(":", "")
            return if (t.length == 4 && t.all { it.isDigit() }) "${t.substring(0, 2)}:${t.substring(2, 4)}" else input.trim()
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
    private var _sessionExpired = false

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

    fun markSessionReady() {
        _sessionReady.value = hyperSession.hasFreshToken() && activeSaaSessionId.isNotBlank()
        _sessionStatus.value = when {
            !hyperSession.hasFreshToken() -> "❌ جلسة الهايبر غير مفعلة"
            hyperSession.hasFreshToken() && activeSaaSessionId.isBlank() -> "🔄 جاري تجديد الجلسة..."
            hyperSession.hasFreshToken() && activeSaaSessionId.isNotBlank() -> "✅ جلسة الهايبر جاهزة"
            else -> "⏳ جاهزة عند أول طلب"
        }
    }

    fun markSessionPreparing() {
        _sessionReady.value = false
        _sessionStatus.value = "🔄 جاري تحديث الجلسة..."
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
        jwtExpiryMs = 0L // لم نعد نستخدم وقت انتهاء JWT في الواجهة
        addLog("🎉 JWT محفوظ — sid: ${if (sid.isNotBlank()) "✅" else "❌"}")
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

    // ═══════════════════════════════════════════════════════════
    //  🔁 حلقة البحث المستمرة — إيقاف تلقائي عند انتهاء الجلسة
    // ═══════════════════════════════════════════════════════════
    fun startAutoLoop() {
        if (autoLoopJob?.isActive == true) return
        _autoLoopActive.value = true
        _needsWebViewRefresh.value = false

        autoLoopJob = viewModelScope.launch {
            addLog("🔁 بدء البحث المستمر...")
            _searchResponseText.value = "بدء البحث..."

            while (isActive && _autoLoopActive.value) {
                try {
                    // 1. التحقق من الجلسة قبل البحث
                    withContext(Dispatchers.IO) { hyperSession.ensureFreshToken() }
                    
                    if (!hyperSession.hasFreshToken() || activeSaaSessionId.isBlank() || _sessionExpired) {
                        addLog("⏸️ إيقاف البحث — تجديد الجلسة...")
                        markSessionPreparing()
                        
                        withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
                        activeCookie = hyperSession.getCookieHeader()
                        
                        addLog("✅ reese84 جديد — فتح WebView في الخلفية...")
                        _needsWebViewRefresh.value = true
                        _autoCaptureMode.value = true
                        
                        while (_needsWebViewRefresh.value && isActive && _autoLoopActive.value) { delay(1000) }
                        
                        if (!hyperSession.hasFreshToken() || activeSaaSessionId.isBlank()) {
                            addLog("⚠️ فشل التجديد — إعادة المحاولة...")
                            delay(5000)
                            continue
                        }
                        
                        _sessionExpired = false
                        markSessionReady()
                        addLog("✅ الجلسة جُددت — استئناف البحث")
                    }

                    // 2. محاولة بحث واحدة
                    val flight = searchUntilFound(max = 1)
                    
                    if (!_autoLoopActive.value) break

                    // 3. معالجة النتيجة
                    if (flight != null) {
                        addLog("✅ وجدت: ${flight.price} SAR — بدء الحجز")
                        clearResults()
                        val success = executeBooking(flight)
                        if (success) {
                            addLog("🎉 انتهى الحجز بنجاح")
                            _autoLoopActive.value = false
                            break
                        } else {
                            addLog("❌ فشل الحجز — إعادة في الدورة القادمة")
                            delay(10000)
                        }
                    } else {
                        if (_sessionExpired) {
                            addLog("🚨 انتهت الجلسة أثناء البحث — جاري التجديد...")
                            continue // سيدخل في فرع التجديد بالأعلى
                        } else {
                            delay(Random.nextLong(5000L, 8000L))
                        }
                    }
                } catch (e: Exception) {
                    addLog("❌ خطأ: ${e.message}")
                    delay(5000)
                }
            }
            _autoLoopActive.value = false
        }
    }

    fun stopAutoLoop() {
        autoLoopJob?.cancel(); autoLoopJob = null
        _autoLoopActive.value = false; _needsWebViewRefresh.value = false; _autoCaptureMode.value = false
        addLog("⏹️ تم الإيقاف")
    }

    // ═══════════════════════════════════════════════════════════
    //  💳 تنفيذ الحجز الكامل مع التحقق من السلة
    // ═══════════════════════════════════════════════════════════
    private suspend fun executeBooking(flight: FoundFlightDetails): Boolean {
        try {
            setStep("search", StepStatus.SUCCESS, "${flight.price} SAR")

            setStep("cart", StepStatus.RUNNING)
            val cid = createCart(flight.airBoundId) ?: run { setStep("cart", StepStatus.FAILED); return false }

            val cartDetails = fetchCartDetails(cid)
            if (cartDetails != null) {
                val actualPrice = cartDetails.first
                val actualFare = cartDetails.second
                val priceMismatch = kotlin.math.abs(actualPrice - flight.price) > 1.0
                val fareMismatch = actualFare.isNotBlank() && actualFare != flight.fareFamily

                if (priceMismatch || fareMismatch) {
                    addLog("⚠️ تحذير: السلة مختلفة!")
                } else {
                    addLog("✅ السلة مطابقة: $actualFare @ $actualPrice SAR")
                }
            }

            setStep("cart", StepStatus.SUCCESS, "$cid | ${flight.fareFamily} @ ${flight.price} SAR")

            setStep("passenger", StepStatus.RUNNING)
            if (!confirmPassenger(cid)) { setStep("passenger", StepStatus.FAILED); return false }
            setStep("passenger", StepStatus.SUCCESS)

            setStep("extension", StepStatus.RUNNING)
            if (!addExtension(cid)) { setStep("extension", StepStatus.FAILED); return false }
            setStep("extension", StepStatus.SUCCESS)

            setStep("contacts", StepStatus.RUNNING)
            if (!saveContacts(cid)) { setStep("contacts", StepStatus.FAILED); return false }
            setStep("contacts", StepStatus.SUCCESS)

            setStep("order", StepStatus.RUNNING)
            val ord = createOrder(cid) ?: run { setStep("order", StepStatus.FAILED); return false }
            _finalPnr.value = ord.first; _finalSadad.value = ord.second
            setStep("order", StepStatus.SUCCESS, "PNR: ${ord.first} | SADAD: ${ord.second}")

            if (monitoringMode) {
                setStep("payment", StepStatus.SKIPPED, "👁️ المراقبة فقط")
                return true
            }

            setStep("payment", StepStatus.RUNNING)
            if (!submitPayment(ord.first)) { setStep("payment", StepStatus.FAILED); return false }
            setStep("payment", StepStatus.SUCCESS, "✅ SADAD: ${ord.second}")
            return true
        } catch (e: Exception) {
            addLog("❌ خطأ في الحجز: ${e.message}")
            return false
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  🔍 البحث — يرجع null فوراً إذا انتهت الجلسة (401/403)
    // ═══════════════════════════════════════════════════════════
    private suspend fun searchUntilFound(max: Int = 100): FoundFlightDetails? {
        _sessionExpired = false
        var attempt = 0
        val wantedTime = FlightData.normalizeTime(flightData.departureTime)
        val wantedCodes = FareFamilies.FARE_CODE_MAP[flightData.cabinClass] ?: emptyList()

        while (attempt < max) {
            if (!_autoLoopActive.value) return null
            
            attempt++
            setStep("search", StepStatus.RUNNING, "محاولة $attempt")
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
                    _sessionExpired = true
                    return null
                }
                if (resp.code() == 403) {
                    _searchResponseText.value = "🚨 Imperva رفض (403)"
                    _sessionExpired = true
                    return null
                }

                if (resp.isSuccessful) {
                    val body = resp.body() ?: continue
                    val flights = parseAirBounds(body)

                    val match = findMatchingFlight(flights, wantedTime, wantedCodes)
                    val matchedFlightNumber = match?.first?.flightNumber
                    val matchedFareCode = match?.second?.code

                    // عرض الرحلات فقط
                    _searchResponseText.value = buildString {
                        appendLine("📊 ${flights.size} رحلة | محاولة $attempt | HTTP ${resp.code()}")
                        appendLine()
                        if (flights.isEmpty()) {
                            appendLine("⚠️ لم تُستخرج أي رحلة")
                        } else {
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
                        }
                    }

                    if (match != null) {
                        val (flight, fare) = match
                        addLog("🎯 مطابقة: ${flight.flightNumber} @ ${flight.departTime} | ${fare.code}")
                        return FoundFlightDetails(
                            flightNumber = flight.flightNumber,
                            date = flightData.date,
                            origin = flight.origin,
                            destination = flight.destination,
                            price = fare.price,
                            fareFamily = fare.code,
                            airBoundId = fare.airBoundId
                        )
                    }
                } else {
                    _searchResponseText.value = "⚠️ HTTP ${resp.code()}"
                }
            } catch (e: Exception) {
                _searchResponseText.value = "❌ خطأ:\n${e.message}"
            }
            if (attempt < max) delay(Random.nextLong(5_000L, 8_000L))
        }
        return null
    }

    // ... بقية الدوال المساعدة (parseAirBounds, findMatchingFlight, buildHeaders, ...) كما هي ...
    // سأتركها كما هي في الكود الكامل أدناه للحفاظ على الطول.
