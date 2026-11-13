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

    // عدادات الحظر الصامت
    private var _consecutiveSilentBlocks = 0
    private var _totalSilentBlocksInSession = 0

    // 🆕 Throttling — نافذة 10 دقائق
    private val _requestTimestamps = mutableListOf<Long>()
    private val THROTTLE_WINDOW_MS = 10 * 60_000L        // 10 دقائق
    private val THROTTLE_WARN_THRESHOLD = 25             // 25 طلب → تبطيء
    private val THROTTLE_STOP_THRESHOLD = 40             // 40 طلب → توقف
    private val THROTTLE_STOP_DURATION_MS = 15_000L      // 🆕 15 ثانية فقط

    // 🆕 WakeLock + WifiLock
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
                markSessionReady()
                addLog("✅ جلسة Hyper جاهزة — فتح WebView تلقائياً...")
                _autoCaptureMode.value = true
                _needsWebViewRefresh.value = true
            } catch (e: Exception) {
                _sessionStatus.value = "❌ جلسة الهايبر غير مفعلة"
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  🆕 WakeLock + WifiLock — للعمل في الخلفية
    // ═══════════════════════════════════════════════════════════
    private fun acquireWakeLocks() {
        if (wakeLock?.isHeld == true) {
            addLog("🔋 WakeLock نشط مسبقاً")
            return
        }

        try {
            val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SaudiaSeatTracker::SearchWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
            addLog("✅ WakeLock مُفعّل — المعالج لن ينام")
        } catch (e: Exception) {
            addLog("⚠️ WakeLock فشل: ${e.message}")
        }

        try {
            val wm = getApplication<Application>().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "SaudiaSeatTracker::SearchWifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            addLog("✅ WifiLock مُفعّل — الواي فاي نشط")
        } catch (e: Exception) {
            addLog("⚠️ WifiLock فشل: ${e.message}")
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {}
        wakeLock = null
        wifiLock = null
        addLog("🔓 WakeLock + WifiLock مُحرّران")
    }

    // ═══════════════════════════════════════════════════════════
    //  🆕 Throttling — نافذة 10 دقائق + توقف 15ث + تصفير فوري
    // ═══════════════════════════════════════════════════════════
    private suspend fun calculateThrottleDelay(): Long {
        val now = System.currentTimeMillis()
        _requestTimestamps.removeAll { it < now - THROTTLE_WINDOW_MS }

        val recentCount = _requestTimestamps.size
        val oldestAge = if (_requestTimestamps.isNotEmpty())
            (now - _requestTimestamps.first()) / 1000 else 0L

        addLog("📊 Throttle: $recentCount طلب | أقدم: ${oldestAge}s")

        return when {
            recentCount < THROTTLE_WARN_THRESHOLD -> {
                // سرعة كاملة 3-5 ثوانٍ
                Random.nextLong(3_000L, 5_000L)
            }
            recentCount < THROTTLE_STOP_THRESHOLD -> {
                // تبطيء 6-8 ثوانٍ
                addLog("🐢 تبطيء: $recentCount/${THROTTLE_STOP_THRESHOLD}")
                _searchResponseText.value = buildString {
                    appendLine("🐢 تبطيء استباقي")
                    appendLine()
                    appendLine("📊 الطلبات في آخر 10 دقائق: $recentCount/${THROTTLE_STOP_THRESHOLD}")
                    appendLine("⏱️ الفاصل الآن: 6-8 ثوانٍ")
                    appendLine("💡 عند الوصول لـ ${THROTTLE_STOP_THRESHOLD} → توقف 15 ثانية")
                    appendLine()
                    appendLine("✅ يحميك من الحظر")
                }
                Random.nextLong(6_000L, 8_000L)
            }
            else -> {
                // 🆕 توقف 15 ثانية + تصفير فوري
                addLog("⏸️ توقف: $recentCount طلب — انتظار 15ث ثم تصفير")
                _searchResponseText.value = buildString {
                    appendLine("⏸️ توقف استباقي (${recentCount} طلب)")
                    appendLine()
                    appendLine("📊 تجاوزت ${THROTTLE_STOP_THRESHOLD} طلب في 10 دقائق")
                    appendLine("⏳ انتظار 15 ثانية لكسر النمط...")
                    appendLine("🔄 ثم تصفير العداد — استئناف بسرعة كاملة")
                    appendLine()
                    appendLine("✅ هذا يحميك من الحظر قبل حدوثه")
                }
                delay(THROTTLE_STOP_DURATION_MS)   // 15 ثانية
                _requestTimestamps.clear()          // 🆕 تصفير فوري
                addLog("🔄 تصفير العداد — استئناف بسرعة كاملة")
                Random.nextLong(3_000L, 5_000L)
            }
        }
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
        _requestTimestamps.clear()

        acquireWakeLocks()

        autoLoopJob = viewModelScope.launch {
            addLog("🔁 بدء البحث المستمر (3-5s + Throttling)...")
            _searchResponseText.value = "بدء البحث..."

            try {
                withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
                activeCookie = hyperSession.getCookieHeader()
            } catch (e: Exception) {
                addLog("❌ فشل تجديد البداية: ${e.message}")
            }

            var attempt = 0

            while (isActive && _autoLoopActive.value) {
                try {
                    val timerExpired = _sessionTimerSeconds.value <= 0L && hyperSession.expiryAt() > 0L
                    val needRenew = _sessionExpired || timerExpired || activeSaaSessionId.isBlank() || activeJwt.isBlank() || !hyperSession.hasFreshToken()

                    if (needRenew) {
                        val reason = when {
                            _sessionExpired && _lastErrorCode == 401 -> "🚨 401 — انتهت الجلسة"
                            _sessionExpired && _lastErrorCode == 403 -> "🚨 403 — محظور (Imperva)"
                            _sessionExpired && _lastErrorCode == 200 -> "🛡️ حظر صامت (200-HTML)"
                            timerExpired -> "⏰ انتهى عداد الجلسة"
                            activeSaaSessionId.isBlank() -> "⚠️ sid مفقود"
                            activeJwt.isBlank() -> "⚠️ JWT مفقود"
                            else -> "⚠️ reese84 منتهي"
                        }

                        _searchResponseText.value = buildString {
                            appendLine("⚙️ تجديد الجلسة تلقائياً...")
                            appendLine()
                            appendLine("📌 السبب: $reason")
                            appendLine()
                            appendLine("🔧 الخطوات:")
                            appendLine("  1️⃣ تجديد reese84 عبر Hyper")
                            appendLine("  2️⃣ فتح WebView لالتقاط JWT")
                            appendLine("  3️⃣ استئناف البحث")
                            appendLine()
                            appendLine("⏳ يرجى الانتظار...")
                        }

                        addLog("⏸️ إيقاف — $reason")
                        markSessionPreparing()
                        _sessionExpired = false
                        _lastErrorCode = 0
                        _lastErrorMessage = ""

                        try {
                            withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
                            activeCookie = hyperSession.getCookieHeader()
                        } catch (e: Exception) {
                            addLog("❌ فشل تجديد reese84: ${e.message}")
                            delay(10000)
                            continue
                        }

                        _needsWebViewRefresh.value = true
                        _autoCaptureMode.value = true
                        var waited = 0
                        while (_needsWebViewRefresh.value && isActive && _autoLoopActive.value && waited < 60) {
                            delay(1000)
                            waited++
                        }

                        if (!hyperSession.hasFreshToken() || activeSaaSessionId.isBlank()) {
                            addLog("⚠️ فشل التجديد — إعادة المحاولة...")
                            delay(10000)
                            continue
                        }

                        addLog("✅ الجلسة جُددت — استئناف البحث")
                        markSessionReady()
                        continue
                    }

                    val throttleDelay = calculateThrottleDelay()

                    attempt++
                    val flight = searchAttempt(attempt)

                    if (!_autoLoopActive.value) break

                    if (_sessionExpired) {
                        addLog("🚨 خطأ $_lastErrorCode — إعادة للتجديد")
                        continue
                    }

                    if (flight != null) {
                        addLog("✅ وجدت: ${flight.price} SAR — بدء الحجز")
                        clearResults()
                        val success = executeBooking(flight)
                        if (success) {
                            addLog("🎉 انتهى الحجز بنجاح")
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
                    _searchResponseText.value = "❌ خطأ غير متوقع:\n${e.message}"
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
        addLog("⏹️ تم الإيقاف يدوياً")
    }

    // ═══════════════════════════════════════════════════════════
    //  🛡️ كشف الحظر الصامت
    // ═══════════════════════════════════════════════════════════
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
                _searchResponseText.value = buildString {
                    appendLine("🛡️ حظر صامت #1 — محاولة $attempt")
                    appendLine()
                    appendLine("📌 Imperva أعاد صفحة HTML بدلاً من JSON")
                    appendLine()
                    appendLine("🔧 الإجراء الفوري:")
                    appendLine("  1️⃣ تجديد reese84 (Hyper)")
                    appendLine("  2️⃣ تجديد JWT (WebView)")
                    appendLine("  3️⃣ استئناف البحث")
                    appendLine()
                    appendLine("📊 المتتالي: $level | الإجمالي: $_totalSilentBlocksInSession")
                }
                addLog("🛡️ حظر صامت #1 — تجديد فوري")
                return true
            }
            level in 2..3 -> {
                _searchResponseText.value = buildString {
                    appendLine("🛡️ حظر صامت #$level — محاولة $attempt")
                    appendLine()
                    appendLine("📌 Imperva يرفض الطلبات بشكل متتالٍ")
                    appendLine()
                    appendLine("🔧 الإجراء المتدرج:")
                    appendLine("  ⏳ انتظار 30 ثانية")
                    appendLine("  🔄 ثم تجديد الجلسة")
                    appendLine()
                    appendLine("📊 المتتالي: $level | الإجمالي: $_totalSilentBlocksInSession")
                }
                addLog("🛡️ حظر #$level — انتظار 30ث")
                delay(30_000)
                return true
            }
            level == 4 -> {
                _searchResponseText.value = buildString {
                    appendLine("🛡️ حظر شديد #$level")
                    appendLine()
                    appendLine("📌 Imperva رفع درجة الشك تجاه IP")
                    appendLine()
                    appendLine("🔧 الإجراء:")
                    appendLine("  ⏳ انتظار دقيقتين")
                    appendLine("  🔄 ثم تجديد الجلسة")
                    appendLine()
                    appendLine("📊 المتتالي: $level | الإجمالي: $_totalSilentBlocksInSession")
                }
                addLog("🛡️ حظر #$level — انتظار دقيقتين")
                delay(120_000)
                return true
            }
            else -> {
                _searchResponseText.value = buildString {
                    appendLine("🚨 حظر مستمر (#$level)")
                    appendLine()
                    appendLine("📌 Imperva يرفض جميع الطلبات من IP الحالي")
                    appendLine()
                    appendLine("📊 المتتالي: $level | الإجمالي: $_totalSilentBlocksInSession")
                    appendLine()
                    appendLine("🔧 الخيارات:")
                    appendLine("  1️⃣ تغيير IP (VPN)")
                    appendLine("  2️⃣ انتظار 5 دقائق")
                    appendLine()
                    appendLine("⏹️ تم إيقاف البحث تلقائياً")
                }
                addLog("🚨 حظر مستمر — إيقاف البحث")
                return false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  🔍 محاولة بحث
    // ═══════════════════════════════════════════════════════════
    private suspend fun searchAttempt(attempt: Int): FoundFlightDetails? {
        val wantedTime = FlightData.normalizeTime(flightData.departureTime)
        val wantedCodes = FareFamilies.FARE_CODE_MAP[flightData.cabinClass] ?: emptyList()

        setStep("search", StepStatus.RUNNING, "محاولة $attempt")
        _searchResponseText.value = "⏳ جاري إرسال الطلب... (محاولة $attempt)"

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
                    _lastErrorMessage = "حظر صامت (HTML بدل JSON)"
                    val shouldContinue = handleSilentBlock(attempt)
                    if (shouldContinue) {
                        _sessionExpired = true
                    } else {
                        _autoLoopActive.value = false
                    }
                    return null
                }

                _consecutiveSilentBlocks = 0

                val body = resp.body() ?: return null
                val flights = parseAirBounds(body)
                val match = findMatchingFlight(flights, wantedTime, wantedCodes)
                val matchedFlightNumber = match?.first?.flightNumber
                val matchedFareCode = match?.second?.code

                // 🆕 عداد Throttling — دائماً في السطر الأول
                val nowMs = System.currentTimeMillis()
                _requestTimestamps.removeAll { it < nowMs - THROTTLE_WINDOW_MS }
                val recentCount = _requestTimestamps.size
                val counterStatus = when {
                    recentCount < THROTTLE_WARN_THRESHOLD ->
                        "🟢 $recentCount/${THROTTLE_WARN_THRESHOLD} طلب في 10د (سرعة كاملة)"
                    recentCount < THROTTLE_STOP_THRESHOLD ->
                        "🟡 $recentCount/${THROTTLE_STOP_THRESHOLD} طلب في 10د (تبطيء)"
                    else ->
                        "🔴 $recentCount/${THROTTLE_STOP_THRESHOLD} طلب في 10د (توقف)"
                }

                _searchResponseText.value = buildString {
                    appendLine(counterStatus)
                    appendLine("📊 ${flights.size} رحلة | محاولة $attempt | HTTP ${resp.code()}")
                    appendLine()
                    if (flights.isEmpty()) {
                        appendLine("⚠️ لم تُستخرج أي رحلة")
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
                    addLog("🎯 مطابقة: ${flight.flightNumber} @ ${flight.departTime} | ${fare.code}")
                    return FoundFlightDetails(
                        flightNumber = flight.flightNumber, date = flightData.date,
                        origin = flight.origin, destination = flight.destination,
                        departureTime = flight.departTime, price = fare.price,
                        fareFamily = fare.code, airBoundId = fare.airBoundId
                    )
                }
                return null
            }

            if (resp.code() == 401) {
                _lastErrorCode = 401
                _lastErrorMessage = "انتهت الجلسة"
                _searchResponseText.value = buildString {
                    appendLine("🚨 HTTP 401 — Unauthorized")
                    appendLine()
                    appendLine("📌 السبب: JWT منتهي")
                    appendLine()
                    appendLine("🔧 الإجراء: تجديد الجلسة تلقائياً...")
                }
                addLog("🚨 401 — تجديد الجلسة")
                _sessionExpired = true
                return null
            }

            if (resp.code() == 403) {
                _lastErrorCode = 403
                _lastErrorMessage = "محظور (Imperva)"
                _searchResponseText.value = buildString {
                    appendLine("🚨 HTTP 403 — Forbidden")
                    appendLine()
                    appendLine("📌 السبب: Imperva رفض الطلب")
                    appendLine()
                    appendLine("🔧 الإجراء: تجديد reese84 + JWT...")
                }
                addLog("🚨 403 — تجديد الجلسة")
                _sessionExpired = true
                return null
            }

            if (resp.code() == 429) {
                val retryAfter = resp.headers()["Retry-After"]?.toLongOrNull() ?: 60L
                _lastErrorCode = 429
                _lastErrorMessage = "عدد الطلبات كبير"
                _searchResponseText.value = buildString {
                    appendLine("🚨 HTTP 429 — Too Many Requests")
                    appendLine()
                    appendLine("⏱️ Retry-After: $retryAfter ثانية")
                    appendLine()
                    appendLine("🔧 الإجراء: انتظار $retryAfter ثانية...")
                }
                addLog("🚨 429 — انتظار ${retryAfter}ث")
                delay(retryAfter * 1000)
                return null
            }

            if (resp.code() >= 500) {
                _lastErrorCode = resp.code()
                _lastErrorMessage = "خطأ في الخادم"
                _searchResponseText.value = buildString {
                    appendLine("🚨 HTTP ${resp.code()} — Server Error")
                    appendLine()
                    appendLine("🔧 الإجراء: انتظار 15 ثانية...")
                }
                addLog("🚨 ${resp.code()} — انتظار 15ث")
                delay(15000)
                return null
            }

            val errBody = try { resp.errorBody()?.string() ?: "" } catch (e: Exception) { "" }
            _searchResponseText.value = buildString {
                appendLine("⚠️ HTTP ${resp.code()}")
                appendLine()
                appendLine(errBody.take(400))
            }
        } catch (e: IOException) {
            _searchResponseText.value = buildString {
                appendLine("🚨 خطأ شبكة")
                appendLine()
                appendLine("${e.message}")
            }
            addLog("🚨 شبكة: ${e.message}")
        } catch (e: Exception) {
            _searchResponseText.value = buildString {
                appendLine("🚨 خطأ غير متوقع")
                appendLine()
                appendLine("${e.javaClass.simpleName}: ${e.message}")
            }
            addLog("🚨 ${e.javaClass.simpleName}: ${e.message}")
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════
    //  💳 تنفيذ الحجز
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
                if (priceMismatch || fareMismatch) addLog("⚠️ تحذير: السلة مختلفة!")
                else addLog("✅ السلة مطابقة: $actualFare @ $actualPrice SAR")
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
            addLog("❌ خطأ في الحجز: ${e.message}")
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
                    ?: ab.airOffer?.totalPrice?.value
                    ?: 0.0
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
            "Authorization" to activeJwt,
            "x-d-token" to activeXdToken,
            "saa-session-id" to activeSaaSessionId,
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
            val bodyStr = r.body()?.string() ?: ""
            JSONObject(bodyStr).optJSONObject("data")?.optString("id")?.takeIf { it.isNotEmpty() } ?: JSONObject(bodyStr).optString("id")
        }
    } catch (e: Exception) { null }

    private suspend fun fetchCartDetails(cid: String): Pair<Double, String>? = try {
        val r = RetrofitClient.api.getCartDetails(cid, headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"))
        saveCookiesFromResponse(r)
        if (!r.isSuccessful) null else {
            val bodyStr = r.body()?.string() ?: ""
            val json = JSONObject(bodyStr)
            var price = 0.0
            var fareFamily = ""
            val data = json.optJSONObject("data")
            val groups = data?.optJSONArray("airBoundGroups")
            if (groups != null && groups.length() > 0) {
                val group = groups.optJSONObject(0)
                val airBounds = group?.optJSONArray("airBounds")
                if (airBounds != null && airBounds.length() > 0) {
                    val ab = airBounds.optJSONObject(0)
                    fareFamily = ab?.optString("fareFamilyCode", "") ?: ""
                    price = (ab?.optJSONObject("airOffer")?.optJSONObject("totalPrice")?.optDouble("value", 0.0) ?: 0.0) / 100.0
                }
            }
            Pair(price, fareFamily)
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
