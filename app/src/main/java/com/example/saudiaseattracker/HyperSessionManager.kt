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
        const val SAUDIA_URL = "https://saudia.com/ar-SA"
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
        .connectionSpecs(listOf(
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                .build(),
            ConnectionSpec.CLEARTEXT
        ))
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
    fun getCookieHeader(): String? {
        val t = currentToken ?: return null
        return "reese84=$t"
    }

    @Synchronized
    fun ensureFreshToken(forceRenew: Boolean = false) {
        if (!forceRenew && hasFreshToken()) {
            Log.d(TAG, "✅ التوكن الحالي صالح")
            DebugBus.emit(DebugEntry(
                source = DebugSource.HYPER,
                title = "♻️ Hyper: التوكن الحالي صالح",
                body = "expiry=${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(expiryMs))}\nremaining=${(expiryMs - System.currentTimeMillis()) / 1000}s"
            ))
            return
        }

        if (apiKey.isBlank()) {
            val msg = "❌ مفتاح HYPER_API_KEY فارغ!"
            Log.e(TAG, msg)
            DebugBus.emit(DebugEntry(
                source = DebugSource.HYPER,
                title = msg,
                body = "تأكد من إعداد HYPER_API_KEY في build.gradle.kts أو GitHub Secrets"
            ))
            throw IOException(msg)
        }

        Log.d(TAG, "🔄 جاري حل/تجديد reese84...")
        DebugBus.emit(DebugEntry(
            source = DebugSource.HYPER,
            title = "🔄 Hyper: بدء تجديد reese84",
            body = "forceRenew=$forceRenew"
        ))

        try {
            runBlocking(Dispatchers.IO) { solveOrRenew() }
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل Hyper: ${e.localizedMessage}", e)
            DebugBus.emit(DebugEntry(
                source = DebugSource.HYPER,
                title = "❌ فشل Hyper",
                body = "${e.javaClass.simpleName}: ${e.message}"
            ))
            throw e
        }
    }

    private fun solveOrRenew() {
        if (sensorPath == null || scriptUrl == null) discoverPaths()

        val script = fetchScript(scriptUrl!!)
        Log.d(TAG, "📜 طول السكريبت: ${script.length}")
        DebugBus.emit(DebugEntry(
            source = DebugSource.HYPER,
            title = "📜 Hyper: تم جلب script التحدي",
            body = "length=${script.length}c\nurl=$scriptUrl"
        ))

        val egressIp = fetchEgressIp()
        Log.d(TAG, "🌐 IP المستخدم: $egressIp")
        DebugBus.emit(DebugEntry(
            source = DebugSource.HYPER,
            title = "🌐 Hyper: egress IP",
            body = "ip=$egressIp"
        ))

        val sensorPayload = generateSensor(
            script = script,
            scriptUrl = scriptUrl!!,
            pageUrl = SAUDIA_URL,
            ip = egressIp
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

        Log.d(TAG, "✅ reese84 token (صالح ${result.renewInSec}s)")
        DebugBus.emit(DebugEntry(
            source = DebugSource.HYPER,
            title = "✅ Hyper: reese84 جاهز",
            body = "token.prefix=${result.token.take(40)}...\nlength=${result.token.length}\nrenewInSec=${result.renewInSec}\ncookieDomain=${result.cookieDomain}"
        ))
    }

    private fun discoverPaths() {
        Log.d(TAG, "🔍 اكتشاف مسارات التحدي...")
        val req = Request.Builder()
            .url(SAUDIA_URL)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept-Language", acceptLanguage)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .build()

        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: throw IOException("لا يوجد HTML")
            DebugBus.emit(DebugEntry(
                source = DebugSource.HYPER,
                method = "GET",
                url = SAUDIA_URL,
                statusCode = resp.code,
                title = "🔍 Hyper: GET /ar-SA لاكتشاف المسار → ${resp.code}",
                body = "html.length=${html.length}c\nfirst300=${html.take(300)}"
            ))

            val pathRegex = Regex("""src\s*=\s*"(/[^/]+/[^?]+)\?.*"""")
            val fullRegex = Regex("""scriptElement\.src\s*=\s*"(.*?)"""")

            val pathMatch = pathRegex.find(html)
            val fullMatch = fullRegex.find(html)

            if (pathMatch == null || fullMatch == null) {
                val msg = "❌ لم يتم العثور على مسار سكريبت التحدي"
                DebugBus.emit(DebugEntry(
                    source = DebugSource.HYPER,
                    title = msg,
                    body = "pathMatch=${pathMatch != null}, fullMatch=${fullMatch != null}"
                ))
                throw IOException(msg)
            }

            val rawPath = pathMatch.groupValues[1]
            val fullPath = fullMatch.groupValues[1]

            sensorPath = "$rawPath?d=$SAUDIA_HOST"
            scriptUrl = "https://$SAUDIA_HOST$fullPath"

            Log.d(TAG, "✅ sensorPath=$sensorPath")
            Log.d(TAG, "✅ scriptUrl=$scriptUrl")
            DebugBus.emit(DebugEntry(
                source = DebugSource.HYPER,
                title = "✅ Hyper: تم اكتشاف المسارات",
                body = "sensorPath=$sensorPath\nscriptUrl=$scriptUrl"
            ))
        }
    }

    private fun fetchScript(url: String): String {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugBus.emit(DebugEntry(
                    source = DebugSource.HYPER,
                    method = "GET",
                    url = url,
                    statusCode = resp.code,
                    title = "❌ Hyper: فشل جلب script → ${resp.code}",
                    body = resp.body?.string()?.take(2000) ?: ""
                ))
                throw IOException("فشل جلب السكريبت: ${resp.code}")
            }
            return resp.body?.string() ?: throw IOException("السكريبت فارغ")
        }
    }

    private fun fetchEgressIp(): String {
        val req = Request.Builder()
            .url(IP_FETCH_URL)
            .get()
            .header("x-api-key", apiKey)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("رد فارغ عند جلب IP")
            if (!resp.isSuccessful) {
                DebugBus.emit(DebugEntry(
                    source = DebugSource.HYPER,
                    method = "GET",
                    url = IP_FETCH_URL,
                    statusCode = resp.code,
                    title = "❌ Hyper: فشل جلب IP → ${resp.code}",
                    body = body.take(2000)
                ))
                throw IOException("فشل جلب IP: ${resp.code} - $body")
            }
            return JSONObject(body).getString("ip")
        }
    }

    private fun generateSensor(
        script: String,
        scriptUrl: String,
        pageUrl: String,
        ip: String
    ): String {
        val body = JSONObject().apply {
            put("userAgent", userAgent)
            put("pageUrl", pageUrl)
            put("scriptUrl", scriptUrl)
            put("script", script)
            put("ip", ip)
            put("acceptLanguage", acceptLanguage)
        }.toString()

        Log.d(TAG, "📤 Hyper Request Body size: ${body.length}")
        DebugBus.emit(DebugEntry(
            source = DebugSource.HYPER,
            method = "POST",
            url = "$HYPER_BASE/reese84",
            title = "📤 Hyper: POST /reese84 (generate sensor)",
            headers = "x-api-key: <redacted>\nContent-Type: application/json",
            body = "request.size=${body.length}c\nscript.size=${script.length}c\nip=$ip"
        ))

        val req = Request.Builder()
            .url("$HYPER_BASE/reese84")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .build()

        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string() ?: throw IOException("Hyper API: رد فارغ")
            if (!resp.isSuccessful) {
                DebugBus.emit(DebugEntry(
                    source = DebugSource.HYPER,
                    method = "POST",
                    url = "$HYPER_BASE/reese84",
                    statusCode = resp.code,
                    title = "❌ Hyper: رفض طلب sensor → ${resp.code}",
                    body = respBody.take(4000)
                ))
                throw IOException("Hyper API ${resp.code}: $respBody")
            }
            val payload = JSONObject(respBody).getString("payload")
            DebugBus.emit(DebugEntry(
                source = DebugSource.HYPER,
                method = "POST",
                url = "$HYPER_BASE/reese84",
                statusCode = resp.code,
                title = "✅ Hyper: تم توليد sensor payload",
                body = "payload.length=${payload.length}c\nprefix=${payload.take(80)}..."
            ))
            return payload
        }
    }

    private data class TokenResult(val token: String, val renewInSec: Long, val cookieDomain: String)

    private fun submitSensor(sensorPath: String, payload: String): TokenResult {
        val url = "https://$SAUDIA_HOST$sensorPath"
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .header("Content-Type", "text/plain; charset=utf-8")
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("رد فارغ من إرسال sensor")
            if (!resp.isSuccessful) {
                DebugBus.emit(DebugEntry(
                    source = DebugSource.HYPER,
                    method = "POST",
                    url = url,
                    statusCode = resp.code,
                    title = "❌ Hyper: فشل submit sensor → ${resp.code}",
                    body = body.take(4000)
                ))
                throw IOException("فشل إرسال sensor (${resp.code})")
            }
            val json = JSONObject(body)
            val token = json.getString("token")
            val renewInSec = json.optLong("renewInSec", 900L)
            val cookieDomain = json.optString("cookieDomain", SAUDIA_HOST)

            DebugBus.emit(DebugEntry(
                source = DebugSource.HYPER,
                method = "POST",
                url = url,
                statusCode = resp.code,
                title = "✅ Hyper: تم الحصول على reese84 → ${resp.code}",
                body = "token.prefix=${token.take(40)}...\nlength=${token.length}\nrenewInSec=$renewInSec\ncookieDomain=$cookieDomain"
            ))

            return TokenResult(token, renewInSec, cookieDomain)
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
