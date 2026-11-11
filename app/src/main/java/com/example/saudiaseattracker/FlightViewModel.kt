package com.example.saudiaseattracker

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    // ── Inputs ──
    var passengerData by mutableStateOf(PassengerData())
    var flightData by mutableStateOf(FlightData())
    var monitoringMode by mutableStateOf(false)  // ⭐ وضع المراقبة

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

    // ══════════════════════════════════════════════════════
    // Prefs / Headers
    // ══════════════════════════════════════════════════════
    fun saveTokens(auth: String, xDToken: String, sessionId: String, userAgent: String) {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("auth_token", auth)
            .putString("xd_token", xDToken)
            .putString("session_id", sessionId)
            .putString("user_agent", userAgent)
            .apply()
        addLog("✅ تم التقاط التوكن")
    }

    private fun getAuth() = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("auth_token", null)
    private fun getXDT() = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("xd_token", null)
    private fun getSID() = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("session_id", null)
    private fun getCookies() = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("dynamic_cookies", null)

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
        val updated = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        prefs.edit().putString("dynamic_cookies", updated).apply()
    }

    private fun buildHeaders(apiVersion: String, referer: String = "https://www.saudia.com/"): Map<String, String> {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)
        val h = mutableMapOf(
            "Authorization" to (getAuth() ?: ""),
            "x-d-token" to (getXDT() ?: ""),
            "saa-session-id" to (getSID() ?: ""),
            "x-api-version" to apiVersion,
            "platform" to "Web",
            "version" to "27.2",
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Origin" to "https://www.saudia.com",
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
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
        getCookies()?.let { if (it.isNotEmpty()) h["Cookie"] = it }
        return h
    }

    fun buildHeadersPublic(v: String, ref: String = "https://www.saudia.com/"): Map<String, String> =
        buildHeaders(v, ref)

    fun hasToken(): Boolean = getAuth() != null

    // ══════════════════════════════════════════════════════
    // Full Booking Flow (with Monitoring Mode)
    // ══════════════════════════════════════════════════════
    fun runFullBooking() {
        if (_isRunning.value) return
        if (!hasToken()) { addLog("❌ لا يوجد توكن"); return }
        if (!passengerData.isValid()) { addLog("❌ بيانات المسافر غير مكتملة"); return }
        if (!flightData.isValid()) { addLog("❌ بيانات الرحلة غير مكتملة"); return }

        clearResults()
        _isRunning.value = true
        addLog(if (monitoringMode) "👁️ بدء وضع المراقبة..." else "🚀 بدء الحجز الكامل...")

        viewModelScope.launch {
            try {
                // ─── SEARCH ───
                setStep("search", StepStatus.RUNNING)
                val flight = searchUntilFound()
                if (flight == null) {
                    setStep("search", StepStatus.FAILED, "لم تُوجد رحلة")
                    _isRunning.value = false
                    return@launch
                }
                setStep("search", StepStatus.SUCCESS, "السعر: ${flight.price} SAR")
                _foundFlight.value = flight
                delay(1200)

                // ─── CART ───
                setStep("cart", StepStatus.RUNNING)
                val cartId = createCart(flight.airBoundId)
                if (cartId == null) {
                    setStep("cart", StepStatus.FAILED)
                    _isRunning.value = false
                    return@launch
                }
                _cartId.value = cartId
                setStep("cart", StepStatus.SUCCESS, "cartId: $cartId")
                delay(1200)

                // ─── PASSENGER ───
                setStep("passenger", StepStatus.RUNNING)
                if (!confirmPassenger(cartId)) {
                    setStep("passenger", StepStatus.FAILED)
                    _isRunning.value = false
                    return@launch
                }
                setStep("passenger", StepStatus.SUCCESS)
                delay(1200)

                // ─── EXTENSION ───
                setStep("extension", StepStatus.RUNNING)
                if (!addExtension(cartId)) {
                    setStep("extension", StepStatus.FAILED)
                    _isRunning.value = false
                    return@launch
                }
                setStep("extension", StepStatus.SUCCESS)
                delay(1200)

                // ─── CONTACTS ───
                setStep("contacts", StepStatus.RUNNING)
                if (!saveContacts(cartId)) {
                    setStep("contacts", StepStatus.FAILED)
                    _isRunning.value = false
                    return@launch
                }
                setStep("contacts", StepStatus.SUCCESS)
                delay(1200)

                // ─── ORDER ───
                setStep("order", StepStatus.RUNNING)
                val orderRes = createOrder(cartId)
                if (orderRes == null) {
                    setStep("order", StepStatus.FAILED)
                    _isRunning.value = false
                    return@launch
                }
                val (pnr, sadad) = orderRes
                _finalPnr.value = pnr
                _finalSadad.value = sadad
                setStep("order", StepStatus.SUCCESS, "PNR: $pnr | SADAD: $sadad")
                delay(1200)

                // ⭐⭐⭐ MONITORING CHECK ⭐⭐⭐
                if (monitoringMode) {
                    setStep("payment", StepStatus.SKIPPED, "👁️ وضع المراقبة: لم يتم إرسال الدفع")
                    addLog("═════════════════════════════════════")
                    addLog("👁️ وضع المراقبة اكتمل")
                    addLog("PNR: $pnr")
                    addLog("SADAD: $sadad")
                    addLog("⚠️ لم يُرسل الدفع — احجز من الموقع أو أوقف المراقبة وأعد")
                    addLog("═════════════════════════════════════")
                    _isRunning.value = false
                    return@launch
                }

                // ─── PAYMENT ───
                setStep("payment", StepStatus.RUNNING)
                if (!submitPayment(pnr)) {
                    setStep("payment", StepStatus.FAILED, "قد تحتاج دفع حجز سابق أولاً")
                    _isRunning.value = false
                    return@launch
                }
                setStep("payment", StepStatus.SUCCESS, "✅ اذهب للبنك وادفع")

                addLog("═════════════════════════════════════")
                addLog("🎉 الحجز مكتمل!")
                addLog("PNR: $pnr | SADAD: $sadad")
                addLog("═════════════════════════════════════")
            } catch (e: Exception) {
                addLog("❌ استثناء: ${e.message}")
            } finally {
                _isRunning.value = false
            }
        }
    }

    private suspend fun searchUntilFound(maxAttempts: Int = 30): FoundFlightDetails? {
        val dateStr = "${flightData.date}T00:00:00.000"
        for (attempt in 1..maxAttempts) {
            setStep("search", StepStatus.RUNNING, "محاولة $attempt/$maxAttempts")
            try {
                val req = SaudiaSearchRequest(
                    airBoundsSearchInputs = AirBoundsSearchInputs(
                        itineraries = listOf(Itinerary(flightData.from, flightData.to, dateStr)),
                        travelers = listOf(Traveler()),
                        searchPreferences = SearchPreferences()
                    ),
                    offerCodeAvailabilityInput = OfferCodeAvailabilityInput()
                )
                val resp = RetrofitClient.api.searchAirBounds(headers = buildHeaders("4"), request = req)
                saveCookies(resp)
                if (resp.isSuccessful) {
                    val groups = resp.body()?.data?.airBoundGroups
                    val matching = groups?.filter { g ->
                        g.boundDetails?.segments?.any {
                            it.flightId?.contains(flightData.flightNumber, ignoreCase = true) == true
                        } == true
                    }
                    val group = matching?.firstOrNull()
                    if (group != null) {
                        val fares = group.airBounds?.filter { it.status?.value != "soldOut" } ?: emptyList()
                        val cheapest = fares.minByOrNull { it.airOffer?.totalPrice?.value ?: Double.MAX_VALUE }
                        if (cheapest != null) {
                            addLog("🎉 وُجدت رحلة")
                            return FoundFlightDetails(
                                flightNumber = flightData.flightNumber,
                                date = flightData.date,
                                origin = group.boundDetails?.origin ?: flightData.from,
                                destination = group.boundDetails?.destination ?: flightData.to,
                                price = (cheapest.airOffer?.totalPrice?.value ?: 0.0) / 100.0,
                                fareFamily = cheapest.fareFamilyCode ?: "N/A",
                                airBoundId = cheapest.airBoundId ?: ""
                            )
                        }
                    }
                    addLog("⚠️ محاولة $attempt — لا مقاعد")
                } else if (resp.code() == 401 || resp.code() == 403) {
                    addLog("❌ توكن منتهي")
                    return null
                }
            } catch (e: Exception) {
                addLog("❌ بحث: ${e.message}")
            }
            delay(15000)
        }
        return null
    }

    private suspend fun createCart(airBoundId: String): String? = try {
        val resp = RetrofitClient.api.createCart(
            headers = buildHeaders("2"),
            request = CreateCartRequest(listOf(airBoundId))
        )
        saveCookies(resp)
        if (!resp.isSuccessful) null else {
            val body = resp.body()?.string() ?: return null
            val json = JsonParser.parseString(body).asJsonObject
            json.getAsJsonObject("data")?.get("id")?.asString ?: json.get("id")?.asString
        }
    } catch (e: Exception) { addLog("❌ cart: ${e.message}"); null }

    private suspend fun confirmPassenger(cartId: String): Boolean = try {
        val p = passengerData
        val payload = """{"passengerTypeCode":"ADT","gender":"${p.gender}","dateOfBirth":"${p.dateOfBirth}","names":[{"firstName":"${p.firstName}","lastName":"${p.lastName}","title":"${p.title}","nameType":"universal"}],"regulatoryDetails":[{"regulatoryApisType":"ApisDetailRegulatoryDocument","regulatoryDocument":{"number":"${p.nationalId}","name":{"firstName":"${p.firstName.uppercase()}","lastName":"${p.lastName.uppercase()}","nameType":"universal"},"nationalityCode":"${p.nationality}","gender":"${p.gender}","birthDate":"${p.dateOfBirth}","documentType":"identityCard"}}]}"""
        val resp = RetrofitClient.api.updateTravelerRaw(
            cartId = cartId,
            travelerId = "SKH-1-EXT",
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = payload.toRequestBody("application/json".toMediaType())
        )
        saveCookies(resp)
        addLog("PATCH traveler: HTTP ${resp.code()}")
        resp.code() == 200
    } catch (e: Exception) { addLog("❌ passenger: ${e.message}"); false }

    private suspend fun addExtension(cartId: String): Boolean = try {
        val body = """[{"content":"eyJzdGF0dXMiOiJWRVJJRklFRCIsImhlYWx0aENoZWNrU3RhdHVzIjoiTk9fUkVTUE9OU0UifQ==","travelerId":"SKH-1-EXT","extensionType":"TextExtension","name":"YQENSTATUS"}]"""
        val resp = RetrofitClient.api.addCartExtension(
            cartId = cartId,
            guestOfficeId = "JEDSV08AA",
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = body.toRequestBody("application/json".toMediaType())
        )
        saveCookies(resp)
        addLog("POST extensions: HTTP ${resp.code()}")
        resp.isSuccessful
    } catch (e: Exception) { addLog("❌ ext: ${e.message}"); false }

    private suspend fun saveContacts(cartId: String): Boolean = try {
        val p = passengerData
        val body = """[{"address":"${p.email}","category":"personal","contactType":"Email","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"address":"${p.email}","category":"personal","contactType":"Email","purpose":"standard"},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"${p.phone}","deviceType":"mobile","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"${p.phone}","deviceType":"mobile","purpose":"standard"}]"""
        val resp = RetrofitClient.api.saveContacts(
            cartId = cartId,
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = body.toRequestBody("application/json".toMediaType())
        )
        saveCookies(resp)
        addLog("POST contacts: HTTP ${resp.code()}")
        resp.isSuccessful
    } catch (e: Exception) { addLog("❌ contacts: ${e.message}"); false }

    private suspend fun createOrder(cartId: String): Pair<String, String>? = try {
        val resp = RetrofitClient.api.createOrderRaw(
            cartId = cartId,
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = "{}".toRequestBody("application/json".toMediaType())
        )
        saveCookies(resp)
        addLog("POST purchase/orders: HTTP ${resp.code()}")
        if (!resp.isSuccessful) null else {
            val body = resp.body()?.string() ?: return null
            val pnr = Regex("\"id\"\\s*:\\s*\"([A-Z0-9]{4,8})\"").find(body)?.groupValues?.get(1) ?: return null
            val sadad = Regex("\"numericId\"\\s*:\\s*\"([0-9]{10,14})\"").find(body)?.groupValues?.get(1) ?: ""
            Pair(pnr, sadad)
        }
    } catch (e: Exception) { addLog("❌ order: ${e.message}"); null }

    private suspend fun submitPayment(pnr: String): Boolean = try {
        val p = passengerData
        val body = """{"orderPaymentRequest":{"paymentRequests":[{"paymentMethod":{"paymentType":"CustomPayment","id":"SADAD"}}]},"emailParameters":{"displayParameters":{"alharamain":true,"baggage":true,"bookingCom":true,"eatmarna":false,"electronicVisa":false,"plusgrade":true,"rental":true,"seat":true,"wifi":true,"onDemandTeasers":[]}},"isCardholderAbsent":false,"isQueuePlacementEnabled":false}"""
        val resp = RetrofitClient.api.submitPayment(
            orderId = pnr,
            lastName = p.lastName,
            headers = buildHeaders("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
            request = body.toRequestBody("application/json".toMediaType())
        )
        saveCookies(resp)
        addLog("POST payment-records: HTTP ${resp.code()}")
        resp.isSuccessful
    } catch (e: Exception) { addLog("❌ payment: ${e.message}"); false }
}
