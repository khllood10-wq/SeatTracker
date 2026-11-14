package com.example.saudiaseattracker

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
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
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
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
    val destination: String, val departureTime: String, val price: Double,
    val fareFamily: String, val airBoundId: String
)

enum class StepStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }
data class BookingStep(val id: String, val title: String, val status: StepStatus = StepStatus.PENDING, val detail: String = "")

class FlightViewModel(application: Application) : AndroidViewModel(application) {

    private var activeJwt = ""
    private var activeCookie: String? = null
    private var activeSaaSessionId = ""
    private var activeXdToken = ""
    private var _sessionExpired = false
    private var _lastErrorCode: Int = 0
    private var _lastErrorMessage: String = ""

    private var _consecutiveSilentBlocks = 0
    private var _totalSilentBlocksInSession = 0
    private var _renewAttemptsAfter403 = 0

    // Throttling — سرعة ثابتة 2-5s + توقف استباقي
    private val _requestTimestamps = mutableListOf<Long>()
    private val THROTTLE_WINDOW_MS = 10 * 60_000L
    private val THROTTLE_STOP_THRESHOLD = 40
    private val THROTTLE_STOP_DURATION_MS = 15_000L

    // 🆕 Hyper Renewal — كل 10 دقائق + WebView مع كل تجديد
    private var _lastHyperRenewTime = 0L
    private val HYPER_RENEW_INTERVAL_MS = 10 * 60_000L

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var autoLoopJob: Job? = null

    var lastBookedFlight by mutableStateOf<FoundFlightDetails?>(null)

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

    private val _sessionTimerSeconds = MutableStateFlow(0L)
    val sessionTimerSeconds: StateFlow<Long> = _sessionTimerSeconds

    private val _jwtReady = MutableStateFlow(false)
    val jwtReady: StateFlow<Boolean> = _jwtReady

    fun pushDebug(entry: DebugEntry) { DebugBus.emit(entry) }
    fun clearDebugLog() { _debugEntries.value = emptyList() }
    fun isSidReady(): Boolean = activeSaaSessionId.isNotBlank()
    fun hasToken(): Boolean = hyperSession.hasFreshToken() && activeSaaSessionId.isNotBlank() && activeJwt.isNotBlank()
    fun isShizukuReady(): Boolean = ShizukuHelper.isReady() && ShizukuHelper.isPermissionGranted()
    fun requestShizukuPermission() = ShizukuHelper.requestPermission()

