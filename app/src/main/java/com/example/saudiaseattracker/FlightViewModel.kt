package com.example.saudiaseattracker

import android.app.Application
import android.content.Context
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
import okhttp3.OkHttpClient
import okhttp3.Request
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

    // ═══════════════════════════════════════════════════════════════
    // 🔑 التوكن الافتراضي
    // ═══════════════════════════════════════════════════════════════
    private var activeJwt = "Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsImtpZCI6InhIOURKSDNOMDg2QWdzOEpMNklRemVRaDFwOCJ9.eyJhdWQiOiIyZjIwNWM4Yi04MmE5LTQzMGYtYjFiYS0wOGFjMzFiNmVkNGMiLCJpc3MiOiJodHRwczovL2xvZ2luLm1pY3Jvc29mdG9ubGluZS5jb20vZjhiYTJjMjMtYTQwMy00MjNiLWJhMzktZjc4ODNjMjExNjM3L3YyLjAiLCJpYXQiOjE3OTQ0NDYwOTYsIm5iZiI6MTc5NDQ0NjA5NiwiZXhwIjoxNzk0NDQ5OTk2LCJhaW8iOiJrMk5nWU9pNFZLWDNvUHRJVkZYMkFwN0E2UmNWVXVzaTdGa1RGN1lsbkE3UytLM0d6UU1BIiwiYXpwIjoiNGE2ZjBkMzEtOGM0NC00NWQ2LThiNWYtZGQxZjRhMzliYjYwIiwiYXpwYWNyIjoiMSIsIm9pZCI6ImMxZDAzYzU2LWNkYmMtNDZlMC05MTAwLTY1NjZiMmE5NjJhYiIsInJoIjoiMS5BUWtBSXl5Ni1BT2tPMEs2T2ZlSVBDRVdONHRjSUMtcGdnOURzYm9JckRHMjdVd0FBQUFKQUEuIiwicm9sZXMiOlsiQm9va2luZ0FsbEFjY2VzcyJdLCJzdWIiOiJjMWQwM2M1Ni1jZGJjLTQ2ZTAtOTEwMC02NTY2YjJhOTYyYWIiLCJ0aWQiOiJmOGJhMmMyMy1hNDAzLTQyM2ItYmEzOS1mNzg4M2MyMTE2MzciLCJ1dGkiOiJKYlJORlUwZWUweW8wZDFqZE1oVkFBIiwidmVyIjoiMi4wIiwieG1zX2Z0ZCI6IjRaaDRqT1RUejlUWi1fUXI3TW1xS3VwaXg4WUxsZEh3SzcweTJxWkpCellCYzNkbFpHVnVZeTFrYzIxeiJ9.j3-2-UfHayi8nqL1lbgX08IXNKVGQXxh1b6CWGfOVib2zqnQNNnJvy0OlmZTUS0Gb0EHlVjs38B0vGIDBAvxz2MxixPglNXL3L5zydPazMCndsuuac66cVrTbp_r0b0Rjq2K-5pOuct9FA0vIWd7Fj01lyhRnL9cEEhKRqNBdyfaQmpfAoBDUS9vW4FE_W-WqAb3kc4QgRqw8qR-9HM_V4CBe6ZBr8o-L9zOBemUDtYA_XmEeAxeTrkKcgb-f2U0WqEWslWKyqY5--35k0DoJISw0lFLb0xKZ0-NVEN5thlWM11pRsvBbOPnizhRCfURUve7JLznKMq2JyppviuuuA"

    // ═══════════════════════════════════════════════════════════════
    // 🧩 طلب الـ Replay (الذي استخرجته)
    // ═══════════════════════════════════════════════════════════════
    private val replayPayload = JSONObject("""
        {
          "url": "https://dapi.saudia.com/session/auth/web/token/initialization",
          "body": "key=UUh2b1ptWElibEoraXR4N21KVUlpdGNuQWlKa2g4eUdXOFBzdUlCQU5aemlRRmhTK09paG1mZVZLNnJPdUxRN3FrUjdjYWxVL0tKRDBFSkxFUm5wK21rNkViMDhlcitYQThYTlNCaEZBQTRaMERTOWV2MXNOMFNWNzhKSDJzYUZ4SWQxOUNEZFJkUk1zVGhFaEhSTjBEcXpVUHdtMGNKNmoyUEF0eEs4NE0wcXZIVHNVOHVkTjNUWktBaW5hcjJkL0REdEpWckRhS2d1c3UvaXVFM1JyMDEybHZzZnl6Q2hrdEdBLzViMEdNUlh1VzMva1ZTWHVwYnFmU3UrUzRySTJjKzdybGU3ODcrT2JYQjVyaWREcGZQK3NQWFd2Zjd6SFVkeStXVTZ4dGNIN1NEZ01lMy84em5Dd3c3dTJ5WS9NeTROamd5eTVNdWlsVlJHM1VtOFRsVW1GcHJncUx3TVljSjZCMHowZUtnZVZWUlRtalBKZnd5dCtDNGMyVTFpUjVPa2RWNzBMZEg5UTVYaWhBSnVORHBFakZ2Wmc5bGs4a2JiNmtMd2w0c2F0b3QzR1gxZHBUYmlGdU8zaTZ6UlBRZmV0Q0FuR0ZVRUFVTk1MNTkzMUdBK1hzSGsyUzNsbUdoaElrczNac0hQZkVUdStyNG5qUFBqUGpxZTROZXM2QWwvaTZFdDhYbTNMd3dWVmVYT1F0R3FUOU1Wc04rSHU4eVBlV3J4cmdOYW9ubWlmMkxkeDV1Rm9oSUhvSjI4U0RBd0lnMGdiZVM3OXp3N3FMRHQ3blR5NWNEL3Y0TURPd3cwOERCY1lpdEs0bGJKNUFQSjhnWC8ycGN3REk1K1NLSHp4VlRyT3lrbDQ5V2Fhb2pnaHh4TTdJYlJxMXhGMG95UDJxRDVIU2c9&body=NjMyMmZkNGJjNDJjZmZiMzIwMzlhZTYzNTQ4ZGFkZjFkNmZhMmJkN2M4OTdkOGYwZGI2Y2VhZDU2YzYxMTdhODUxNmM0OWJjYmY5NjQ4OTRlN2E0OTRiMjM0MTQ1ZTU4NGY2NGQ1YTg5MmIzYjkzYjk0YmU3OWI3ZWMwNzVhYWYwYWFjZjY0YWY1NzMyOTI0MDU0NDA3MzMxNzI2ZTI1YzczNjY5OWYwYjU2OWZmOTU2MDM1NmZmMjVmOTg3NjhkNWQ0MWM5NTg5YzFkODE2YmRlM2RhZjA2ZjM1ZWUyZTU3ZWVjMGE4YjQ1ZThmY2NkZjI3Nzc1ZjAxN2M3NDVkNTBmMDFiOTFkNmVmMTljZWEzYThjZjg0ODEzNTkzOTJiMzZiODFjMzIyZmYyNWRmYzk5OWM4MzA3NzViNzRmOWJkNTU4MjczMzZiM2E2NGQ3NDhkNGI4MDdiNTYyOTMxYWY3MmIzYWFlMDFkZGY4NzZjOTljOTI2YjZmMjA5MWM4ZDE1ZGI4ODcyNjg0NDllODQwZmQ5ZjUzN2JmOWNmZDZhNDMzMTUwY2I5OGFmZDQ3NmM2N2RmZWRhMDE0YTU5NGU1YmJlMGM3NjIwMzUzMDkzNTcwOGUxZTEzMzg0NGFlYzlkODU4M2JiZTc5MGZmYjU1ZmE3N2YxZTAwY2FkNGY=",
          "xdToken": "3:hZcTrr6YK93lEdp5NFgzPQ==:AiDNK0Qg3GNenZyYa3hXh+LDSmw4BrryVr+IjlGDRaV6VDiRgHd1tr7u5GiRg3lrgwm5S7JTgUtPVl4vcR2e+eADx6/KpUTVfdQB1+ZR3Oy2bFyhjud7PG5NnaMrrzDHibYnTJ4XOkB+7vamdj6ec0thu0ErLq99h8tBuhx+FKk=:bhsavVVDGyj8BwFeChe3IfW1J5GfxKzCEFJsOk+69Ek=:b195C0bceVKfSjfFuTmR2/FOWZNDT7Yuxvrva5RzIybGsuEug/CmrT+WvKFisHWmR3MbR3FJeBnQttFv2PatTQd6VUOwKe6kHvNfyma+p5VKUHt/A3mC65rPW6MHhxRRhaECf0YBwQz0BjmAQaNSl89Nq0LzU4mXKxlXoJX40m7m1uXDi24adMqprXxNxlfp6Q+F9oSqsNtgM+xeqQ5qvkBjNvBhJ7Cggq73F6IpGtpM19gHauHhoSlqTdnQT0o8WS4Uo1CIw+DhgSl+St/+ZQPiiwD/YVitnU07skHIX5OsHZV9BaIJtB1NsjBQo9U8vW7j2Kza6GxVXB6vOiw/2ip8rS5lfV+Asded4dow/Kuuemnq2Srs/au2iQ1cOncWggdYMhi4817iOZYKCC8uyxoRNAnJ734El8QzQXSNUioYebBOHpz2pWiMYnGBhO96sn33zQq5kK4SpBfvyA==:",
          "headers": {
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
            "x-d-token": "3:hZcTrr6YK93lEdp5NFgzPQ==:AiDNK0Qg3GNenZyYa3hXh+LDSmw4BrryVr+IjlGDRaV6VDiRgHd1tr7u5GiRg3lrgwm5S7JTgUtPVl4vcR2e+eADx6/KpUTVfdQB1+ZR3Oy2bFyhjud7PG5NnaMrrzDHibYnTJ4XOkB+7vamdj6ec0thu0ErLq99h8tBuhx+FKk=:bhsavVVDGyj8BwFeChe3IfW1J5GfxKzCEFJsOk+69Ek=:b195C0bceVKfSjfFuTmR2/FOWZNDT7Yuxvrva5RzIybGsuEug/CmrT+WvKFisHWmR3MbR3FJeBnQttFv2PatTQd6VUOwKe6kHvNfyma+p5VKUHt/A3mC65rPW6MHhxRRhaECf0YBwQz0BjmAQaNSl89Nq0LzU4mXKxlXoJX40m7m1uXDi24adMqprXxNxlfp6Q+F9oSqsNtgM+xeqQ5qvkBjNvBhJ7Cggq73F6IpGtpM19gHauHhoSlqTdnQT0o8WS4Uo1CIw+DhgSl+St/+ZQPiiwD/YVitnU07skHIX5OsHZV9BaIJtB1NsjBQo9U8vW7j2Kza6GxVXB6vOiw/2ip8rS5lfV+Asded4dow/Kuuemnq2Srs/au2iQ1cOncWggdYMhi4817iOZYKCC8uyxoRNAnJ734El8QzQXSNUioYebBOHpz2pWiMYnGBhO96sn33zQq5kK4SpBfvyA==:",
            "platform": "Web",
            "version": "27.2"
          }
        }
    """.trimIndent())

    private val hyperSession: HyperSessionManager by lazy {
        HyperSessionManager(
            context = getApplication(),
            rawApiKey = BuildConfig.HYPER_API_KEY,
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

    private val _foundFlight = MutableStateFlow<FoundFlightDetails?>(null)
    val foundFlight: StateFlow<FoundFlightDetails?> = _foundFlight

    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady

    private val _sessionStatus = MutableStateFlow("⏳ جاري تجهيز جلسة Hyper...")
    val sessionStatus: StateFlow<String> = _sessionStatus

    init {
        viewModelScope.launch {
            try {
                addLog("🔄 تهيئة جلسة Hyper...")
                hyperSession.ensureFreshToken()
                markSessionReady()
                addLog("✅ جلسة Hyper جاهزة")
                
                // تجديد التوكن فوراً عند بدء التشغيل
                refreshJwtFromReplay()
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

    // ═══════════════════════════════════════════════════════════════
    // 🚀 دالة التجديد التلقائي باستخدام الـ Replay
    // ═══════════════════════════════════════════════════════════════
    private suspend fun refreshJwtFromReplay(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val url = replayPayload.getString("url")
                val bodyString = replayPayload.getString("body")
                val xdToken = replayPayload.getString("xdToken")

                val request = Request.Builder()
                    .url(url)
                    .post(bodyString.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .header("x-d-token", xdToken)
                    .header("platform", "Web")
                    .header("version", "27.2")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    // 👇 هنا نستخدم كوكي reese84 من Hyper الذي يتخطى حماية Imperva
                    .header("Cookie", hyperSession.getCookieHeader() ?: "")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val json = JSONObject(body)
                        val newToken = json.optString("access_token", json.optString("token", ""))
                        if (newToken.isNotEmpty()) {
                            activeJwt = "Bearer $newToken"
                            withContext(Dispatchers.Main) {
                                addLog("✅ تم تجديد التوكن تلقائياً من الـ Replay")
                            }
                            true
                        } else {
                            withContext(Dispatchers.Main) { addLog("❌ لم يتم العثور على التوكن في الرد") }
                            false
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            addLog("❌ فشل التجديد: ${response.code} - $body")
                        }
                        false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addLog("❌ خطأ أثناء التجديد: ${e.message}")
                }
                false
            }
        }
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
            "channel" to "WEB",
            "request-id" to "|$traceId.$spanId",
            "traceparent" to "00-$traceId-$spanId-01",
            "x-datadog-origin" to "rum",
            "x-datadog-parent-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString(),
            "x-datadog-sampling-priority" to "1",
            "x-datadog-trace-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString()
        )

        // 👇 استخدام التوكن النشط (المجدد تلقائياً)
        h["Authorization"] = activeJwt

        val reese = hyperSession.getCookieHeader()
        if (!reese.isNullOrBlank()) h["Cookie"] = reese

        return h
    }

    fun runFullBooking() {
        if (_isRunning.value) return
        if (!passengerData.isValid()) { addLog("❌ بيانات المسافر ناقصة"); return }
        if (!flightData.isValid()) { addLog("❌ بيانات الرحلة ناقصة"); return }

        clearResults()
        _isRunning.value = true

        viewModelScope.launch {
            try {
                hyperSession.ensureFreshToken()
                markSessionReady()

                // تجديد التوكن قبل البحث
                refreshJwtFromReplay()

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

                // إذا انتهت صلاحية التوكن، جدد وحاول مرة أخرى
                if (resp.code() == 401 || resp.code() == 403) {
                    addLog("⚠️ التوكن منتهي - جاري التجديد التلقائي...")
                    val refreshed = refreshJwtFromReplay()
                    if (refreshed) {
                        addLog("✅ تم التجديد، إعادة المحاولة...")
                        delay(2000)
                        continue 
                    } else {
                        addLog("❌ فشل التجديد، أوقف البحث.")
                        return null
                    }
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
        r.code() == 200
    } catch (e: Exception) { false }

    private suspend fun addExtension(cid: String): Boolean = try {
        val body = """[{"content":"eyJzdGF0dXMiOiJWRVJJRklFRCIsImhlYWx0aENoZWNrU3RhdHVzIjoiTk9fUkVTUE9OU0UifQ==","travelerId":"SKH-1-EXT","extensionType":"TextExtension","name":"YQENSTATUS"}]"""
        val r = RetrofitClient.api.addCartExtension(
            cid, guestOfficeId = "JEDSV08AA",
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = body.toRequestBody("application/json".toMediaType())
        )
        r.isSuccessful
    } catch (e: Exception) { false }

    private suspend fun saveContacts(cid: String): Boolean = try {
        val p = passengerData
        val body = """[{"address":"${p.email}","category":"personal","contactType":"Email","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"address":"${p.email}","category":"personal","contactType":"Email","purpose":"standard"},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"${p.phone}","deviceType":"mobile","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"${p.phone}","deviceType":"mobile","purpose":"standard"}]"""
        val r = RetrofitClient.api.saveContacts(
            cid,
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = body.toRequestBody("application/json".toMediaType())
        )
        r.isSuccessful
    } catch (e: Exception) { false }

    private suspend fun createOrder(cid: String): Pair<String, String>? = try {
        val r = RetrofitClient.api.createOrderRaw(
            cid,
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = "{}".toRequestBody("application/json".toMediaType())
        )
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
        r.isSuccessful
    } catch (e: Exception) { false }
}
