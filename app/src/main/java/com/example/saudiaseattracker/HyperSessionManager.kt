package com.example.saudiaseattracker

import android.content.Context
import android.util.Log
import com.github.chnouman.tlsclient.TLSClient
import com.github.chnouman.tlsclient.TLSClientBuilder
import com.github.chnouman.tlsclient.models.TLSClientProfile
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
        const val SAUDIA_HOST = "www.saudia.com"
        const val SAUDIA_URL = "https://www.saudia.com/ar-SA"
        const val PREFS = "hyper_prefs"
        const val KEY_TOKEN = "reese84_token"
        const val KEY_EXPIRY = "reese84_expiry_ms"
        const val KEY_COOKIE_DOMAIN = "reese84_cookie_domain"
    }

    // 👇 استخدام TLS Client بدلاً من OkHttp لتجاوز Imperva
    private val client = TLSClientBuilder()
        .withProfile(TLSClientProfile.CHROME_120)
        .withTimeout(30, TimeUnit.SECONDS)
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
            Log.e(TAG, "❌ خطأ فادح: مفتاح API فارغ! (تأكد من إضافته في GitHub Secrets وملف build.gradle)")
            return
        }

        Log.d(TAG, "🔄 جاري حل/تجديد reese84...")
        try {
            solveOrRenew()
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل الاتصال بـ Hyper Solutions: ${e.localizedMessage}", e)
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
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to acceptLanguage,
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1"
        )

        val response = client.get(SAUDIA_URL, headers = headers)
        val html = response.body ?: throw IOException("لا يوجد HTML")
        
        val pathRegex = Regex("""src\s*=\s*"(/[^/]+/[^?]+)\?.*"""")
        val fullRegex = Regex("""scriptElement\.src\s*=\s*"(.*?)"""")

        val pathMatch = pathRegex.find(html)
        val fullMatch = fullRegex.find(html)

        if (pathMatch == null || fullMatch == null) {
            Log.e(TAG, "❌ لم يتم العثور على مسار سكريبت التحدي (Imperva).")
            throw IOException("لم يتم العثور على مسار التحدي.")
        }

        val rawPath = pathMatch.groupValues[1]
        val fullPath = fullMatch.groupValues[1]

        sensorPath = "$rawPath?d=$SAUDIA_HOST"
        scriptUrl = "https://$SAUDIA_HOST$fullPath"
    }

    private fun fetchScript(url: String): String {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to acceptLanguage,
            "Sec-Fetch-Dest" to "script",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "same-origin"
        )
        val response = client.get(url, headers = headers)
        if (response.status != 200) {
            Log.e(TAG, "❌ فشل جلب السكريبت (HTTP ${response.status})")
            throw IOException("فشل جلب السكريبت: ${response.status}")
        }
        return response.body ?: throw IOException("السكريبت فارغ")
    }

    private fun fetchEgressIp(): String {
        val headers = mapOf("x-api-key" to apiKey)
        val response = client.get("https://ip.hypersolutions.co/ip", headers = headers)
        val body = response.body ?: throw IOException("رد فارغ عند جلب IP")
        if (response.status != 200) {
            Log.e(TAG, "❌ فشل جلب IP من Hyper (HTTP ${response.status}): $body")
            throw IOException("فشل جلب IP: ${response.status}")
        }
        return JSONObject(body).getString("ip")
    }

    private fun tryFetchPowIfNeeded(): String? {
        return try {
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Content-Type" to "text/plain; charset=utf-8"
            )
            val response = client.post("https://$SAUDIA_HOST${sensorPath!!}", body = "{\"f\":\"gpc\"}", headers = headers)
            val body = response.body
            if (response.status == 200 && !body.isNullOrBlank() && body.length > 10) {
                Log.d(TAG, "✅ PoW مطلوب وتم جلبه")
                body
            } else {
                Log.d(TAG, "ℹ️ لا يبدو أن PoW مطلوب (HTTP ${response.status})")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ أثناء محاولة جلب PoW: ${e.message}")
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

        val headers = mapOf(
            "x-api-key" to apiKey,
            "Content-Type" to "application/json"
        )
        val response = client.post("$HYPER_BASE/reese84", body = body, headers = headers)
        val respBody = response.body ?: throw IOException("Hyper API: رد فارغ")

        if (response.status != 200) {
            Log.e(TAG, "❌ رفضت Hyper الطلب! (HTTP ${response.status}) | السبب: $respBody")
            throw IOException("Hyper API ${response.status}: $respBody")
        }
        return JSONObject(respBody).getString("payload")
    }

    private data class TokenResult(val token: String, val renewInSec: Long, val cookieDomain: String)

    private fun submitSensor(sensorPath: String, payload: String): TokenResult {
        val url = "https://$SAUDIA_HOST$sensorPath"
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Content-Type" to "text/plain; charset=utf-8",
            "Accept" to "*/*"
        )
        val response = client.post(url, body = payload, headers = headers)
        val body = response.body ?: throw IOException("رد فارغ من إرسال sensor")

        if (response.status != 200) {
            Log.e(TAG, "❌ فشل إرسال sensor إلى saudia (HTTP ${response.status}): $body")
            throw IOException("فشل إرسال sensor (${response.status})")
        }
        val json = JSONObject(body)
        return TokenResult(
            token = json.getString("token"),
            renewInSec = json.optLong("renewInSec", 900L),
            cookieDomain = json.optString("cookieDomain", SAUDIA_HOST)
        )
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
