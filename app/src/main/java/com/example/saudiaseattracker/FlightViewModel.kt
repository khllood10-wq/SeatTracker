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
import retrofit2.Response
import java.util.UUID
import kotlin.random.Random

data class FoundFlightDetails(
    val flightNumber: String,
    val date: String,
    val origin: String,
    val destination: String,
    val price: Double,
    val fareFamily: String,
    val airBoundId: String
)

enum class SearchState { IDLE, SEARCHING, FOUND, ERROR, TOKEN_EXPIRED }

class FlightViewModel(application: Application) : AndroidViewModel(application) {
    var searchOrigin by mutableStateOf("JED")
    var searchDestination by mutableStateOf("AHB")
    var searchDate by mutableStateOf("2026-11-17")

    var passengerFirstName by mutableStateOf("TEST")
    var passengerLastName by mutableStateOf("TEST")
    var passengerDocumentType by mutableStateOf("I")
    var passengerDocumentNumber by mutableStateOf("1071580342")
    var passengerDateOfBirth by mutableStateOf("1991-05-10")
    var passengerNationality by mutableStateOf("SA")
    var passengerEmail by mutableStateOf("khllood10@gmail.com")
    var passengerPhoneCountryCode by mutableStateOf("+966")
    var passengerPhoneNumber by mutableStateOf("572260016")

    fun getPassengerData(): PassengerData = PassengerData(
        firstName = passengerFirstName,
        lastName = passengerLastName,
        documentType = passengerDocumentType,
        documentNumber = passengerDocumentNumber,
        dateOfBirth = passengerDateOfBirth,
        nationality = passengerNationality,
        email = passengerEmail,
        phoneCountryCode = passengerPhoneCountryCode,
        phoneNumber = passengerPhoneNumber
    )

    private val _uiState = MutableStateFlow(SearchState.IDLE)
    val uiState: StateFlow<SearchState> = _uiState

    private val _searchLogs = MutableStateFlow<List<String>>(emptyList())
    val searchLogs: StateFlow<List<String>> = _searchLogs

    private val _foundFlight = MutableStateFlow<FoundFlightDetails?>(null)
    val foundFlight: StateFlow<FoundFlightDetails?> = _foundFlight

    private val _cartId = MutableStateFlow<String?>(null)
    val cartId: StateFlow<String?> = _cartId

    private val _rawCartResponse = MutableStateFlow<String?>(null)
    val rawCartResponse: StateFlow<String?> = _rawCartResponse

    private val _paymentReady = MutableStateFlow(false)
    val paymentReady: StateFlow<Boolean> = _paymentReady

    private val _orderId = MutableStateFlow<String?>(null)
    val orderId: StateFlow<String?> = _orderId

    private val _paymentMethodsRaw = MutableStateFlow<String?>(null)
    val paymentMethodsRaw: StateFlow<String?> = _paymentMethodsRaw

    private val _pnr = MutableStateFlow<String?>(null)
    val pnr: StateFlow<String?> = _pnr

    private val _sadadReady = MutableStateFlow(false)
    val sadadReady: StateFlow<Boolean> = _sadadReady

