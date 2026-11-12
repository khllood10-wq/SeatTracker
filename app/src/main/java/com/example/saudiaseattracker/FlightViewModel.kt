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
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Response
import java.util.UUID
import kotlin.random.Random

// ... (بيانات المسافر والرحلة تبقى كما هي) ...

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
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            acceptLanguage = "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    // ... (باقي المتغيرات تبقى كما هي) ...

    private suspend fun captureSessionViaHttpCloak(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 1. اختبار سريع على httpbin.org
                addLog("🔄 اختبار httpcloak على httpbin.org...")
                val testResp = withTimeout(20_000L) {
                    httpCloakClient.get(
                        url = "https://httpbin.org/get",
                        headers = mapOf("Accept" to "application/json")
                    )
                }
                addLog("📄 httpbin → HTTP ${testResp.statusCode}, Length: ${testResp.text.length}")

                if (testResp.statusCode != 200) {
                    addLog("⚠️ httpbin أعاد كود ${testResp.statusCode}. المكتبة قد لا تعمل.")
                    return@withContext false
                }

                addLog("✅ httpcloak تعمل (httpbin رد 200)")

                // 2. الاختبار على السعودية
                val reeseCookie = hyperSession.getCookieHeader() ?: run {
                    addLog("❌ لا يوجد reese84 من Hyper")
                    return@withContext false
                }
                val reeseValue = reeseCookie.removePrefix("reese84=").trim()

                addLog("🔄 اختبار httpcloak على موقع السعودية...")
                val pageResp = withTimeout(30_000L) {
                    httpCloakClient.get(
                        url = "https://www.saudia.com/ar-SA",
                        headers = mapOf(
                            "Cookie" to reeseCookie,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language" to "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7"
                        )
                    )
                }

                addLog("📄 GET /ar-SA → HTTP ${pageResp.statusCode}, Length: ${pageResp.text.length}")

                val isImpervaBlock = pageResp.text.contains("_Incapsula_Resource") ||
                        pageResp.text.contains("Pardon Our Interruption") ||
                        pageResp.text.contains("Incapsula incident ID")

                if (isImpervaBlock) {
                    addLog("❌ لا زال Imperva يحظر httpcloak")
                    addLog("📋 Body: ${pageResp.text.take(300)}")
                    return@withContext false
                }

                if (pageResp.statusCode != 200) {
                    addLog("⚠️ HTTP ${pageResp.statusCode} - غير متوقع")
                    return@withContext false
                }

                addLog("🎉 httpcloak تجاوز Imperva بنجاح!")

                // 3. محاولة token/initialization
                addLog("🔄 محاولة token/initialization...")
                val initResp = withTimeout(30_000L) {
                    httpCloakClient.post(
                        url = "https://dapi.saudia.com/session/auth/web/token/initialization",
                        body = emptyMap(), // 👈 إرسال Map فارغ
                        headers = mapOf(
                            "Cookie" to reeseCookie,
                            "x-d-token" to reeseValue,
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Accept" to "application/json",
                            "platform" to "Web",
                            "version" to "27.2",
                            "Origin" to "https://www.saudia.com",
                            "Referer" to "https://www.saudia.com/ar-SA"
                        )
                    )
                }

                addLog("📄 POST /token/init → HTTP ${initResp.statusCode}")
                addLog("📋 Response: ${initResp.text.take(500)}")

                if (initResp.statusCode == 200) {
                    try {
                        val json = JSONObject(initResp.text)
                        val token = json.optString("access_token", json.optString("token", ""))
                        val sessionId = json.optString("session_id", "")

                        if (token.isNotBlank()) {
                            activeJwt = if (token.startsWith("Bearer ")) token else "Bearer $token"
                            if (sessionId.isNotBlank()) activeSaaSessionId = sessionId
                            activeXdToken = reeseValue
                            activeCookie = reeseCookie

                            addLog("🎉 نجحنا! JWT: ${activeJwt.take(30)}...")
                            addLog("🆔 saa-session-id: ${if (sessionId.isNotBlank()) sessionId else "❌ فارغ"}")
                            return@withContext true
                        } else {
                            addLog("⚠️ 200 لكن بدون access_token في الرد")
                        }
                    } catch (e: Exception) {
                        addLog("⚠️ فشل تحليل JSON: ${e.message}")
                    }
                }

                addLog("⚠️ token/initialization لم يعطنا JWT - سنحتاج Replay Payload")
                return@withContext false

            } catch (e: Exception) {
                addLog("❌ فشل httpcloak: ${e.javaClass.simpleName} - ${e.message}")
                return@withContext false
            }
        }
    }

    // ... (باقي الدوال تبقى كما هي) ...
}