    init {
        viewModelScope.launch {
            DebugBus.flow.collect { entry ->
                val curr = _debugEntries.value.toMutableList()
                curr.add(0, entry)
                _debugEntries.value = curr.take(300)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                val remaining = ((hyperSession.expiryAt() - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                _sessionTimerSeconds.value = remaining
                delay(1000)
            }
        }

        viewModelScope.launch {
            try {
                addLog("🔄 تهيئة جلسة Hyper...")
                withContext(Dispatchers.IO) { hyperSession.ensureFreshToken() }
                _lastHyperRenewTime = System.currentTimeMillis()
                markSessionReady()
                addLog("✅ جلسة Hyper جاهزة — فتح WebView تلقائياً...")
                _autoCaptureMode.value = true
                _needsWebViewRefresh.value = true
            } catch (e: Exception) {
                _sessionStatus.value = "❌ جلسة الهايبر غير مفعلة"
            }
        }
    }

    private fun acquireWakeLocks() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SaudiaSeatTracker::SearchWakeLock").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {}

        try {
            val wm = getApplication<Application>().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SaudiaSeatTracker::WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {}
    }

    private fun releaseWakeLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private suspend fun calculateThrottleDelay(): Long {
        val now = System.currentTimeMillis()
        _requestTimestamps.removeAll { it < now - THROTTLE_WINDOW_MS }
        val recentCount = _requestTimestamps.size

        return when {
            recentCount < THROTTLE_STOP_THRESHOLD -> Random.nextLong(2_000L, 5_000L)
            else -> {
                addLog("⏸️ توقف استباقي: $recentCount طلب")
                _searchResponseText.value = buildString {
                    appendLine("⏸️ توقف استباقي ($recentCount طلب)")
                    appendLine()
                    appendLine("📊 تجاوزت $THROTTLE_STOP_THRESHOLD طلب في 10 دقائق")
                    appendLine("⏳ انتظار 15 ثانية لكسر النمط...")
                    appendLine("🔄 ثم تصفير العداد — استئناف فوري")
                }
                delay(THROTTLE_STOP_DURATION_MS)
                _requestTimestamps.clear()
                addLog("🔄 تصفير العداد — استئناف")
                Random.nextLong(2_000L, 5_000L)
            }
        }
    }

    private suspend fun performAirplaneModeRenewal(): Boolean {
        if (!ShizukuHelper.isReady()) {
            _searchResponseText.value = "❌ Shizuku غير جاهز\n\n📌 السبب: IP مُعلَّم من Imperva\n💡 الحل:\n  1️⃣ شغّل Shizuku\n  2️⃣ امنح التطبيق صلاحية\n  3️⃣ أعد المحاولة"
            return false
        }

        if (!ShizukuHelper.isPermissionGranted()) {
            _searchResponseText.value = "⚠️ صلاحية Shizuku مطلوبة\n\n📌 السبب: IP مُعلَّم\n💡 امنح الصلاحية ثم أعد المحاولة"
            ShizukuHelper.requestPermission()
            return false
        }

        _searchResponseText.value = "✈️ تفعيل وضع الطيران...\n\n📌 السبب: 3 محاولات 403 متتالية\n📌 Imperva يرفض IP الحالي"
        addLog("✈️ تفعيل الطيران (Shizuku)...")

        if (!ShizukuHelper.enableAirplaneMode()) {
            _searchResponseText.value = "❌ فشل تفعيل الطيران\n\nتأكد من Shizuku وصلاحياته"
            return false
        }

        _searchResponseText.value = "✈️ الطيران مُفعّل\n\n⏳ انتظار 15 ثانية..."
        for (i in 15 downTo 1) {
            _searchResponseText.value = "✈️ الطيران مُفعّل\n\n⏳ انتظار $i ثانية..."
            delay(1000)
        }

        _searchResponseText.value = "📶 إيقاف الطيران + تفعيل البيانات..."
        ShizukuHelper.disableAirplaneMode()
        delay(1500)
        ShizukuHelper.enableMobileData()

        _searchResponseText.value = "📶 انتظار الشبكة...\n\n⏳ 12 ثانية..."
        for (i in 12 downTo 1) {
            _searchResponseText.value = "📶 انتظار الشبكة...\n\n⏳ $i ثانية..."
            delay(1000)
        }

        _searchResponseText.value = "🔄 تجديد reese84..."
        try {
            withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
            activeCookie = hyperSession.getCookieHeader()
            _lastHyperRenewTime = System.currentTimeMillis()
            addLog("✅ reese84 جديد")
        } catch (e: Exception) {
            _searchResponseText.value = "❌ فشل تجديد reese84\n\n${e.message}"
            return false
        }

        _searchResponseText.value = "🌐 التقاط JWT جديد..."
        _needsWebViewRefresh.value = true
        _autoCaptureMode.value = true
        var waited = 0
        while (_needsWebViewRefresh.value && coroutineContext.isActive && _autoLoopActive.value && waited < 60) {
            delay(1000)
            waited++
        }

        if (!hyperSession.hasFreshToken() || activeSaaSessionId.isBlank()) {
            _searchResponseText.value = "❌ فشل التقاط JWT"
            return false
        }

        _renewAttemptsAfter403 = 0
        _consecutiveSilentBlocks = 0
        _requestTimestamps.clear()
        _sessionExpired = false
        _lastErrorCode = 0

        _searchResponseText.value = "✅ تم تغيير IP + تجديد الجلسة\n\n▶️ استئناف البحث..."
        addLog("✅ اكتمل - استئناف البحث")
        return true
    }

    fun markSessionReady() {
        _sessionReady.value = hyperSession.hasFreshToken() && activeSaaSessionId.isNotBlank()
        _jwtReady.value = activeJwt.isNotBlank()
        _sessionStatus.value = when {
            !hyperSession.hasFreshToken() -> "❌ جلسة الهايبر غير مفعلة"
            hyperSession.hasFreshToken() && activeSaaSessionId.isBlank() -> "🔄 جاري تجديد الجلسة..."
            hyperSession.hasFreshToken() && activeSaaSessionId.isNotBlank() -> "✅ جلسة الهايبر جاهزة"
            else -> "⏳ جاهزة عند أول طلب"
        }
    }

    fun markSessionPreparing() {
        _sessionReady.value = false
        _jwtReady.value = false
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
        lastBookedFlight = null
        _searchLogs.value = emptyList()
        _searchResponseText.value = "بدء البحث..."
    }

    fun sessionExpiryMs(): Long = hyperSession.expiryAt()

    fun refreshSession() {
        viewModelScope.launch {
            try {
                markSessionPreparing()
                withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
                _lastHyperRenewTime = System.currentTimeMillis()
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
        _jwtReady.value = jwt.isNotBlank()
        if (sid.isNotBlank()) activeSaaSessionId = sid
        activeCookie = hyperSession.getCookieHeader()
        activeXdToken = hyperSession.getToken() ?: ""
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

    fun startAutoLoop() {
        if (autoLoopJob?.isActive == true) return
        _autoLoopActive.value = true
        _needsWebViewRefresh.value = false
        _sessionExpired = false
        _consecutiveSilentBlocks = 0
        _totalSilentBlocksInSession = 0
        _renewAttemptsAfter403 = 0
        _requestTimestamps.clear()
        _lastHyperRenewTime = 0L

        acquireWakeLocks()

        autoLoopJob = viewModelScope.launch {
            addLog("🔁 بدء البحث المستمر (2-5s)...")
            _searchResponseText.value = "بدء البحث..."

            try {
                withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
                activeCookie = hyperSession.getCookieHeader()
                _lastHyperRenewTime = System.currentTimeMillis()
            } catch (e: Exception) {
                addLog("❌ فشل تجديد البداية: ${e.message}")
            }

            var attempt = 0

            while (isActive && _autoLoopActive.value) {
                try {
                    val timerExpired = _sessionTimerSeconds.value <= 0L && hyperSession.expiryAt() > 0L
                    // 🆕 كل 10 دقائق: تجديد Hyper + WebView
                    val hyperIntervalExpired = _lastHyperRenewTime > 0L &&
                            (System.currentTimeMillis() - _lastHyperRenewTime) >= HYPER_RENEW_INTERVAL_MS

                    val needRenew = _sessionExpired || timerExpired || hyperIntervalExpired ||
                            activeSaaSessionId.isBlank() || activeJwt.isBlank() || !hyperSession.hasFreshToken()

                    if (needRenew) {
                        val reason = when {
                            _sessionExpired && _lastErrorCode == 401 -> "🚨 401 — انتهت الجلسة"
                            _sessionExpired && _lastErrorCode == 403 -> "🚨 403 — محاولة $_renewAttemptsAfter403/3"
                            _sessionExpired && _lastErrorCode == 200 -> "🛡️ حظر صامت"
                            hyperIntervalExpired -> "⏱️ 10 دقائق — تجديد دوري"
                            timerExpired -> "⏰ انتهى العداد"
                            activeSaaSessionId.isBlank() -> "⚠️ sid مفقود"
                            activeJwt.isBlank() -> "⚠️ JWT مفقود"
                            else -> "⚠️ reese84 منتهي"
                        }

                        _searchResponseText.value = buildString {
                            appendLine("⚙️ تجديد الجلسة تلقائياً...")
                            appendLine()
                            appendLine("📌 السبب: $reason")
                            if (_lastErrorCode == 403) {
                                appendLine("📊 محاولات 403: $_renewAttemptsAfter403/3")
                            }
                        }

                        addLog("⏸️ إيقاف — $reason")
                        markSessionPreparing()
                        _sessionExpired = false

                        // ═══ 1. تجديد Hyper (دائماً) ═══
                        try {
                            withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
                            activeCookie = hyperSession.getCookieHeader()
                            _lastHyperRenewTime = System.currentTimeMillis()
                            addLog("✅ reese84 جُدد")
                        } catch (e: Exception) {
                            addLog("❌ فشل reese84: ${e.message}")
                            delay(10000)
                            continue
                        }

                        // ═══ 2. WebView يُفتح دائماً (كل تجديد) ═══
                        _searchResponseText.value = buildString {
                            appendLine("🌐 فتح WebView لالتقاط JWT...")
                            appendLine()
                            appendLine("📌 السبب: تجديد الجلسة ($reason)")
                        }
                        _needsWebViewRefresh.value = true
                        _autoCaptureMode.value = true
                        var waited = 0
                        while (_needsWebViewRefresh.value && isActive && _autoLoopActive.value && waited < 60) {
                            delay(1000)
                            waited++
                        }

                        if (!hyperSession.hasFreshToken() || activeSaaSessionId.isBlank()) {
                            addLog("⚠️ فشل التقاط JWT — إعادة")
                            delay(10000)
                            continue
                        }

                        _lastErrorCode = 0
                        markSessionReady()
                        addLog("✅ الجلسة جُددت — استئناف")
                        continue
                    }

                    val throttleDelay = calculateThrottleDelay()
                    attempt++
                    val flight = searchAttempt(attempt)

                    if (!_autoLoopActive.value) break

                    if (_sessionExpired) {
                        addLog("🚨 خطأ $_lastErrorCode — إعادة")
                        continue
                    }

                    if (flight != null) {
                        addLog("✅ وجدت: ${flight.price} SAR")
                        clearResults()
                        val success = executeBooking(flight)
                        if (success) {
                            addLog("🎉 انتهى الحجز")
                            _autoLoopActive.value = false
                            break
                        } else {
                            addLog("❌ فشل الحجز — إعادة")
                            delay(10000)
                        }
                    } else {
                        delay(throttleDelay)
                    }
                } catch (e: Exception) {
                    addLog("❌ خطأ في الجولة: ${e.message}")
                    delay(5000)
                }
            }
            _autoLoopActive.value = false
            releaseWakeLocks()
            addLog("⏹️ توقف البحث")
        }
    }

    fun stopAutoLoop() {
        autoLoopJob?.cancel(); autoLoopJob = null
        _autoLoopActive.value = false
        _needsWebViewRefresh.value = false
        _autoCaptureMode.value = false
        releaseWakeLocks()
        addLog("⏹️ إيقاف يدوي")
    }

    private fun isSilentBlock(rawBody: String, contentType: String): Boolean {
        if (rawBody.isBlank()) return false
        if (contentType.contains("text/html", ignoreCase = true)) return true
        if (rawBody.contains("Pardon Our Interruption", ignoreCase = true)) return true
        if (rawBody.contains("_Incapsula_Resource", ignoreCase = true)) return true
        if (rawBody.contains("Incapsula incident ID", ignoreCase = true)) return true
        if (rawBody.contains("Request unsuccessful", ignoreCase = true)) return true
        if (rawBody.length < 1500 && rawBody.contains("<html", ignoreCase = true)) return true
        return false
    }

    private suspend fun handleSilentBlock(attempt: Int): Boolean {
        _consecutiveSilentBlocks++
        _totalSilentBlocksInSession++
        val level = _consecutiveSilentBlocks

        when {
            level == 1 -> {
                _searchResponseText.value = "🛡️ حظر صامت #1\n\n📌 Imperva أعاد HTML بدل JSON\n🔧 الإجراء: تجديد الجلسة\n📊 المتتالي: $level | الإجمالي: $_totalSilentBlocksInSession"
                addLog("🛡️ حظر صامت #1")
                return true
            }
            level in 2..3 -> {
                _searchResponseText.value = "🛡️ حظر صامت #$level\n\n⏳ انتظار 30 ثانية...\n📊 المتتالي: $level | الإجمالي: $_totalSilentBlocksInSession"
                addLog("🛡️ حظر #$level — 30ث")
                delay(30_000)
                return true
            }
            level == 4 -> {
                _searchResponseText.value = "🛡️ حظر شديد #$level\n\n⏳ انتظار دقيقتين...\n📊 المتتالي: $level"
                addLog("🛡️ حظر #$level — دقيقتين")
                delay(120_000)
                return true
            }
            else -> {
                _searchResponseText.value = "🚨 حظر مستمر (#$level)\n\n🔧 جرّب VPN أو انتظر 5 دقائق\n\n⏹️ تم إيقاف البحث"
                addLog("🚨 حظر مستمر — إيقاف")
                return false
            }
        }
    }

    private suspend fun searchAttempt(attempt: Int): FoundFlightDetails? {
        val wantedTime = FlightData.normalizeTime(flightData.departureTime)
        val wantedCodes = FareFamilies.FARE_CODE_MAP[flightData.cabinClass] ?: emptyList()

        setStep("search", StepStatus.RUNNING, "محاولة $attempt")
        _searchResponseText.value = "⏳ إرسال الطلب... ($attempt)"

        _requestTimestamps.add(System.currentTimeMillis())

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

            val contentType = resp.headers()["Content-Type"] ?: ""

            if (resp.isSuccessful) {
                val rawBody = try {
                    com.google.gson.Gson().toJson(resp.body())
                } catch (e: Exception) { "" }

                if (isSilentBlock(rawBody, contentType)) {
                    _lastErrorCode = 200
                    val shouldContinue = handleSilentBlock(attempt)
                    if (shouldContinue) {
                        _sessionExpired = true
                    } else {
                        _autoLoopActive.value = false
                    }
                    return null
                }

                _consecutiveSilentBlocks = 0
                _renewAttemptsAfter403 = 0

                val body = resp.body() ?: return null
                val flights = parseAirBounds(body)
                val match = findMatchingFlight(flights, wantedTime, wantedCodes)
                val matchedFlightNumber = match?.first?.flightNumber
                val matchedFareCode = match?.second?.code

                val nowMs = System.currentTimeMillis()
                _requestTimestamps.removeAll { it < nowMs - THROTTLE_WINDOW_MS }
                val recentCount = _requestTimestamps.size
                val counterStatus = when {
                    recentCount < THROTTLE_STOP_THRESHOLD ->
                        "🟢 $recentCount/$THROTTLE_STOP_THRESHOLD (سرعة كاملة 2-5s)"
                    else ->
                        "🔴 $recentCount/$THROTTLE_STOP_THRESHOLD (توقف)"
                }

                _searchResponseText.value = buildString {
                    appendLine(counterStatus)
                    appendLine("📊 ${flights.size} رحلة | محاولة $attempt | HTTP ${resp.code()}")
                    appendLine()
                    if (flights.isEmpty()) {
                        appendLine("⚠️ لم تُستخرج رحلة")
                    } else {
                        flights.forEachIndexed { idx, f ->
                            val isMatched = f.flightNumber == matchedFlightNumber
                            val prefix = if (isMatched) "🎯" else "  "
                            val directStr = if (f.isDirect) "مباشر" else "${f.stops}st"
                            appendLine("$prefix ${idx + 1}. ${f.flightNumber} @ ${f.departTime}→${f.arrivalTime} | $directStr")
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
                    addLog("🎯 مطابقة: ${flight.flightNumber}")
                    return FoundFlightDetails(
                        flightNumber = flight.flightNumber, date = flightData.date,
                        origin = flight.origin, destination = flight.destination,
                        departureTime = flight.departTime, price = fare.price,
                        fareFamily = fare.code, airBoundId = fare.airBoundId
                    )
                }
                return null
            }

            if (resp.code() == 403) {
                _renewAttemptsAfter403++
                _lastErrorCode = 403
                _lastErrorMessage = "محظور (Imperva)"

                if (_renewAttemptsAfter403 >= 3) {
                    addLog("🚨 3× 403 — تفعيل الطيران التلقائي")
                    val success = performAirplaneModeRenewal()
                    if (!success) _autoLoopActive.value = false
                    return null
                }

                _searchResponseText.value = buildString {
                    appendLine("🚨 HTTP 403 — محاولة $_renewAttemptsAfter403/3")
                    appendLine()
                    appendLine("📌 Imperva رفض الطلب")
                    appendLine()
                    if (_renewAttemptsAfter403 < 3) {
                        appendLine("🔧 جاري تجديد الجلسة...")
                        appendLine("⚠️ بعد 3 محاولات → وضع الطيران تلقائياً")
                    }
                }
                addLog("🚨 403 — تجديد ($_renewAttemptsAfter403/3)")
                _sessionExpired = true
                return null
            }

            if (resp.code() == 401) {
                _lastErrorCode = 401
                _searchResponseText.value = "🚨 HTTP 401\n\n📌 JWT منتهي\n\n🔧 تجديد الجلسة..."
                _sessionExpired = true
                return null
            }

            if (resp.code() == 429) {
                val retryAfter = resp.headers()["Retry-After"]?.toLongOrNull() ?: 60L
                _lastErrorCode = 429
                _searchResponseText.value = "🚨 HTTP 429\n\n⏱️ انتظار $retryAfter ثانية..."
                delay(retryAfter * 1000)
                return null
            }

            if (resp.code() >= 500) {
                _lastErrorCode = resp.code()
                _searchResponseText.value = "🚨 HTTP ${resp.code()}\n\n⏳ انتظار 15 ثانية..."
                delay(15000)
                return null
            }

        } catch (e: IOException) {
            _searchResponseText.value = "🚨 خطأ شبكة\n\n${e.message}"
        } catch (e: Exception) {
            _searchResponseText.value = "🚨 ${e.javaClass.simpleName}\n\n${e.message}"
        }
        return null
    }

    private suspend fun executeBooking(flight: FoundFlightDetails): Boolean {
        try {
            setStep("search", StepStatus.SUCCESS, "${flight.price} SAR")
            setStep("cart", StepStatus.RUNNING)
            val cid = createCart(flight.airBoundId) ?: run { setStep("cart", StepStatus.FAILED); return false }
            setStep("cart", StepStatus.SUCCESS, cid)
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
            _finalPnr.value = ord.first
            _finalSadad.value = ord.second
            lastBookedFlight = flight
            setStep("order", StepStatus.SUCCESS, "PNR: ${ord.first} | SADAD: ${ord.second}")
            if (monitoringMode) { setStep("payment", StepStatus.SKIPPED, "👁️ المراقبة فقط"); return true }
            setStep("payment", StepStatus.RUNNING)
            if (!submitPayment(ord.first)) { setStep("payment", StepStatus.FAILED); return false }
            setStep("payment", StepStatus.SUCCESS, "✅ SADAD: ${ord.second}")
            return true
        } catch (e: Exception) {
            addLog("❌ ${e.message}")
            return false
        }
    }

    private fun parseAirBounds(response: SaudiaResponse): List<ParsedFlight> {
        val groups = response.data?.airBoundGroups ?: return emptyList()
        val flightDict = response.data?.dictionaries?.flight ?: emptyMap()
        val flights = mutableListOf<ParsedFlight>()

        for (group in groups) {
            val bd = group.boundDetails ?: continue
            val segments = bd.segments ?: continue
            if (segments.isEmpty()) continue
            val flightId = segments[0].flightId ?: continue
            val fnMatch = Regex("SEG-([A-Z]{2}\\d+)-").find(flightId)
            val flightNumber = fnMatch?.groupValues?.get(1) ?: "?"
            val dtMatch = Regex("-(\\d{4}-\\d{2}-\\d{2})-(\\d{4})$").find(flightId)
            val timeRaw = dtMatch?.groupValues?.get(2) ?: "0000"
            val departTime = timeRaw.substring(0, 2) + ":" + timeRaw.substring(2, 4)
            val flightInfo = flightDict[flightId]
            val arrivalDateTime = flightInfo?.arrival?.dateTime ?: ""
            val arrMatch = Regex("T(\\d{2}:\\d{2})").find(arrivalDateTime)
            val arrivalTime = arrMatch?.groupValues?.get(1) ?: "?"
            val fares = mutableListOf<ParsedFare>()
            for (ab in group.airBounds ?: emptyList()) {
                val code = ab.fareFamilyCode ?: "?"
                val isSoldOut = ab.status?.value == "soldOut"
                val avail = ab.availabilityDetails?.firstOrNull()
                val quota = avail?.quota ?: 0
                val statusCode = avail?.statusCode ?: ""
                val isAvailable = !isSoldOut && quota > 0 && statusCode == "HK"
                val priceValue = ab.prices?.totalPrices?.firstOrNull()?.total
                    ?: ab.airOffer?.totalPrice?.value ?: 0.0
                val price = priceValue / 100.0
                fares.add(ParsedFare(code, quota, statusCode, price, isAvailable, ab.airBoundId ?: ""))
            }
            flights.add(ParsedFlight(
                flightNumber = flightNumber, departTime = departTime, arrivalTime = arrivalTime,
                origin = bd.origin ?: "", destination = bd.destination ?: "",
                isDirect = segments.size == 1, stops = segments.size - 1, fares = fares
            ))
        }
        return flights
    }

    private fun findMatchingFlight(flights: List<ParsedFlight>, wantedTime: String, wantedCodes: List<String>): Pair<ParsedFlight, ParsedFare>? {
        for (flight in flights) {
            if (flightData.directOnly && !flight.isDirect) continue
            if (wantedTime.isNotBlank()) {
                val t = wantedTime.trim()
                val tu = t.uppercase()
                val isFlightNumber = Regex("^SV\\d{3,4}$").matches(tu)
                val isTimeFormat = Regex("^\\d{1,2}:\\d{2}$").matches(t)
                var matchedTime = false
                when {
                    isFlightNumber -> if (flight.flightNumber == tu) matchedTime = true
                    isTimeFormat -> if (flight.departTime == t || flight.arrivalTime == t) matchedTime = true
                    else -> if (flight.flightNumber.contains(t) || flight.departTime.contains(t)) matchedTime = true
                }
                if (!matchedTime) continue
            }
            val matchedFare = flight.fares.firstOrNull { it.isAvailable && wantedCodes.contains(it.code) } ?: continue
            return Pair(flight, matchedFare)
        }
        return null
    }

    private fun buildHeaders(apiVersion: String, referer: String = "https://www.saudia.com/"): Map<String, String> {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)
        val headers = mutableMapOf(
            "Authorization" to activeJwt, "x-d-token" to activeXdToken, "saa-session-id" to activeSaaSessionId,
            "x-api-version" to apiVersion, "platform" to "Web", "version" to "27.2",
            "Accept" to "application/json", "Content-Type" to "application/json",
            "Origin" to "https://www.saudia.com", "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36",
            "Accept-Language" to "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "request-id" to "|$traceId.$spanId", "traceparent" to "00-$traceId-$spanId-01",
            "x-datadog-origin" to "rum",
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
            val s = r.body()?.string() ?: ""
            JSONObject(s).optJSONObject("data")?.optString("id")?.takeIf { it.isNotEmpty() } ?: JSONObject(s).optString("id")
        }
    } catch (e: Exception) { null }

    private suspend fun fetchCartDetails(cid: String): Pair<Double, String>? = try {
        val r = RetrofitClient.api.getCartDetails(cid, headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"))
        saveCookiesFromResponse(r)
        if (!r.isSuccessful) null else {
            val s = r.body()?.string() ?: ""
            val j = JSONObject(s)
            var p = 0.0; var f = ""
            val d = j.optJSONObject("data")
            val g = d?.optJSONArray("airBoundGroups")
            if (g != null && g.length() > 0) {
                val ab = g.optJSONObject(0)?.optJSONArray("airBounds")?.optJSONObject(0)
                f = ab?.optString("fareFamilyCode", "") ?: ""
                p = (ab?.optJSONObject("airOffer")?.optJSONObject("totalPrice")?.optDouble("value", 0.0) ?: 0.0) / 100.0
            }
            Pair(p, f)
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