    private companion object {
        const val HAR_ORDER_KEY =
            "Tfdn2colJ0iu60bGEOhBCPLPMxhp6FCvIh1SGlVo0GzimN8/NqEdptIzypLsDJCebFMwRSKVGhof4g0wOKcsEShmmfavAl9xv1NowevhMhm+jf8XQvaVRVCDx6LH4iD99pYizEXYcofDyX1m1LdS95mHA87po/mi+PyIIvCPFePf3H19N1zmUV8kY6Gt7ws3mB90Bxh55rroKxShXkGqNGLVqABMUSpEWFwFDamD4wHMcV+qZjL4/jpJjg8iqkwXrzWvLywIU8j3wsvYCyDFK4VWQoebkCEISU5jnJ4bNYEaW/jDD5EDFzj/JG3qNuCcwe309Zi4MNXnlRLampQs8DjSN1zwYNveUju5z8Q8ipQCzbLmWL1UPBmBSa199ttN6ZSH9JKnKo4zKUbqmYXpbz09IFFfAu+FHgfWbmvHxxI24R/Mv8kXUr54xgpg/3Q9u21ONOaBDWRQ4SnX9dD5jMRgAxQl50sCwbM4Z/m7l+fFzPeheQ74akm3xPF5w/TLSYQ33RwOoaRZmWnUPRk6fTcfZaYL9PxvXSDUYfk5Z0EjYoMLpR1Bh6Gw72cnHqzKkhB+mrZZZB83bQN9WjV6XZMJSUZb1FiGx3A1eI+VtUZAeKnFAVwpt/xUA8jnH2A2mMMB7UNmHsgRktQMgFtZYjpUqgwSiTGFMmxGk9OU0GQ="

        const val HAR_ORDER_BODY =
            "b653e6c66f6da5f96dd85a6acb8dd44efa8b0c41ac5a4ca71724210b7e2c876b46a6b237266c44b93d608c84b95cc637fe94dbbadcbf170c9a812cee1ac007f09c166f50a6cac0073542b3c48b713ed3483ce81d628c8ef597c56326452278e3ac1b7f41d5f1bd649795fbb54bc7bffd2439fb13e667129ecffae26ece21d3a9f5b28cf3a09d58e4683184730ed0f215a7637610a19d27615c310b77e3297434f6bf892299789a07e43d0574aaeaef067be85cb13c9f1b5b99b2901ab63660e2e487322afa360f67a47792edc38f398d1689e17bfd8841eb84d2797d835847c37d599ea222c0966fdd17fcd6a36de80d8cfd4b26fb588658ef2ade072ca69f10640c997aa5797747f4c5cec7290e185598797145dde220de3ccbcff3ded02b5315ce697e759d8ee081dd2f826d230b7ae0f53442f247b18cf15b014d69e870d573e08da7794018eeb3c09310af23dec6d62628eb2227672d6e73b20efef09c8e49b2a43ec00f3684a16841f59511ca44ce23eca459db899c8521e991c0b7532425808f5a1eafa083f5ef0d34463a61d329f7cbfd925fb3409d0cb8436c3d03c8"

        const val FIRST_TRAVELER_ID = "SKH-1-EXT"
        const val TEST_DOCUMENT_NUMBER = "1071580342"
        const val TEST_DATE_OF_BIRTH = "1991-05-10"
        const val CONTACT_EMAIL = "khllood10@gmail.com"
        const val CONTACT_PHONE = "572260016"
        const val CONTACT_PHONE_EXT = "+966"
        const val TRAVELER_LAST_NAME = "Otudi"
    }

    fun saveTokens(auth: String, xDToken: String, sessionId: String, userAgent: String) {
        val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("auth_token", auth)
            .putString("xd_token", xDToken)
            .putString("session_id", sessionId)
            .putString("user_agent", userAgent)
            .apply()
        addLog("✅ تم التقاط التوكن (${auth.take(20)}...)")
    }

