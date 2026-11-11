package com.example.saudiaseattracker

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Response
import java.util.UUID
import kotlin.random.Random

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

    // ═══════ Hyper Solutions ═══════
    private val hyperSession: HyperSessionManager by lazy {
        HyperSessionManager(
            context = getApplication(),
            apiKey = BuildConfig.HYPER_API_KEY,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
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

    private val _cartId = MutableStateFlow<String?>(null)
    val cartId: StateFlow<String?> = _cartId

    private val _foundFlight = MutableStateFlow<FoundFlightDetails?>(null)
    val foundFlight: StateFlow<FoundFlightDetails?> = _foundFlight

    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady

    private val _sessionStatus = MutableStateFlow("⏳ جاري تجهيز جلسة Hyper...")
    val sessionStatus: StateFlow<String> = _sessionStatus

    init {
        viewModelScope.launch {
            try {
                // 👇 فحص المفتاح
                if (BuildConfig.HYPER_API_KEY.isBlank()) {
                    addLog("❌ مفتاح Hyper فارغ! تأكد من إضافته في GitHub Secrets وملف build.gradle")
                    _sessionStatus.value = "❌ مفتاح API فارغ"
                    _sessionReady.value = false
                    return@launch
                }

                addLog("🔄 تهيئة جلسة Hyper...")
                hyperSession.ensureFreshToken()
                
                if (hyperSession.hasFreshToken()) {
                    markSessionReady()
                    addLog("✅ جلسة Hyper جاهزة — reese84 يُجدد تلقائياً")
                } else {
                    _sessionStatus.value = "⚠️ فشل التجهيز، اضغط للبدء للمحاولة"
                    addLog("⚠️ لم يتم الحصول على التوكن")
                }
            } catch (e: Exception) {
                _sessionStatus.value = "⚠️ تعذر التجهيز: ${e.message}"
                addLog("❌ فشل Hyper: ${e.message}")
            }
        }
    }

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

    fun hasToken(): Boolean = hyperSession.hasFreshToken()

    fun sessionExpiryMs(): Long = hyperSession.expiryAt()

    fun clearSession() {
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
        hyperSession.forceClear()
        markSessionPreparing()
    }

    fun refreshSession() {
        viewModelScope.launch {
            try {
                markSessionPreparing()
                hyperSession.ensureFreshToken(forceRenew = true)
                markSessionReady()
                addLog("✅ تم تجديد reese84")
            } catch (e: Exception) {
                addLog("❌ فشل التجديد: ${e.message}")
                markSessionPreparing()
            }
        }
    }

    private fun getCookies() = getApplication<Application>()
        .getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        .getString("dynamic_cookies", null)

    private fun saveCookies(response: Response<*>) {
        val setCookies = response.headers().values("Set-Cookie")
        if (setCookies.isEmpty()) return
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val current = prefs.getString("dynamic_cookies", "") ?: ""
        val map = mutableMapOf<String, String>()
        if (current.isNotEmpty()) {
            current.split("; ").forEach { pair ->
                val p = pair.split("=", limit = 2)
                if (p.size == 2) map[p[0].trim()] = p[1].trim()
            }
        }
        setCookies.forEach { h ->
            val f = h.split(";").first().trim()
            val p = f.split("=", limit = 2)
            if (p.size == 2) map[p[0].trim()] = p[1].trim()
        }
        prefs.edit().putString("dynamic_cookies",
            map.entries.joinToString("; ") { "${it.key}=${it.value}" }).apply()
    }

    private fun buildHeaders(apiVersion: String, referer: String = "https://www.saudia.com/"): Map<String, String> {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)
        val h = mutableMapOf(
            "x-api-version" to apiVersion,
            "platform" to "Web",
            "version" to "27.2",
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Origin" to "https://www.saudia.com",
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Accept-Language" to "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "browserid" to "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9",
            "channel" to "MOBILE_WEB",
            "request-id" to "|$traceId.$spanId",
            "traceparent" to "00-$traceId-$spanId-01",
            "x-datadog-origin" to "rum",
            "x-datadog-parent-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString(),
            "x-datadog-sampling-priority" to "1",
            "x-datadog-trace-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString()
        )

        val dynamic = getCookies()
        val reese = hyperSession.getCookieHeader()
        val cookieParts = mutableListOf<String>()
        if (!dynamic.isNullOrBlank()) cookieParts.add(dynamic)
        if (!reese.isNullOrBlank()) cookieParts.add(reese)
        if (cookieParts.isNotEmpty()) h["Cookie"] = cookieParts.joinToString("; ")

        return h
    }

    fun buildHeadersPublic(v: String, ref: String = "https://www.saudia.com/") = buildHeaders(v, ref)

    fun runFullBooking() {
        if (_isRunning.value) return
        if (!passengerData.isValid()) { addLog("❌ بيانات المسافر ناقصة"); return }
        if (!flightData.isValid()) { addLog("❌ بيانات الرحلة ناقصة"); return }

        clearResults()
        _isRunning.value = true

        viewModelScope.launch {
            try {
                try {
                    addLog("🔄 ضمان جلسة Hyper قبل البحث...")
                    hyperSession.ensureFreshToken()
                    markSessionReady()
                } catch (e: Exception) {
                    addLog("❌ فشل تجهيز الجلسة: ${e.message}")
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
                _cartId.value = cid; setStep("cart", StepStatus.SUCCESS, cid); delay(1200)

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
                setStep("payment", StepStatus.SUCCESS, "✅ SADAD جاهز — ادفع خلال 24 ساعة")
            } catch (e: Exception) {
                addLog("❌ ${e.message}")
            } finally { _isRunning.value = false }
        }
    }

    private suspend fun searchUntilFound(max: Int = 1000): FoundFlightDetails? {
        var attempt = 0
        while (attempt < max) {
            attempt++
            try {
                hyperSession.ensureFreshToken()
            } catch (e: Exception) {
                addLog("⚠️ فشل تجديد reese84: ${e.message} — انتظار 30s")
                delay(30_000)
                continue
            }

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
                saveCookies(resp)

                if (resp.code() == 401 || resp.code() == 403) {
                    addLog("⚠️ استجابة ${resp.code()} — تجديد الجلسة")
                    hyperSession.ensureFreshToken(forceRenew = true)
                    delay(5000)
                    continue
                }

                if (resp.isSuccessful) {
                    val g = resp.body()?.data?.airBoundGroups?.firstOrNull { gg ->
                        gg.boundDetails?.segments?.any {
                            it.flightId?.contains(flightData.flightNumber, true) == true
                        } == true
                    }
                    val cheapest = g?.airBounds?.filter { it.status?.value != "soldOut" }
                        ?.minByOrNull { it.airOffer?.totalPrice?.value ?: Double.MAX_VALUE }
                    if (cheapest != null) return FoundFlightDetails(
                        flightData.flightNumber, flightData.date,
                        g.boundDetails?.origin ?: flightData.from,
                        g.boundDetails?.destination ?: flightData.to,
                        (cheapest.airOffer?.totalPrice?.value ?: 0.0) / 100.0,
                        cheapest.fareFamilyCode ?: "N/A",
                        cheapest.airBoundId ?: ""
                    )
                } else {
                    addLog("❌ بحث ${attempt}: HTTP ${resp.code()}")
                }
            } catch (e: Exception) { addLog("❌ ${e.message}") }
            delay(15_000)
        }
        return null
    }

    private suspend fun createCart(airBoundId: String): String? = try {
        val r = RetrofitClient.api.createCart(
            headers = buildHeaders("2"),
            request = CreateCartRequest(listOf(airBoundId))
        )
        saveCookies(r)
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
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = payload.toRequestBody("application/json".toMediaType())
        )
        saveCookies(r); r.code() == 200
    } catch (e: Exception) { false }

    private suspend fun addExtension(cid: String): Boolean = try {
        val body = """[{"content":"eyJzdGF0dXMiOiJWRVJJRklFRCIsImhlYWx0aENoZWNrU3RhdHVzIjoiTk9fUkVTUE9OU0UifQ==","travelerId":"SKH-1-EXT","extensionType":"TextExtension","name":"YQENSTATUS"}]"""
        val r = RetrofitClient.api.addCartExtension(
            cid, guestOfficeId = "JEDSV08AA",
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = body.toRequestBody("application/json".toMediaType())
        )
        saveCookies(r); r.isSuccessful
    } catch (e: Exception) { false }

    private suspend fun saveContacts(cid: String): Boolean = try {
        val p = passengerData
        val body = """[{"address":"${p.email}","category":"personal","contactType":"Email","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"address":"${p.email}","category":"personal","contactType":"Email","purpose":"standard"},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"${p.phone}","deviceType":"mobile","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"${p.phone}","deviceType":"mobile","purpose":"standard"}]"""
        val r = RetrofitClient.api.saveContacts(
            cid,
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = body.toRequestBody("application/json".toMediaType())
        )
        saveCookies(r); r.isSuccessful
    } catch (e: Exception) { false }

    private suspend fun createOrder(cid: String): Pair<String, String>? = try {
        val r = RetrofitClient.api.createOrderRaw(
            cid,
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = "{}".toRequestBody("application/json".toMediaType())
        )
        saveCookies(r)
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
            pnr, p.lastName,
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = body.toRequestBody("application/json".toMediaType())
        )
        saveCookies(r); r.isSuccessful
    } catch (e: Exception) { false }
}
