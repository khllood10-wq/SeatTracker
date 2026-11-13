package com.example.saudiaseattracker

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Response
import java.util.UUID
import kotlin.random.Random
import io.github.sardanioss.httpcloak.Body

data class PassengerData(
    val title: String = "Mr",
    val firstName: String = "Ebrahim",
    val lastName: String = "Otudi",
    val dateOfBirth: String = "1990-02-18",
    val nationalId: String = "1065314187",
    val nationality: String = "SA",
    val gender: String = "male",
    val email: String = "aletwade@gmail.com",
    val phone: String = "543309909"
) {
    fun isValid(): Boolean =
        firstName.isNotBlank() && lastName.isNotBlank() &&
        dateOfBirth.isNotBlank() && nationalId.length == 10 &&
        email.contains("@") && phone.length >= 9
}

data class FlightData(
    val from: String = "JED",
    val to: String = "AHB",
    val date: String = "2026-11-17",
    val flightNumber: String = "SV8844"
) {
    fun isValid(): Boolean =
        from.length == 3 && to.length == 3 &&
        date.isNotBlank() && flightNumber.isNotBlank()
}

data class FoundFlightDetails(
    val flightNumber: String,
    val date: String,
    val origin: String,
    val destination: String,
    val price: Double,
    val fareFamily: String,
    val airBoundId: String
)

enum class StepStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

data class BookingStep(
    val id: String,
    val title: String,
    val status: StepStatus = StepStatus.PENDING,
    val detail: String = ""
)

class FlightViewModel(application: Application) : AndroidViewModel(application) {

    private val httpCloakClient = HttpCloakClient()

    private var activeJwt = ""
    private var activeCookie: String? = null
    private var activeSaaSessionId = ""
    private var activeXdToken = ""

