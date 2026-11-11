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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class HyperSessionManager(
    context: Context,
    rawApiKey: String,
    private val userAgent: String = DEFAULT_UA,
    private val acceptLanguage: String = "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7"
) {
    private val apiKey = rawApiKey.trim()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val TAG = "HyperSessionError"
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val HYPER_BASE = "https://incapsula.hypersolutions.co"
        const val IP_FETCH_URL = "https://ip.hypersolutions.co/ip"
        const val SAUDIA_HOST = "www.saudia.com"
        const val SAUDIA_URL = "https://www.saudia.com/ar-SA"
        const val PREFS = "hyper_prefs"
        const val KEY_TOKEN = "reese84_token"
        const val KEY_EXPIRY = "reese84_expiry_ms"
        const val KEY_COOKIE_DOMAIN = "reese84_cookie_domain"
    }

    // عميل OkHttp بسيط جداً ليتطابق مع curl
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
    fun getCookieHeader(): String? = currentToken?.let { "reese84=$it" }

    @Synchronized
    fun ensureFreshToken(forceRenew: Boolean = false) {
        if (!forceRenew && hasFreshToken()) return
        if (apiKey.isBlank()) throw IOException("مفتاح API فارغ!")
        try {
            runBlocking(Dispatchers.IO) { solveOrRenew() }
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل Hyper: ${e.localizedMessage}", e)
            throw e
        }
    }

    private fun solveOrRenew() {
        if (sensorPath == null || scriptUrl == null) discoverPaths()
        val script = fetchScript(scriptUrl!!)
        val egressIp = fetchEgressIp()
        val pow = tryFetchPowIfNeeded()
        val sensorPayload = generateSensor(script, scriptUrl!!, egressIp, pow)
        val result = submitSensor(sensorPath!!, sensorPayload)
        currentToken = result.token
        expiryMs = System.currentTimeMillis() + result.renewInSec * 1000L
        prefs.edit().putString(KEY_TOKEN, result.token).putLong(KEY_EXPIRY, expiryMs).apply()
        Log.d(TAG, "✅ تم الحصول على التوكن (صالح ${result.renewInSec}s)")
    }

    private fun discoverPaths() {
        val req = Request.Builder().url(SAUDIA_URL).get()
            .header("User-Agent", userAgent)
            .header("Accept-Language", acceptLanguage)
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: throw IOException("HTML فارغ")
            val pathRegex = Regex("""src\s*=\s*"(/[^/]+/[^?]+)\?.*"""")
            val fullRegex = Regex("""scriptElement\.src\s*=\s*"(.*?)"""")
            val pathMatch = pathRegex.find(html) ?: throw IOException("مسار السكريبت غير موجود")
            val fullMatch = fullRegex.find(html) ?: throw IOException("مسار السكريبت الكامل غير موجود")
            sensorPath = "${pathMatch.groupValues[1]}?d=$SAUDIA_HOST"
            scriptUrl = "https://$SAUDIA_HOST${fullMatch.groupValues[1]}"
        }
    }

    private fun fetchScript(url: String): String {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", userAgent)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("فشل جلب السكريبت: ${resp.code}")
            return resp.body?.string() ?: throw IOException("السكريبت فارغ")
        }
    }

    private fun fetchEgressIp(): String {
        val req = Request.Builder().url(IP_FETCH_URL).get()
            .header("x-api-key", apiKey)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("رد IP فارغ")
            if (!resp.isSuccessful) throw IOException("فشل جلب IP: ${resp.code}")
            return JSONObject(body).getString("ip")
        }
    }

    private fun tryFetchPowIfNeeded(): String? {
        return try {
            val req = Request.Builder()
                .url("https://$SAUDIA_HOST${sensorPath!!}")
                .post("{\"f\":\"gpc\"}".toRequestBody("text/plain; charset=utf-8".toMediaType()))
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("User-Agent", userAgent)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrBlank() && body.length > 10) body else null
            }
        } catch (e: Exception) { null }
    }

    private fun generateSensor(script: String, scriptUrl: String, ip: String, pow: String?): String {
        // استخدام scriptHash (موصى به من Hyper لتقليل حجم الطلب)
        val scriptHash = MessageDigest.getInstance("SHA-256")
            .digest(script.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val body = JSONObject().apply {
            put("userAgent", userAgent)
            put("pageUrl", SAUDIA_URL)
            put("scriptUrl", scriptUrl)
            put("scriptHash", scriptHash) // إرسال البصمة بدلاً من السكريبت كاملاً
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
            val respBody = resp.body?.string() ?: throw IOException("رد Hyper فارغ")
            if (!resp.isSuccessful) {
                Log.e(TAG, "❌ رفضت Hyper الطلب! (HTTP ${resp.code}) | السبب: $respBody")
                // إذا طلب الخادم السكريبت كاملاً (422)، نعيد المحاولة بإرساله
                if (resp.code == 422) {
                    return generateSensorWithFullScript(script, scriptUrl, ip, pow)
                }
                throw IOException("Hyper API ${resp.code}: $respBody")
            }
            return JSONObject(respBody).getString("payload")
        }
    }

    private fun generateSensorWithFullScript(script: String, scriptUrl: String, ip: String, pow: String?): String {
        val body = JSONObject().apply {
            put("userAgent", userAgent)
            put("pageUrl", SAUDIA_URL)
            put("scriptUrl", scriptUrl)
            put("script", script) // إرسال السكريبت كاملاً
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
            val respBody = resp.body?.string() ?: throw IOException("رد Hyper فارغ")
            if (!resp.isSuccessful) throw IOException("Hyper API ${resp.code}: $respBody")
            return JSONObject(respBody).getString("payload")
        }
    }

    private data class TokenResult(val token: String, val renewInSec: Long, val cookieDomain: String)

    private fun submitSensor(sensorPath: String, payload: String): TokenResult {
        val url = "https://$SAUDIA_HOST$sensorPath"
        val req = Request.Builder().url(url)
            .post(payload.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .header("Content-Type", "text/plain; charset=utf-8")
            .header("User-Agent", userAgent)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("رد السعودية فارغ")
            if (!resp.isSuccessful) throw IOException("فشل إرسال sensor: ${resp.code}")
            val json = JSONObject(body)
            return TokenResult(json.getString("token"), json.optLong("renewInSec", 900L), json.optString("cookieDomain", SAUDIA_HOST))
        }
    }

    fun expiryAt(): Long = expiryMs
    fun forceClear() {
        currentToken = null; expiryMs = 0L; sensorPath = null; scriptUrl = null
        prefs.edit().clear().apply()
    }
}
