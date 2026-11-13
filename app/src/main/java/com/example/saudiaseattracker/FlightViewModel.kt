package com.example.saudiaseattracker

import android.app.Application
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
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
    val date: String = "2026-11-17", val flightNumber: String = "SV8844"
) {
    fun isValid(): Boolean = from.length == 3 && to.length == 3 && date.isNotBlank() && flightNumber.isNotBlank()
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

    // رد البحث (JSON) في المستطيل
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
                // فتح WebView تلقائياً لجلب JWT + sid بدون إظهاره للمستخدم
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

    fun refreshHyperThenCapture(manual: Boolean = false) {
        if (_needsWebViewRefresh.value) return
        viewModelScope.launch {
            try {
                addLog("🔄 تجديد reese84 قبل فتح WebView...")
                withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
                activeCookie = hyperSession.getCookieHeader()
                addLog("✅ reese84 جديد جاهز — فتح WebView...")
                _autoCaptureMode.value = !manual
                _needsWebViewRefresh.value = true
            } catch (e: Exception) {
                addLog("❌ فشل تجديد reese84: ${e.message}")
            }
        }
    }

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
            addLog("🔁 بدء الحلقة التلقائية...")
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
                            addLog("⏸️ فشل التجديد — إيقاف الحلقة")
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
                            addLog("⏸️ فشل التجديد — إيقاف الحلقة")
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

    private suspend fun executeBooking(flight: FoundFlightDetails): Boolean {
        try {
            setStep("search", StepStatus.SUCCESS, "${flight.price} SAR")
            delay(1000)

            setStep("cart", StepStatus.RUNNING)
            val cid = createCart(flight.airBoundId) ?: run { setStep("cart", StepStatus.FAILED); return false }
            setStep("cart", StepStatus.SUCCESS, cid); delay(1000)

            setStep("passenger", StepStatus.RUNNING)
            if (!confirmPassenger(cid)) { setStep("passenger", StepStatus.FAILED); return false }
            setStep("passenger", StepStatus.SUCCESS); delay(1000)

            setStep("extension", StepStatus.RUNNING)
            if (!addExtension(cid)) { setStep("extension", StepStatus.FAILED); return false }
            setStep("extension", StepStatus.SUCCESS); delay(1000)

            setStep("contacts", StepStatus.RUNNING)
            if (!saveContacts(cid)) { setStep("contacts", StepStatus.FAILED); return false }
            setStep("contacts", StepStatus.SUCCESS); delay(1000)

            setStep("order", StepStatus.RUNNING)
            val ord = createOrder(cid) ?: run { setStep("order", StepStatus.FAILED); return false }
            _finalPnr.value = ord.first; _finalSadad.value = ord.second
            setStep("order", StepStatus.SUCCESS, "PNR: ${ord.first} | SADAD: ${ord.second}")
            delay(1000)

            if (monitoringMode) { setStep("payment", StepStatus.SKIPPED, "👁️ المراقبة فقط"); return true }

            setStep("payment", StepStatus.RUNNING)
            if (!submitPayment(ord.first)) { setStep("payment", StepStatus.FAILED); return false }
            setStep("payment", StepStatus.SUCCESS, "✅ SADAD: ${ord.second}")
            return true
        } catch (e: Exception) { return false }
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

    private suspend fun searchUntilFound(max: Int = 100): FoundFlightDetails? {
        var attempt = 0
        val gson = GsonBuilder().setPrettyPrinting().create()
        
        while (attempt < max) {
            attempt++
            setStep("search", StepStatus.RUNNING, "محاولة $attempt/$max")
            
            // هنا نقوم بمسح الرد القديم وتجهيز الرد الجديد
            _searchResponseText.value = "جاري إرسال الطلب... (محاولة $attempt)"
            
            try {
                val resp = RetrofitClient.api.searchAirBounds(
                    headers = buildHeaders("4"),
                    request = SaudiaSearchRequest(
                        airBoundsSearchInputs = AirBoundsSearchInputs(
                            itineraries = listOf(Itinerary(flightData.from, flightData.to, "${flightData.date}T00:00:00.000")),
                            travelers = listOf(Traveler()),
                            searchPreferences = SearchPreferences()
                        ),
                        offerCodeAvailabilityInput = OfferCodeAvailabilityInput()
                    )
                )
                saveCookiesFromResponse(resp)

                // نلتقط رد الـ API الفعلي سواء كان نجاح أو خطأ
                val rawJsonResponse = try {
                    if (resp.isSuccessful) gson.toJson(resp.body())
                    else resp.errorBody()?.string() ?: "لا يوجد رد"
                } catch (e: Exception) { "فشل قراءة الرد" }

                // نضع الرد الفعلي في المستطيل (هذا يقوم بمسح القديم وعرض الجديد)
                _searchResponseText.value = "HTTP ${resp.code()} | محاولة $attempt\n\n$rawJsonResponse"

                if (resp.code() == 401) {
                    jwtExpiryMs = 0L
                    return null
                }
                if (resp.code() == 403) return null

                if (resp.isSuccessful) {
                    val g = resp.body()?.data?.airBoundGroups?.firstOrNull { gg ->
                        gg.boundDetails?.segments?.any { it.flightId?.contains(flightData.flightNumber, true) == true } == true
                    }
                    val cheapest = g?.airBounds?.filter { it.status?.value != "soldOut" }
                        ?.minByOrNull { it.airOffer?.totalPrice?.value ?: Double.MAX_VALUE }

                    if (cheapest != null) {
                        return FoundFlightDetails(
                            flightData.flightNumber, flightData.date, g.boundDetails?.origin ?: flightData.from,
                            g.boundDetails?.destination ?: flightData.to, (cheapest.airOffer?.totalPrice?.value ?: 0.0) / 100.0,
                            cheapest.fareFamilyCode ?: "N/A", cheapest.airBoundId ?: ""
                        )
                    }
                }
            } catch (e: Exception) { 
                _searchResponseText.value = "❌ خطأ في الاتصال:\n${e.message}" 
            }
            if (attempt < max) delay(15_000)
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
