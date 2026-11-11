package com.example.saudiaseattracker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HyperSessionManager(
    private val context: Context,
    private val apiKey: String,
    private val userAgent: String = DEFAULT_UA,
    private val acceptLanguage: String = "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7"
) {
    companion object {
        const val TAG = "HyperSessionError"
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val HYPER_BASE = "https://incapsula.hypersolutions.co"
        
        // 👇 تم تصحيح الرابط إلى الصيغة الصحيحة
        const val HYPER_IP_URL = "https://ip.hypersolutions.co/ip" 
        
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
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept-Language", acceptLanguage)

            // نضيف هيدرات المتصفح فقط لطلبات السعودية (وليس لطلبات Hyper API)
            if (original.url.host.contains("saudia.com")) {
                requestBuilder.header("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                requestBuilder.header("sec-ch-ua-mobile", "?0")
                requestBuilder.header("sec-ch-ua-platform", "\"Windows\"")
            }
            chain.proceed(requestBuilder.build())
        }
        .connectionSpecs(listOf(
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                .build(),
            ConnectionSpec.CLEARTEXT
        ))
        .build()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var currentToken: String? = prefs.getString(KEY_TOKEN, null)
    @Volatile private var expiryMs: Long = prefs.getLong(KEY_EXPIRY, 0L)
    @Volatile private var cookieDomain: String = prefs.getString(KEY_COOKIE_DOMAIN, SAUDIA_HOST) ?: SAUDIA_HOST

    @Volatile private var sensorPath: String? = null
    @Volatile private var scriptUrl: String? = null

    fun hasFreshToken(minRemainingMs: Long = 120_000L): Boolean {
        val t = currentToken ?: return false
        return t.isNotBlank() && (expiryMs - System.currentTimeMillis()) > minRemainingMs
    }

    fun getToken(): String? = currentToken

    fun getCookieHeader(): String? {
        val t = currentToken ?: return null
        return "reese84=$t"
    }

    @Synchronized
    fun ensureFreshToken(forceRenew: Boolean = false) {
        if (!forceRenew && hasFreshToken()) {
            Log.d(TAG, "✅ التوكن الحالي صالح")
            return
        }
        
        if (apiKey.isBlank()) {
            Log.e(TAG, "❌ خطأ فادح: مفتاح API فارغ!")
            throw IOException("مفتاح API فارغ! تأكد من إعداده في GitHub.")
        }

        // نطبع المفتاح للتأكد أنه وصل صح (نطبع أول 4 وآخر 4 أحرف فقط للأمان)
        val maskedKey = if (apiKey.length > 8) "${apiKey.take(4)}...${apiKey.takeLast(4)}" else "قصير جداً"
        Log.d(TAG, "🔑 المفتاح المستخدم حالياً: $maskedKey")

        Log.d(TAG, "🔄 جاري حل/تجديد reese84...")
        try {
            runBlocking(Dispatchers.IO) {
                solveOrRenew()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل الاتصال بـ Hyper Solutions: ${e.localizedMessage}", e)
            throw e 
        }
    }

    private fun solveOrRenew() {
        if (sensorPath == null || scriptUrl == null) {
            discoverPaths()
        }

        val script = fetchScript(scriptUrl!!)
        val egressIp = fetchEgressIp()
        val pow = tryFetchPowIfNeeded()

        val sensorPayload = generateSensor(
            script = script,
            scriptUrl = scriptUrl!!,
            pageUrl = SAUDIA_URL,
            ip = egressIp,
            pow = pow
        )

        val result = submitSensor(sensorPath!!, sensorPayload)

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

    private fun discoverPaths() {
        Log.d(TAG, "🔍 اكتشاف مسارات التحدي...")
        val req = Request.Builder()
            .url(SAUDIA_URL)
            .get()
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .build()

        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: throw IOException("لا يوجد HTML")
            val pathRegex = Regex("""src\s*=\s*"(/[^/]+/[^?]+)\?.*"""")
            val fullRegex = Regex("""scriptElement\.src\s*=\s*"(.*?)"""")

            val pathMatch = pathRegex.find(html)
            val fullMatch = fullRegex.find(html)

            if (pathMatch == null || fullMatch == null) {
                Log.e(TAG, "❌ لم يتم العثور على مسار سكريبت التحدي.")
                throw IOException("لم يتم العثور على مسار سكريبت التحدي.")
            }

            val rawPath = pathMatch.groupValues[1]
            val fullPath = fullMatch.groupValues[1]

            sensorPath = "$rawPath?d=$SAUDIA_HOST"
            scriptUrl = "https://$SAUDIA_HOST$fullPath"
        }
    }

    private fun fetchScript(url: String): String {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "*/*")
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
            .url(HYPER_IP_URL) // الرابط الصحيح
            .get()
            .header("x-api-key", apiKey)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("رد فارغ عند جلب IP")
            if (!resp.isSuccessful) {
                Log.e(TAG, "❌ فشل جلب IP. الكود: ${resp.code}، الرد: $body")
                throw IOException("فشل جلب IP: ${resp.code} - $body")
            }
            val ip = JSONObject(body).getString("ip")
            Log.d(TAG, "🌐 IP المستخدم: $ip")
            return ip
        }
    }

    private fun tryFetchPowIfNeeded(): String? {
        return try {
            val req = Request.Builder()
                .url("https://$SAUDIA_HOST${sensorPath!!}")
                .post("{\"f\":\"gpc\"}".toRequestBody("text/plain; charset=utf-8".toMediaType()))
                .header("Content-Type", "text/plain; charset=utf-8")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrBlank() && body.length > 10) {
                    body 
                } else {
                    null
                }
            }
        } catch (e: Exception) {
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

        Log.d(TAG, "📤 Hyper Request Body: $body")

        val req = Request.Builder()
            .url("$HYPER_BASE/reese84")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .build()

        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string() ?: throw IOException("Hyper API: رد فارغ")
            if (!resp.isSuccessful) {
                Log.e(TAG, "❌ رفضت Hyper الطلب! (HTTP ${resp.code}) | السبب: $respBody")
                throw IOException("Hyper API ${resp.code}: $respBody")
            }
            return JSONObject(respBody).getString("payload")
        }
    }

    private data class TokenResult(val token: String, val renewInSec: Long, val cookieDomain: String)

    private fun submitSensor(sensorPath: String, payload: String): TokenResult {
        val url = "https://$SAUDIA_HOST$sensorPath"
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .header("Content-Type", "text/plain; charset=utf-8")
            .header("Accept", "*/*")
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("رد فارغ من إرسال sensor")
            if (!resp.isSuccessful) {
                Log.e(TAG, "❌ فشل إرسال sensor إلى saudia (HTTP ${resp.code}): $body")
                throw IOException("فشل إرسال sensor (${resp.code})")
            }
            val json = JSONObject(body)
            return TokenResult(
                token = json.getString("token"),
                renewInSec = json.optLong("renewInSec", 900L),
                cookieDomain = json.optString("cookieDomain", SAUDIA_HOST)
            )
        }
    }

    fun expiryAt(): Long = expiryMs

    fun forceClear() {
        currentToken = null
        expiryMs = 0L
        sensorPath = null
        scriptUrl = null
        prefs.edit().clear().apply()
    }
}