    private fun getSavedAuthToken(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("auth_token", null)
    private fun getSavedXDToken(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("xd_token", null)
    private fun getSavedSessionId(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("session_id", null)

    private fun saveCookiesFromResponse(response: Response<*>) {
        val setCookieHeaders = response.headers().values("Set-Cookie")
        if (setCookieHeaders.isNotEmpty()) {
            val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            val current = prefs.getString("dynamic_cookies", "") ?: ""
            val newCookies = setCookieHeaders.joinToString("; ") { it.split(";").first() }
            val updated = if (current.isEmpty()) newCookies else "$current; $newCookies"
            prefs.edit().putString("dynamic_cookies", updated).apply()
        }
    }

    private fun getSavedDynamicCookies(): String? =
        getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("dynamic_cookies", null)

    private fun buildHeaders(apiVersion: String): Map<String, String> {
        val authToken = getSavedAuthToken() ?: ""
        val xDToken = getSavedXDToken() ?: ""
        val sessionId = getSavedSessionId() ?: ""

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)

        val headers = mutableMapOf(
            "Authorization" to authToken,
            "x-d-token" to xDToken,
            "saa-session-id" to sessionId,
            "x-api-version" to apiVersion,
            "platform" to "Web",
            "version" to "27.0",
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Origin" to "https://www.saudia.com",
            "Referer" to "https://www.saudia.com/",
            "User-Agent" to userAgent,
            "Accept-Language" to "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "channel" to "MOBILE_WEB",
            "browserid" to "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9",
            "request-id" to "|$traceId.$spanId",
            "traceparent" to "00-$traceId-$spanId-01",
            "x-datadog-origin" to "rum",
            "x-datadog-parent-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString(),
            "x-datadog-sampling-priority" to "1",
            "x-datadog-trace-id" to Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString()
        )

        val cookies = getSavedDynamicCookies()
        if (!cookies.isNullOrEmpty()) headers["Cookie"] = cookies
        return headers
    }

    // ═══════════════════════════════════════════════════════
    //   البحث — إعادة 401/403 إلى TOKEN_EXPIRED (السلوك القديم)
    // ═══════════════════════════════════════════════════════

    fun startSearchingFlight(flightNumber: String) {
        if (_uiState.value == SearchState.SEARCHING) return
        if (getSavedAuthToken() == null) {
            addLog("❌ لا يوجد توكن!")
            _uiState.value = SearchState.TOKEN_EXPIRED
            return
        }
        _uiState.value = SearchState.SEARCHING
        _searchLogs.value = emptyList()
        _foundFlight.value = null
        addLog("بدء البحث عن الرحلة: $flightNumber (${searchDate})")

        viewModelScope.launch {
            var attempts = 1
            val dateStr = "${searchDate}T00:00:00.000"
            while (attempts <= 100 && _uiState.value == SearchState.SEARCHING) {
                addLog("المحاولة $attempts...")
                try {
                    val request = SaudiaSearchRequest(
                        airBoundsSearchInputs = AirBoundsSearchInputs(
                            itineraries = listOf(Itinerary(searchOrigin, searchDestination, dateStr)),
                            travelers = listOf(Traveler()),
                            searchPreferences = SearchPreferences()
                        ),
                        offerCodeAvailabilityInput = OfferCodeAvailabilityInput()
                    )
                    val response = RetrofitClient.api.searchAirBounds(
                        headers = buildHeaders("4"),
                        request = request
                    )
                    saveCookiesFromResponse(response)
                    if (response.isSuccessful) {
                        val data = response.body()
                        val matching = data?.data?.airBoundGroups?.filter { group ->
                            group.boundDetails?.segments?.any {
                                it.flightId?.contains(flightNumber, ignoreCase = true) == true
                            } == true
                        }
                        if (matching.isNullOrEmpty()) {
                            addLog("⚠️ لم يتم العثور على الرحلة $flightNumber")
                        } else {
                            val group = matching.first()
                            val fares = group.airBounds?.filter { it.status?.value != "soldOut" } ?: emptyList()
                            if (fares.isNotEmpty()) {
                                val cheapest = fares.minByOrNull { it.airOffer?.totalPrice?.value ?: Double.MAX_VALUE }
                                _foundFlight.value = FoundFlightDetails(
                                    flightNumber = flightNumber,
                                    date = searchDate,
                                    origin = group.boundDetails?.origin ?: searchOrigin,
                                    destination = group.boundDetails?.destination ?: searchDestination,
                                    price = (cheapest?.airOffer?.totalPrice?.value ?: 0.0) / 100.0,
                                    fareFamily = cheapest?.fareFamilyCode ?: "غير معروف",
                                    airBoundId = cheapest?.airBoundId ?: ""
                                )
                                addLog("🎉 وُجد مقعد متاح!")
                                _uiState.value = SearchState.FOUND
                                return@launch
                            } else {
                                addLog("🚫 الرحلة ممتلئة. انتظار 15ث...")
                            }
                        }
                    } else {
                        addLog("❌ فشل HTTP ${response.code()}")
                        // ✅ السلوك القديم: 401 و 403 كلاهما يعني "أعد تسجيل الدخول"
                        if (response.code() == 401 || response.code() == 403) {
                            _uiState.value = SearchState.TOKEN_EXPIRED
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    addLog("❌ خطأ: ${e.message}")
                }
                delay(15000)
                attempts++
            }
        }
    }

    fun stopSearch() {
        _uiState.value = SearchState.IDLE
        addLog("تم الإيقاف يدوياً")
    }

    // ═══════════════════════════════════════════════════════
    //   السلة
    // ═══════════════════════════════════════════════════════

    fun createCartAndProceedToPassenger() {
        val flight = _foundFlight.value ?: return
        addLog("جاري إنشاء السلة...")
        viewModelScope.launch {
            try {
                val createResp = RetrofitClient.api.createCart(
                    headers = buildHeaders("2"),
                    request = CreateCartRequest(listOf(flight.airBoundId))
                )
                saveCookiesFromResponse(createResp)
                if (createResp.isSuccessful) {
                    val body = createResp.body()?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    val id = json.getAsJsonObject("data")?.get("id")?.asString
                        ?: json.get("id")?.asString ?: ""
                    if (id.isNotEmpty()) {
                        _cartId.value = id
                        addLog("✅ cartId = $id")
                        val getResp = RetrofitClient.api.getCartDetails(
                            cartId = id,
                            headers = buildHeaders("3")
                        )
                        saveCookiesFromResponse(getResp)
                        if (getResp.isSuccessful) {
                            _rawCartResponse.value = getResp.body()?.string() ?: ""
                            addLog("✅ تم جلب بيانات المسافرين")
                            submitPassengersAndProceedToPayment(id)
                        } else {
                            addLog("❌ HTTP ${getResp.code()}")
                        }
                    } else addLog("⚠️ لا يوجد cartId")
                } else {
                    addLog("❌ HTTP ${createResp.code()}")
                }
            } catch (e: Exception) {
                addLog("❌ ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //   المسافرون → الدفع
    // ═══════════════════════════════════════════════════════

    private fun submitPassengersAndProceedToPayment(cartId: String) {
        viewModelScope.launch {
            var travelerId = FIRST_TRAVELER_ID
            var extensionId = "EXN-1-EXN"

            try {
                addLog("🔐 [1/4] ELM...")
                val r = RetrofitClient.api.verifyElmProfile(
                    headers = buildHeaders("2"),
                    request = ElmVerificationRequest(
                        documentNumber = TEST_DOCUMENT_NUMBER,
                        dateOfBirth = TEST_DATE_OF_BIRTH
                    )
                )
                saveCookiesFromResponse(r)
                if (!r.isSuccessful) {
                    addLog("❌ [1/4] ${r.code()} — ${r.errorBody()?.string()}")
                    return@launch
                }
                addLog("✅ [1/4] تم")
            } catch (e: Exception) { addLog("❌ [1/4] ${e.message}"); return@launch }

            try {
                addLog("📥 [2/4] الملحقات...")
                val r = RetrofitClient.api.getCartExtensions(cartId, headers = buildHeaders("2"))
                saveCookiesFromResponse(r)
                val body = if (r.isSuccessful) r.body()?.string() else r.errorBody()?.string()
                addLog("📦 [2/4] HTTP ${r.code()} — ${body?.take(400) ?: ""}")
                if (!body.isNullOrBlank()) {
                    try {
                        val json = JsonParser.parseString(body)
                        if (json.isJsonArray && json.asJsonArray.size() > 0) {
                            val ext = json.asJsonArray.get(0).asJsonObject
                            extensionId = ext.get("id")?.asString ?: extensionId
                            travelerId = ext.get("travelerId")?.asString ?: travelerId
                        } else if (json.isJsonObject) {
                            val obj = json.asJsonObject
                            val data = obj.get("data")
                            val arr = when {
                                data?.isJsonArray == true -> data.asJsonArray
                                data?.isJsonObject == true -> data.asJsonObject.getAsJsonArray("extensions")
                                else -> obj.getAsJsonArray("extensions")
                            }
                            if (arr != null && arr.size() > 0) {
                                val ext = arr.get(0).asJsonObject
                                extensionId = ext.get("id")?.asString ?: extensionId
                                travelerId = ext.get("travelerId")?.asString ?: travelerId
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                addLog("⚠️ [2/4] ${e.message}")
            }

            try {
                addLog("🩺 [3/4] الحالة الصحية...")
                val inner = """{"status":"VERIFIED","healthCheckStatus":"NO_RESPONSE"}"""
                val b64 = android.util.Base64.encodeToString(
                    inner.toByteArray(), android.util.Base64.NO_WRAP
                )
                val r = RetrofitClient.api.patchCartExtensions(
                    cartId = cartId,
                    headers = buildHeaders("2"),
                    request = listOf(CartExtensionRequest(content = b64, travelerId = travelerId, id = extensionId))
                )
                saveCookiesFromResponse(r)
                addLog(if (r.isSuccessful) "✅ [3/4] تم" else "⚠️ [3/4] ${r.code()}")
            } catch (e: Exception) {
                addLog("⚠️ [3/4] ${e.message}")
            }

            try {
                addLog("👤 [4/4] المسافر...")
                val r = RetrofitClient.api.patchTravelerWeb(
                    cartId = cartId,
                    travelerId = travelerId,
                    headers = buildHeaders("2"),
                    request = EncryptedTravelerRequest(HAR_ORDER_KEY, HAR_ORDER_BODY)
                )
                saveCookiesFromResponse(r)
                if (!r.isSuccessful) {
                    addLog("❌ [4/4] ${r.code()} — ${r.errorBody()?.string()}")
                    return@launch
                }
                addLog("✅ [4/4] المسافر مقبول")
            } catch (e: Exception) { addLog("❌ [4/4] ${e.message}"); return@launch }

            proceedToPayment(cartId)
        }
    }

    private fun proceedToPayment(cartId: String) {
        viewModelScope.launch {
            try {
                addLog("📞 [5/10] الاتصالات...")
                val contacts = listOf(
                    AddContactRequest(address = CONTACT_EMAIL, contactType = "Email",
                        purpose = "notification", lang = "ar", travelerIds = listOf(FIRST_TRAVELER_ID)),
                    AddContactRequest(address = CONTACT_EMAIL, contactType = "Email", purpose = "standard"),
                    AddContactRequest(contactType = "Phone", countryPhoneExtension = CONTACT_PHONE_EXT,
                        number = CONTACT_PHONE, deviceType = "mobile",
                        purpose = "notification", lang = "ar", travelerIds = listOf(FIRST_TRAVELER_ID)),
                    AddContactRequest(contactType = "Phone", countryPhoneExtension = CONTACT_PHONE_EXT,
                        number = CONTACT_PHONE, deviceType = "mobile", purpose = "standard")
                )
                val r = RetrofitClient.api.addContacts(cartId, headers = buildHeaders("2"), request = contacts)
                saveCookiesFromResponse(r)
                if (!r.isSuccessful) {
                    addLog("❌ [5/10] ${r.code()} — ${r.errorBody()?.string()}")
                    return@launch
                }
                addLog("✅ [5/10] تم")
            } catch (e: Exception) { addLog("❌ [5/10] ${e.message}"); return@launch }

            var newOrderId: String? = null
            try {
                addLog("🛒 [6/10] إنشاء الطلب...")
                val r = RetrofitClient.api.createOrder(
                    cartId = cartId,
                    headers = buildHeaders("4"),
                    request = CreateOrderRequest(HAR_ORDER_KEY, HAR_ORDER_BODY)
                )
                saveCookiesFromResponse(r)
                val raw = if (r.isSuccessful) r.body()?.string() else r.errorBody()?.string()
                addLog("📦 [6/10] HTTP ${r.code()} — ${raw?.take(300) ?: ""}")
                if (!r.isSuccessful) {
                    addLog("❌ [6/10] فشل")
                    return@launch
                }
                try {
                    val j = JsonParser.parseString(raw).asJsonObject
                    newOrderId = j.getAsJsonObject("data")?.get("id")?.asString
                        ?: j.getAsJsonObject("data")?.get("orderId")?.asString
                        ?: j.get("id")?.asString
                        ?: j.get("orderId")?.asString
                } catch (_: Exception) {}
                if (newOrderId.isNullOrBlank()) {
                    addLog("⚠️ [6/10] لم نجد orderId")
                    return@launch
                }
                _orderId.value = newOrderId
                addLog("✅ [6/10] orderId = $newOrderId")
            } catch (e: Exception) { addLog("❌ [6/10] ${e.message}"); return@launch }

            try {
                addLog("📋 [7/10] خدمات الطلب...")
                val r = RetrofitClient.api.getOrderServices(
                    orderId = newOrderId!!, lastName = TRAVELER_LAST_NAME, headers = buildHeaders("3")
                )
                addLog(if (r.isSuccessful) "✅ [7/10]" else "⚠️ [7/10] ${r.code()}")
            } catch (e: Exception) { addLog("⚠️ [7/10] ${e.message}") }

            try {
                addLog("📜 [8/10] GACA...")
                val r = RetrofitClient.api.getGacaMandate(
                    headers = buildHeaders("1"),
                    request = GacaMandateRequest(listOf(GacaBound("SA", "SA")))
                )
                addLog(if (r.isSuccessful) "✅ [8/10]" else "⚠️ [8/10] ${r.code()}")
            } catch (e: Exception) { addLog("⚠️ [8/10] ${e.message}") }

            try {
                addLog("💳 [9/10] طرق الدفع...")
                val r = RetrofitClient.api.getPaymentMethods(
                    lastName = TRAVELER_LAST_NAME,
                    headers = buildHeaders("3"),
                    request = PaymentMethodsRequest(
                        orderId = newOrderId!!,
                        paymentRequests = listOf(
                            PaymentRequest(
                                PaymentMethod(),
                                PaymentAuthorization(PaymentRedirectionContext(listOf(
                                    PaymentLink("postredirection", "https://ncerndobedev6482.etv.nce.amadeus.net:1443/index.html")
                                )))
                            )
                        )
                    )
                )
                val raw = if (r.isSuccessful) r.body()?.string() else r.errorBody()?.string()
                _paymentMethodsRaw.value = raw
                addLog("💳 [9/10] HTTP ${r.code()} — ${raw?.take(300) ?: ""}")
            } catch (e: Exception) { addLog("⚠️ [9/10] ${e.message}") }

            try {
                addLog("🎫 [10/10] الأميال...")
                val r = RetrofitClient.api.getMilesPayment(
                    lastName = TRAVELER_LAST_NAME, orderId = newOrderId!!, headers = buildHeaders("2")
                )
                addLog(if (r.isSuccessful) "✅ [10/10]" else "⚠️ [10/10] ${r.code()}")
            } catch (e: Exception) { addLog("⚠️ [10/10] ${e.message}") }

            addLog("🎉 جاهز للدفع! orderId = $newOrderId")
            _paymentReady.value = true
        }
    }

    fun payWithSadad() {
        viewModelScope.launch {
            val currentOrderId = _orderId.value ?: run {
                addLog("❌ لا يوجد orderId")
                return@launch
            }

            var vOrderDataKey: String? = null
            try {
                addLog("💳 [1/3] اختيار سداد...")
                val r = RetrofitClient.api.selectSadadPayment(
                    lastName = TRAVELER_LAST_NAME,
                    headers = buildHeaders("3"),
                    request = SelectSadadRequest(
                        orderId = currentOrderId,
                        paymentRequests = listOf(SadadPaymentRequest(SadadPaymentMethod()))
                    )
                )
                val raw = if (r.isSuccessful) r.body()?.string() else r.errorBody()?.string()
                addLog("📦 [1/3] HTTP ${r.code()} — ${raw?.take(400)}")
                if (!r.isSuccessful) { addLog("❌ [1/3]"); return@launch }

                try {
                    val json = JsonParser.parseString(raw).asJsonObject
                    vOrderDataKey = json.get("vOrderDataKey")?.asString
                        ?: json.getAsJsonObject("data")?.get("vOrderDataKey")?.asString
                        ?: json.getAsJsonObject("data")?.getAsJsonObject("payment")?.get("vOrderDataKey")?.asString
                } catch (_: Exception) {}

                if (vOrderDataKey.isNullOrBlank()) {
                    addLog("⚠️ [1/3] لم نجد vOrderDataKey")
                    return@launch
                }
                addLog("✅ [1/3] vOrderDataKey = ${vOrderDataKey.take(20)}...")
            } catch (e: Exception) { addLog("❌ [1/3] ${e.message}"); return@launch }

            try {
                addLog("✅ [2/3] تأكيد الدفع...")
                val r = RetrofitClient.api.confirmPayment(
                    orderId = currentOrderId,
                    lastName = TRAVELER_LAST_NAME,
                    headers = buildHeaders("3"),
                    request = ConfirmPaymentRequest(
                        orderPaymentRequest = OrderPaymentRequest(
                            paymentRequests = listOf(SadadPaymentRequest(SadadPaymentMethod()))
                        ),
                        emailParameters = EmailParameters(DisplayParameters()),
                        vOrderDataKey = vOrderDataKey!!
                    )
                )
                val raw = if (r.isSuccessful) r.body()?.string() else r.errorBody()?.string()
                addLog("📦 [2/3] HTTP ${r.code()} — ${raw?.take(400)}")
                if (!r.isSuccessful) { addLog("❌ [2/3]"); return@launch }
                addLog("✅ [2/3] تم")
            } catch (e: Exception) { addLog("❌ [2/3] ${e.message}"); return@launch }

            try {
                addLog("🎫 [3/3] جلب PNR...")
                val r = RetrofitClient.api.getOrderConfirmation(
                    orderId = currentOrderId,
                    lastName = TRAVELER_LAST_NAME,
                    headers = buildHeaders("4")
                )
                if (r.isSuccessful) {
                    try {
                        val raw = r.body()?.string()
                        val json = JsonParser.parseString(raw).asJsonObject
                        val d = json.getAsJsonObject("data")
                        val extracted = d?.get("orderId")?.asString
                            ?: d?.get("pnr")?.asString
                            ?: d?.get("recordLocator")?.asString
                        if (!extracted.isNullOrBlank()) {
                            _pnr.value = extracted
                            addLog("🎉 [3/3] PNR = $extracted")
                            _sadadReady.value = true
                            return@launch
                        }
                    } catch (_: Exception) {}
                }
                _pnr.value = currentOrderId
                addLog("🎉 [3/3] PNR = $currentOrderId")
                _sadadReady.value = true
            } catch (e: Exception) {
                _pnr.value = currentOrderId
                addLog("⚠️ [3/3] ${e.message} — PNR = $currentOrderId")
                _sadadReady.value = true
            }
        }
    }

    fun addLog(message: String) {
        val curr = _searchLogs.value.toMutableList()
        curr.add(0, message)
        _searchLogs.value = curr
    }
}