    private val hyperSession: HyperSessionManager by lazy {
        HyperSessionManager(
            context = getApplication<Application>(),
            rawApiKey = BuildConfig.HYPER_API_KEY,
            userAgent = HttpCloakClient.UA,  // ← Chrome/155 Windows (موحّد)
            acceptLanguage = "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    var passengerData by mutableStateOf(PassengerData())
    var flightData by mutableStateOf(FlightData())
    var monitoringMode by mutableStateOf(false)

    private val _steps = MutableStateFlow(
        listOf(
            BookingStep("search", "البحث عن الرحلة"),
            BookingStep("cart", "إنشاء سلة الحجز"),
            BookingStep("passenger", "تأكيد بيانات المسافر"),
            BookingStep("extension", "حفظ التفعيل (YQENSTATUS)"),
            BookingStep("contacts", "حفظ بيانات الاتصال"),
            BookingStep("order", "إنشاء الطلب (PNR + SADAD)"),
            BookingStep("payment", "إرسال طلب السداد")
        )
    )
    val steps: StateFlow<List<BookingStep>> = _steps

    private val _searchLogs = MutableStateFlow<List<String>>(emptyList())
    val searchLogs: StateFlow<List<String>> = _searchLogs

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _finalPnr = MutableStateFlow("")
    val finalPnr: StateFlow<String> = _finalPnr

    private val _finalSadad = MutableStateFlow("")
    val finalSadad: StateFlow<String> = _finalSadad

    private val _foundFlight = MutableStateFlow<FoundFlightDetails?>(null)
    val foundFlight: StateFlow<FoundFlightDetails?> = _foundFlight

    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady

    private val _sessionStatus = MutableStateFlow("⏳ جاري تجهيز جلسة Hyper...")
    val sessionStatus: StateFlow<String> = _sessionStatus

    private val _debugEntries = MutableStateFlow<List<DebugEntry>>(emptyList())
    val debugEntries: StateFlow<List<DebugEntry>> = _debugEntries

    fun pushDebug(entry: DebugEntry) { DebugBus.emit(entry) }
    fun clearDebugLog() { _debugEntries.value = emptyList() }

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
                addLog("✅ جلسة Hyper جاهزة")
            } catch (e: Exception) {
                _sessionStatus.value = "⚠️ تعذر التجهيز: ${e.message}"
                addLog("❌ فشل Hyper: ${e.message}")
            }
        }
    }

    fun hasToken(): Boolean = hyperSession.hasFreshToken()

    fun markSessionReady() {
        _sessionReady.value = hyperSession.hasFreshToken()
        _sessionStatus.value = if (_sessionReady.value) "✅ جلسة Hyper جاهزة" else "⏳ جاهزة عند أول طلب"
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
        _steps.value = _steps.value.map {
            if (it.id == id) it.copy(status = status, detail = detail) else it
        }
    }

    fun clearResults() {
        _steps.value = _steps.value.map { it.copy(status = StepStatus.PENDING, detail = "") }
        _finalPnr.value = ""
        _finalSadad.value = ""
        _searchLogs.value = emptyList()
    }

    fun sessionExpiryMs(): Long = hyperSession.expiryAt()

    fun refreshSession() {
        viewModelScope.launch {
            try {
                markSessionPreparing()
                withContext(Dispatchers.IO) { hyperSession.ensureFreshToken(forceRenew = true) }
                markSessionReady()
                addLog("✅ تم تجديد reese84 يدوياً")
            } catch (e: Exception) {
                addLog("❌ فشل التجديد: ${e.message}")
                markSessionPreparing()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  🧪 اختبار httpcloak الشامل — مع Warmup
    // ═══════════════════════════════════════════════════════════
    fun testHttpCloak() {
        viewModelScope.launch {
            try {
                addLog("🧪 بدء اختبار httpcloak الشامل...")
                pushDebug(DebugEntry(
                    source = DebugSource.APP,
                    title = "═══ 🧪 اختبار httpcloak بدأ ═══",
                    body = "الخطوات:\n1. تأكيد reese84\n2. resetSession\n3. Warmup (تهيئة الاتصال)\n4. GET /ar-SA الفعلي"
                ))

                withContext(Dispatchers.IO) { hyperSession.ensureFreshToken() }
                val reeseCookie = hyperSession.getCookieHeader() ?: run {
                    addLog("❌ لا reese84"); return@launch
                }
                addLog("✅ reese84: ${reeseCookie.take(40)}... (${reeseCookie.length}c)")

                httpCloakClient.resetSession()
                delay(500)

                // ✅ الخطوة الجديدة: Warmup
                addLog("🔥 Warmup (تهيئة الاتصال)...")
                val warmupHeaders = buildFullChromeHeaders(
                    cookie = reeseCookie,
                    referer = "https://www.saudia.com/",
                    secFetchSite = "none",
                    secFetchMode = "navigate",
                    secFetchDest = "document"
                )
                try {
                    httpCloakClient.warmup(
                        url = "https://www.saudia.com/",
                        headers = warmupHeaders
                    )
                    addLog("✅ Warmup انتهى بنجاح")
                } catch (e: Exception) {
                    addLog("⚠️ Warmup فشل: ${e.javaClass.simpleName} - ${e.message}")
                }
                delay(1500)

                // الآن الطلب الفعلي
                addLog("🌐 GET /ar-SA (الطلب الفعلي)...")
                val resp = httpCloakClient.get(
                    url = "https://www.saudia.com/ar-SA",
                    headers = warmupHeaders
                )

                val blocked = resp.text.contains("Pardon Our Interruption") ||
                        resp.text.contains("_Incapsula_Resource")

                addLog("GET: HTTP ${resp.statusCode}, len=${resp.text.length}, blocked=$blocked")

                if (!blocked && resp.statusCode == 200) {
                    addLog("🎉 نجاح! httpcloak تجاوز Imperva!")
                } else {
                    addLog("⚠️ لا يزال Imperva يحظر — راجع Debug")
                }

            } catch (e: Exception) {
                addLog("❌ استثناء: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  🌐 headers كاملة كما يفعل Chrome الحقيقي
    // ═══════════════════════════════════════════════════════════
    private fun buildFullChromeHeaders(
        cookie: String,
        referer: String,
        secFetchSite: String,
        secFetchMode: String,
        secFetchDest: String,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> = buildMap {
        put("Cookie", cookie)
        put("User-Agent", HttpCloakClient.UA)
        put(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
        )
        put("Accept-Language", "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7")
        put("Accept-Encoding", "gzip, deflate, br, zstd")
        put("Sec-Fetch-Site", secFetchSite)
        put("Sec-Fetch-Mode", secFetchMode)
        put("Sec-Fetch-Dest", secFetchDest)
        put("Sec-Fetch-User", "?1")
        put("Upgrade-Insecure-Requests", "1")
        put("Referer", referer)
        extra.forEach { (k, v) -> put(k, v) }
    }

    // ═══════════════════════════════════════════════════════════
    //  📡 captureSession — المحاولة الحقيقية
    // ═══════════════════════════════════════════════════════════
    private suspend fun captureSessionViaHttpCloak(): Boolean = withContext(Dispatchers.IO) {
        try {
            val reeseCookie = hyperSession.getCookieHeader() ?: run {
                addLog("❌ لا يوجد reese84")
                return@withContext false
            }

            httpCloakClient.resetSession()
            delay(300)

            // ─── 0) Warmup قبل التحدي ───
            addLog("🔥 Warmup...")
            try {
                httpCloakClient.warmup(
                    url = "https://www.saudia.com/",
                    headers = buildFullChromeHeaders(
                        cookie = reeseCookie,
                        referer = "https://www.saudia.com/",
                        secFetchSite = "none",
                        secFetchMode = "navigate",
                        secFetchDest = "document"
                    )
                )
                addLog("✅ Warmup OK")
            } catch (e: Exception) {
                addLog("⚠️ Warmup fail: ${e.message}")
            }
            delay(1500)

            // ─── 1) GET /ar-SA ───
            addLog("🌐 GET #1 /ar-SA...")
            val pageResp1 = httpCloakClient.get(
                url = "https://www.saudia.com/ar-SA",
                headers = buildFullChromeHeaders(
                    cookie = reeseCookie,
                    referer = "https://www.saudia.com/",
                    secFetchSite = "none",
                    secFetchMode = "navigate",
                    secFetchDest = "document"
                )
            )
            addLog("📄 GET #1 → HTTP ${pageResp1.statusCode}, len=${pageResp1.text.length}")
            delay(2500)

            // ─── 2) GET /ar-SA ───
            addLog("🌐 GET #2 /ar-SA...")
            val pageResp2 = httpCloakClient.get(
                url = "https://www.saudia.com/ar-SA",
                headers = buildFullChromeHeaders(
                    cookie = reeseCookie,
                    referer = "https://www.saudia.com/",
                    secFetchSite = "none",
                    secFetchMode = "navigate",
                    secFetchDest = "document"
                )
            )
            val stillBlocked = pageResp2.text.contains("Pardon Our Interruption") ||
                    pageResp2.text.contains("_Incapsula_Resource")
            addLog("📄 GET #2 → HTTP ${pageResp2.statusCode}, len=${pageResp2.text.length}, blocked=$stillBlocked")

            if (stillBlocked || pageResp2.statusCode != 200) {
                addLog("❌ لا يزال Imperva يحظر بعد GET #2")
                pushDebug(DebugEntry(
                    source = DebugSource.HTTPCLOAK,
                    title = "❌ httpcloak لم يتجاوز Imperva",
                    body = "GET#2 status=${pageResp2.statusCode}\nblocked=$stillBlocked\n\n${pageResp2.text.take(3000)}"
                ))
                return@withContext false
            }

            addLog("🎉 httpcloak تجاوز Imperva!")
            delay(1500)

            // ─── 3) POST /token/init ───
            val attempts = listOf(
                "no-body" to null,
                "empty-form" to Body.form(emptyMap())
            )

            for ((name, body) in attempts) {
                addLog("🔄 token/init ($name)...")
                val initResp = httpCloakClient.post(
                    url = "https://dapi.saudia.com/session/auth/web/token/initialization",
                    headers = buildFullChromeHeaders(
                        cookie = reeseCookie,
                        referer = "https://www.saudia.com/ar-SA",
                        secFetchSite = "same-site",
                        secFetchMode = "cors",
                        secFetchDest = "empty",
                        extra = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Accept" to "application/json, text/plain, */*",
                            "Origin" to "https://www.saudia.com",
                            "platform" to "Web",
                            "version" to "27.2"
                        )
                    ),
                    body = body
                )
                addLog("📄 token/init ($name) → HTTP ${initResp.statusCode}, len=${initResp.text.length}")

                if (initResp.statusCode == 200) {
                    try {
                        val json = JSONObject(initResp.text)
                        val token = json.optString("access_token", json.optString("token", ""))
                        val sessionId = json.optString("session_id", "")
                        if (token.isNotBlank()) {
                            activeJwt = if (token.startsWith("Bearer ")) token else "Bearer $token"
                            if (sessionId.isNotBlank()) activeSaaSessionId = sessionId
                            activeXdToken = ""
                            activeCookie = reeseCookie
                            addLog("🎉 JWT! ${activeJwt.take(30)}...")
                            addLog("🆔 sid: ${if (sessionId.isNotBlank()) sessionId else "❌"}")
                            pushDebug(DebugEntry(
                                source = DebugSource.APP,
                                title = "🎉 JWT captured ($name)",
                                body = "jwt.prefix=${activeJwt.take(60)}...\nsid=$sessionId"
                            ))
                            return@withContext true
                        }
                    } catch (e: Exception) { addLog("⚠️ JSON parse: ${e.message}") }
                }
                delay(1500)
            }

            addLog("⚠️ token/init فشل بكل الطرق")
            return@withContext false

        } catch (e: Exception) {
            addLog("❌ httpcloak exception: ${e.javaClass.simpleName} - ${e.message}")
            pushDebug(DebugEntry(
                source = DebugSource.HTTPCLOAK,
                title = "❌ exception في captureSession",
                body = "${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString().take(2000)}"
            ))
            return@withContext false
        }
    }

    private fun buildHeaders(
        apiVersion: String,
        isWebEndpoint: Boolean = false,
        referer: String = "https://www.saudia.com/"
    ): Map<String, String> {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)

        val headers = mutableMapOf(
            "Authorization" to activeJwt,
            "x-d-token" to activeXdToken,
            "saa-session-id" to activeSaaSessionId,
            "x-api-version" to apiVersion,
            "platform" to "Web",
            "version" to "27.2",
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Origin" to "https://www.saudia.com",
            "Referer" to referer,
            "User-Agent" to HttpCloakClient.UA,
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
        if (!activeCookie.isNullOrBlank()) headers["Cookie"] = activeCookie!!
        return headers
    }

    fun runFullBooking(activity: android.app.Activity) {
        if (_isRunning.value) return
        if (!passengerData.isValid()) { addLog("❌ بيانات المسافر ناقصة"); return }
        if (!flightData.isValid()) { addLog("❌ بيانات الرحلة ناقصة"); return }

        clearResults()
        _isRunning.value = true

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { hyperSession.ensureFreshToken() }
                markSessionReady()

                val captured = captureSessionViaHttpCloak()
                if (!captured) {
                    addLog("⚠️ التقاط الجلسة لم يكتمل")
                    _isRunning.value = false
                    return@launch
                }

                setStep("search", StepStatus.RUNNING)
                val flight = searchUntilFound() ?: run {
                    setStep("search", StepStatus.FAILED); _isRunning.value = false; return@launch
                }
                setStep("search", StepStatus.SUCCESS, "${flight.price} SAR"); _foundFlight.value = flight
                delay(1200)

                setStep("cart", StepStatus.RUNNING)
                val cid = createCart(flight.airBoundId) ?: run {
                    setStep("cart", StepStatus.FAILED); _isRunning.value = false; return@launch
                }
                setStep("cart", StepStatus.SUCCESS, cid); delay(1200)

                setStep("passenger", StepStatus.RUNNING)
                if (!confirmPassenger(cid)) { setStep("passenger", StepStatus.FAILED); _isRunning.value = false; return@launch }
                setStep("passenger", StepStatus.SUCCESS); delay(1200)

                setStep("extension", StepStatus.RUNNING)
                if (!addExtension(cid)) { setStep("extension", StepStatus.FAILED); _isRunning.value = false; return@launch }
                setStep("extension", StepStatus.SUCCESS); delay(1200)

                setStep("contacts", StepStatus.RUNNING)
                if (!saveContacts(cid)) { setStep("contacts", StepStatus.FAILED); _isRunning.value = false; return@launch }
                setStep("contacts", StepStatus.SUCCESS); delay(1200)

                setStep("order", StepStatus.RUNNING)
                val ord = createOrder(cid) ?: run {
                    setStep("order", StepStatus.FAILED); _isRunning.value = false; return@launch
                }
                _finalPnr.value = ord.first; _finalSadad.value = ord.second
                setStep("order", StepStatus.SUCCESS, "PNR: ${ord.first} | SADAD: ${ord.second}"); delay(1200)

                if (monitoringMode) {
                    setStep("payment", StepStatus.SKIPPED, "👁️ لم يُرسل الدفع")
                    _isRunning.value = false; return@launch
                }

                setStep("payment", StepStatus.RUNNING)
                if (!submitPayment(ord.first)) { setStep("payment", StepStatus.FAILED); _isRunning.value = false; return@launch }
                setStep("payment", StepStatus.SUCCESS, "✅ SADAD جاهز")
            } catch (e: Exception) {
                addLog("❌ ${e.message}")
            } finally { _isRunning.value = false }
        }
    }

    private suspend fun searchUntilFound(max: Int = 1000): FoundFlightDetails? {
        var attempt = 0
        while (attempt < max) {
            attempt++
            setStep("search", StepStatus.RUNNING, "$attempt")
            try {
                val resp = RetrofitClient.api.searchAirBounds(
                    headers = buildHeaders("4"),
                    request = SaudiaSearchRequest(
                        airBoundsSearchInputs = AirBoundsSearchInputs(
                            itineraries = listOf(
                                Itinerary(flightData.from, flightData.to, "${flightData.date}T00:00:00.000")
                            ),
                            travelers = listOf(Traveler()),
                            searchPreferences = SearchPreferences()
                        ),
                        offerCodeAvailabilityInput = OfferCodeAvailabilityInput()
                    )
                )
                saveCookiesFromResponse(resp)

                if (resp.code() == 401 || resp.code() == 403) {
                    val errBody = try { resp.errorBody()?.string() ?: "" } catch (e: Exception) { "" }
                    addLog("🚨 ${resp.code()}: ${errBody.take(200)}")
                    return null
                }

                if (resp.isSuccessful) {
                    val g = resp.body()?.data?.airBoundGroups?.firstOrNull { gg ->
                        gg.boundDetails?.segments?.any {
                            it.flightId?.contains(flightData.flightNumber, true) == true
                        } == true
                    }
                    val cheapest = g?.airBounds?.filter { it.status?.value != "soldOut" }
                        ?.minByOrNull { it.airOffer?.totalPrice?.value ?: Double.MAX_VALUE }
                    if (cheapest != null) {
                        return FoundFlightDetails(
                            flightData.flightNumber, flightData.date,
                            g.boundDetails?.origin ?: flightData.from,
                            g.boundDetails?.destination ?: flightData.to,
                            (cheapest.airOffer?.totalPrice?.value ?: 0.0) / 100.0,
                            cheapest.fareFamilyCode ?: "N/A",
                            cheapest.airBoundId ?: ""
                        )
                    }
                } else {
                    addLog("❌ بحث $attempt: HTTP ${resp.code()}")
                }
            } catch (e: Exception) { addLog("❌ ${e.message}") }
            delay(15_000)
        }
        return null
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
        val r = RetrofitClient.api.createCart(
            headers = buildHeaders("2"),
            request = CreateCartRequest(listOf(airBoundId))
        )
        saveCookiesFromResponse(r)
        if (!r.isSuccessful) null else {
            val bodyStr = r.body()?.string() ?: ""
            val j = JSONObject(bodyStr)
            val dataObj = j.optJSONObject("data")
            dataObj?.optString("id")?.takeIf { it.isNotEmpty() } ?: j.optString("id")
        }
    } catch (e: Exception) { null }

    private suspend fun confirmPassenger(cid: String): Boolean = try {
        val p = passengerData
        val payload = """{"passengerTypeCode":"ADT","gender":"${p.gender}","dateOfBirth":"${p.dateOfBirth}","names":[{"firstName":"${p.firstName}","lastName":"${p.lastName}","title":"${p.title}","nameType":"universal"}],"regulatoryDetails":[{"regulatoryApisType":"ApisDetailRegulatoryDocument","regulatoryDocument":{"number":"${p.nationalId}","name":{"firstName":"${p.firstName.uppercase()}","lastName":"${p.lastName.uppercase()}","nameType":"universal"},"nationalityCode":"${p.nationality}","gender":"${p.gender}","birthDate":"${p.dateOfBirth}","documentType":"identityCard"}}]}"""
        val r = RetrofitClient.api.updateTravelerRaw(
            cid, "SKH-1-EXT",
            headers = buildHeaders("2", referer = "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = payload.toRequestBody("application/json".toMediaType())
        )
        saveCookiesFromResponse(r); r.code() == 200
    } catch (e: Exception) { false }

    private suspend fun addExtension(cid: String): Boolean = try {
        val body = """[{"content":"eyJzdGF0dXMiOiJWRVJJRklFRCIsImhlYWx0aENoZWNrU3RhdHVzIjoiTk9fUkVTUE9OU0UifQ==","travelerId":"SKH-1-EXT","extensionType":"TextExtension","name":"YQENSTATUS"}]"""
        val r = RetrofitClient.api.addCartExtension(
            cid, guestOfficeId = "JEDSV08AA",
            headers = buildHeaders("2", referer = "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = body.toRequestBody("application/json".toMediaType())
        )
        saveCookiesFromResponse(r); r.isSuccessful
    } catch (e: Exception) { false }

    private suspend fun saveContacts(cid: String): Boolean = try {
        val p = passengerData
        val body = """[{"address":"${p.email}","category":"personal","contactType":"Email","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"address":"${p.email}","category":"personal","contactType":"Email","purpose":"standard"},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"${p.phone}","deviceType":"mobile","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"${p.phone}","deviceType":"mobile","purpose":"standard"}]"""
        val r = RetrofitClient.api.saveContacts(
            cid, headers = buildHeaders("2", referer = "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = body.toRequestBody("application/json".toMediaType())
        )
        saveCookiesFromResponse(r); r.isSuccessful
    } catch (e: Exception) { false }

    private suspend fun createOrder(cid: String): Pair<String, String>? = try {
        val r = RetrofitClient.api.createOrderRaw(
            cid, headers = buildHeaders("2", referer = "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = "{}".toRequestBody("application/json".toMediaType())
        )
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
        val r = RetrofitClient.api.submitPayment(
            pnr, p.lastName, headers = buildHeaders("2", referer = "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = body.toRequestBody("application/json".toMediaType())
        )
        saveCookiesFromResponse(r); r.isSuccessful
    } catch (e: Exception) { false }
}
