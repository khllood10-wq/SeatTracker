package com.example.saudiaseattracker

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HyperSessionManager
 * - يحل تحدي Imperva reese84 (Dynamic) عبر Hyper Solutions API.
 * - يولد sensor، يرسله للموقع، ويحصل على reese84 token.
 * - يجدده تلقائياً قبل انتهاء renewInSec.
 * - يخزن التوكن في الذاكرة والـ SharedPreferences.
 */
class HyperSessionManager(
    private val context: Context,
    private val apiKey: String,
    private val userAgent: String = DEFAULT_UA,
    private val acceptLanguage: String = "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7"
) {
    companion object {
        const val TAG = "HyperSession"
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val HYPER_BASE = "https://incapsula.hypersolutions.co"
        const val SAUDIA_HOST = "www.saudia.com"
        const val SAUDIA_URL = "https://www.saudia.com/ar-SA"
        const val PREFS = "hyper_prefs"
        const val KEY_TOKEN = "reese84_token"
        const val KEY_EXPIRY = "reese84_expiry_ms"
        const val KEY_COOKIE_DOMAIN = "reese84_cookie_domain"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var currentToken: String? = prefs.getString(KEY_TOKEN, null)
    @Volatile private var expiryMs: Long = prefs.getLong(KEY_EXPIRY, 0L)
    @Volatile private var cookieDomain: String = prefs.getString(KEY_COOKIE_DOMAIN, SAUDIA_HOST) ?: SAUDIA_HOST

    // مسارات ثابتة يتم اكتشافها مرة واحدة
    @Volatile private var sensorPath: String? = null   // مثال: /abc-def/123456?d=www.saudia.com
    @Volatile private var scriptUrl: String? = null    // مثال: https://www.saudia.com/abc-def/123456?s=xyz

    /** هل التوكن الحالي صالح لأكثر من [minRemainingMs]؟ */
    fun hasFreshToken(minRemainingMs: Long = 120_000L): Boolean {
        val t = currentToken ?: return false
        return t.isNotBlank() && (expiryMs - System.currentTimeMillis()) > minRemainingMs
    }

    /** يعيد التوكن الحالي (قد يكون منتهياً). */
    fun getToken(): String? = currentToken

    /** يعيد الكوكي بالشكل الجاهز للاستخدام في هيدر Cookie. */
    fun getCookieHeader(): String? {
        val t = currentToken ?: return null
        return "reese84=$t"
    }

    /**
     * يضمن وجود توكن صالح. إذا كان هناك توكن صالح، لا يفعل شيئاً.
     * وإلا يحل التحدي من جديد (أو يجدد).
     */
    @Synchronized
    fun ensureFreshToken(forceRenew: Boolean = false) {
        if (!forceRenew && hasFreshToken()) {
            Log.d(TAG, "✅ التوكن الحالي صالح (متبقي ${(expiryMs - System.currentTimeMillis()) / 1000}s)")
            return
        }
        Log.d(TAG, "🔄 جاري حل/تجديد reese84...")
        try {
            solveOrRenew()
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل حل reese84: ${e.message}", e)
            throw e
        }
    }

    private fun solveOrRenew() {
        // 1) اكتشاف المسارات إذا لم تكن معروفة
        if (sensorPath == null || scriptUrl == null) {
            discoverPaths()
        }

        // 2) جلب السكريبت (مطلوب في كل مرة)
        val script = fetchScript(scriptUrl!!)

        // 3) جلب IP الخروج (يجب أن يطابق IP العميل)
        val egressIp = fetchEgressIp()

        // 4) (اختياري) جلب PoW إذا كانت الصفحة الديناميكية تتطلبه
        val pow = tryFetchPowIfNeeded()

        // 5) توليد sensor عبر Hyper API
        val sensorPayload = generateSensor(
            script = script,
            scriptUrl = scriptUrl!!,
            pageUrl = SAUDIA_URL,
            ip = egressIp,
            pow = pow
        )

        // 6) إرسال sensor إلى الموقع للحصول على token
        val result = submitSensor(sensorPath!!, sensorPayload)

        // 7) حفظ النتيجة
        currentToken = result.token
        expiryMs = System.currentTimeMillis() + result.renewInSec * 1000L
        cookieDomain = result.cookieDomain

        prefs.edit()
            .putString(KEY_TOKEN, result.token)
            .putLong(KEY_EXPIRY, expiryMs)
            .putString(KEY_COOKIE_DOMAIN, cookieDomain)
            .apply()

        Log.d(TAG, "✅ تم الحصول على reese84 token (صالح ${result.renewInSec}s)")
    }

    // ---------- خطوات التنفيذ ----------

    private fun discoverPaths() {
        Log.d(TAG, "🔍 اكتشاف مسارات التحدي...")
        val req = Request.Builder()
            .url(SAUDIA_URL)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", acceptLanguage)
            .build()

        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: throw IOException("لا يوجد HTML")
            // استخراج scriptPath (بدون query) و fullScriptPath (مع query)
            // من وثائق Hyper Solutions:
            // pathRegex = /src\s*=\s*"(\/[^/]+\/[^?]+)\?.*"/
            // fullPathRegex = /scriptElement\.src\s*=\s*"(.*?)"/
            val pathRegex = Regex("""src\s*=\s*"(/[^/]+/[^?]+)\?.*"""")
            val fullRegex = Regex("""scriptElement\.src\s*=\s*"(.*?)"""")

            val pathMatch = pathRegex.find(html)
            val fullMatch = fullRegex.find(html)

            if (pathMatch == null || fullMatch == null) {
                throw IOException("لم يتم العثور على مسار سكريبت التحدي. قد لا تكون الصفحة محمية أو تغيرت البنية.")
            }

            val rawPath = pathMatch.groupValues[1] // /abc/123
            val fullPath = fullMatch.groupValues[1] // /abc/123?s=xyz

            sensorPath = "$rawPath?d=$SAUDIA_HOST"
            scriptUrl = "https://$SAUDIA_HOST$fullPath"

            Log.d(TAG, "sensorPath=$sensorPath")
            Log.d(TAG, "scriptUrl=$scriptUrl")
        }
    }

    private fun fetchScript(url: String): String {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .header("Accept-Language", acceptLanguage)
            .header("Sec-Fetch-Dest", "script")
            .header("Sec-Fetch-Mode", "no-cors")
            .header("Sec-Fetch-Site", "same-origin")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("فشل جلب السكريبت: ${resp.code}")
            return resp.body?.string() ?: throw IOException("السكريبت فارغ")
        }
    }

    private fun fetchEgressIp(): String {
        val req = Request.Builder()
            .url("https://ip.hypersolutions.co/ip")
            .get()
            .header("x-api-key", apiKey)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("فشل جلب IP")
            val json = JSONObject(body)
            return json.getString("ip")
        }
    }

    /**
     * إذا كانت الصفحة الديناميكية تتطلب PoW، نرسل POST {"f":"gpc"} إلى sensorPath
     * ونستخرج النص الناتج. إذا لم تكن مطلوبة، نعيد null.
     */
    private fun tryFetchPowIfNeeded(): String? {
        return try {
            val req = Request.Builder()
                .url("https://$SAUDIA_HOST${sensorPath!!}")
                .post("{\"f\":\"gpc\"}".toRequestBody("text/plain; charset=utf-8".toMediaType()))
                .header("User-Agent", userAgent)
                .header("Content-Type", "text/plain; charset=utf-8")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrBlank() && body.length > 10) {
                    Log.d(TAG, "PoW مطلوب، تم جلبه (${body.length} حرف)")
                    body
                } else {
                    Log.d(TAG, "لا يبدو أن PoW مطلوب (رمز ${resp.code})")
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "تخطي PoW: ${e.message}")
            null
        }
    }

    private fun generateSensor(
        script: String,
        scriptUrl: String,
        pageUrl: String,
        ip: String,
        pow: String?
    ): String {
        val body = JSONObject().apply {
            put("userAgent", userAgent)
            put("pageUrl", pageUrl)
            put("scriptUrl", scriptUrl)
            put("script", script)
            put("ip", ip)
            put("acceptLanguage", acceptLanguage)
            if (!pow.isNullOrBlank()) put("pow", pow)
        }.toString()

        val req = Request.Builder()
            .url("$HYPER_BASE/reese84")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .build()

        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string() ?: throw IOException("Hyper API: رد فارغ")
            if (!resp.isSuccessful) {
                throw IOException("Hyper API ${resp.code}: $respBody")
            }
            val json = JSONObject(respBody)
            return json.getString("payload")
        }
    }

    private data class TokenResult(val token: String, val renewInSec: Long, val cookieDomain: String)

    private fun submitSensor(sensorPath: String, payload: String): TokenResult {
        val url = "https://$SAUDIA_HOST$sensorPath"
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .header("User-Agent", userAgent)
            .header("Content-Type", "text/plain; charset=utf-8")
            .header("Accept", "*/*")
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("رد فارغ من إرسال sensor")
            if (!resp.isSuccessful) {
                throw IOException("فشل إرسال sensor (${resp.code}): $body")
            }
            val json = JSONObject(body)
            return TokenResult(
                token = json.getString("token"),
                renewInSec = json.optLong("renewInSec", 900L),
                cookieDomain = json.optString("cookieDomain", SAUDIA_HOST)
            )
        }
    }
}
