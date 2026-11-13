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
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/155.0.0.0 Safari/537.36"
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
    fun getCookieDomain(): String = cookieDomain

    @Synchronized
    fun ensureFreshToken(forceRenew: Boolean = false) {
        if (!forceRenew && hasFreshToken()) {
            DebugBus.emit(DebugEntry(
                source = DebugSource.HYPER,
                title = "♻️ Hyper: التوكن الحالي صالح",
                body = "expiry=${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(expiryMs))}\n" +
                        "remaining=${(expiryMs - System.currentTimeMillis()) / 1000}s"
            ))
            return
        }

        if (apiKey.isBlank()) {
            val msg = "❌ مفتاح HYPER_API_KEY فارغ!"
            DebugBus.emit(DebugEntry(source = DebugSource.HYPER, title = msg, body = "تأكد من إعداد HYPER_API_KEY"))
            throw IOException(msg)
        }

        DebugBus.emit(DebugEntry(
            source = DebugSource.HYPER,
            title = "🔄 Hyper: بدء تجديد reese84",
            body = "forceRenew=$forceRenew\nUA=$userAgent"
        ))

        try {
            runBlocking(Dispatchers.IO) { solveOrRenew() }
        } catch (e: Exception) {
            DebugBus.emit(DebugEntry(
                source = DebugSource.HYPER,
                title = "❌ فشل Hyper",
                body = "${e.javaClass.simpleName}: ${e.message}"
            ))
            throw e
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  🆕 حل تحدٍّ لصفحة محددة (يستخدم pageUrl مختلف)
    // ═══════════════════════════════════════════════════════════
    fun solveForPage(pageUrl: String, forceRenew: Boolean = true): String? {
        if (apiKey.isBlank()) throw IOException("HYPER_API_KEY فارغ")

        DebugBus.emit(DebugEntry(
            source = DebugSource.HYPER,
            title = "🆕 Hyper: حل تحدٍّ مخصص",
            body = "pageUrl=$pageUrl"
        ))

        return try {
            runBlocking(Dispatchers.IO) {
                // اكتشاف مسار التحدي من الصفحة المطلوبة
                val pageHost = java.net.URI(pageUrl).host
                val discoverUrl = "https://$pageHost/ar-SA"

                val req = Request.Builder()
                    .url(discoverUrl)
                    .get()
                    .header("User-Agent", userAgent)
                    .header("Accept-Language", acceptLanguage)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                val html = client.newCall(req).execute().use { resp ->
                    resp.body?.string() ?: throw IOException("لا HTML من $discoverUrl")
                }

                val pathRegex = Regex("""src\s*=\s*"(/[^/]+/[^?]+)\?.*"""")
                val fullRegex = Regex("""scriptElement\.src\s*=\s*"(.*?)"""")

                val pathMatch = pathRegex.find(html)
                val fullMatch = fullRegex.find(html)

                if (pathMatch == null || fullMatch == null) {
                    DebugBus.emit(DebugEntry(
                        source = DebugSource.HYPER,
                        title = "⚠️ Hyper: لا يوجد سكريبت تحدٍّ في $discoverUrl",
                        body = "html.length=${html.length}c\nfirst500=${html.take(500)}"
                    ))
                    return@runBlocking null
                }

                val rawPath = pathMatch.groupValues[1]
                val fullPath = fullMatch.groupValues[1]
                val pageSensorPath = "$rawPath?d=$pageHost"
                val pageScriptUrl = "https://$pageHost$fullPath"

                DebugBus.emit(DebugEntry(
                    source = DebugSource.HYPER,
                    title = "✅ Hyper: مسار التحدي ($pageHost)",
                    body = "sensorPath=$pageSensorPath\nscriptUrl=$pageScriptUrl"
                ))

                val script = client.newCall(
                    Request.Builder()
                        .url(pageScriptUrl)
                        .get()
                        .header("User-Agent", userAgent)
                        .build()
                ).execute().use { it.body?.string() ?: throw IOException("script فارغ") }

                val egressIp = client.newCall(
                    Request.Builder()
                        .url(IP_FETCH_URL)
                        .get()
                        .header("x-api-key", apiKey)
                        .build()
                ).execute().use {
                    JSONObject(it.body?.string() ?: "{}").getString("ip")
                }

                val body = JSONObject().apply {
                    put("userAgent", userAgent)
                    put("pageUrl", pageUrl)
                    put("scriptUrl", pageScriptUrl)
                    put("script", script)
                    put("ip", egressIp)
                    put("acceptLanguage", acceptLanguage)
                }.toString()

                val payloadResp = client.newCall(
                    Request.Builder()
                        .url("$HYPER_BASE/reese84")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .header("x-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .build()
                ).execute().use { resp ->
                    val b = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw IOException("Hyper ${resp.code}: ${b.take(500)}")
                    JSONObject(b).getString("payload")
                }

                val tokenUrl = "https://$pageHost$pageSensorPath"
                val tokenResp = client.newCall(
                    Request.Builder()
                        .url(tokenUrl)
                        .post(payloadResp.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .header("User-Agent", userAgent)
                        .header("Accept", "*/*")
                        .build()
                ).execute().use { resp ->
                    val b = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw IOException("Submit sensor ${resp.code}: ${b.take(500)}")
                    JSONObject(b)
                }

                val token = tokenResp.getString("token")
                val domain = tokenResp.optString("cookieDomain", pageHost)

                DebugBus.emit(DebugEntry(
                    source = DebugSource.HYPER,
                    title = "✅ Hyper: reese84 لـ $pageHost",
                    body = "token.length=${token.length}\ncookieDomain=$domain"
                ))

                token
            }
        } catch (e: Exception) {
            DebugBus.emit(DebugEntry(
                source = DebugSource.HYPER,
                title = "❌ Hyper: فشل حل تحدٍّ $pageUrl",
                body = "${e.javaClass.simpleName}: ${e.message}"
            ))
            null
        }
    }

    private fun solveOrRenew() {
        if (sensorPath == null || scriptUrl == null) discoverPaths()

        val script = fetchScript(scriptUrl!!)
        DebugBus.emit(DebugEntry(
            source = DebugSource.HYPER,
            title = "📜 Hyper: script التحدي",
            body = "length=${script.length}c\nurl=$scriptUrl"
        ))

        val egressIp = fetchEgressIp()
        DebugBus.emit(DebugEntry(
            source = DebugSource.HYPER,
            title = "🌐 Hyper: egress IP",
            body = "ip=$egressIp"
        ))

        val sensorPayload = generateSensor(script, scriptUrl!!, SAUDIA_URL, egressIp)
        val result = submitSensor(sensorPath!!, sensorPayload)

        currentToken = result.token
        expiryMs = System.currentTimeMillis() + result.renewInSec * 1000L
        cookieDomain = result.cookieDomain

        prefs.edit()
            .putString(KEY_TOKEN, result.token)
            .putLong(KEY_EXPIRY, expiryMs)
            .putString(KEY_COOKIE_DOMAIN, cookieDomain)
            .apply()

        DebugBus.emit(DebugEntry(
            source = DebugSource.HYPER,
            title = "✅ Hyper: reese84 جاهز",
            body = "token.prefix=${result.token.take(40)}...\nlength=${result.token.length}\nrenewInSec=${result.renewInSec}\ncookieDomain=${result.cookieDomain}"
        ))
    }

    private fun discoverPaths() {
        val req = Request.Builder()
            .url(SAUDIA_URL)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept-Language", acceptLanguage)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: throw IOException("لا يوجد HTML")
            DebugBus.emit(DebugEntry(
                source = DebugSource.HYPER,
                method = "GET",
                url = SAUDIA_URL,
                statusCode = resp.code,
                title = "🔍 Hyper: GET /ar-SA → ${resp.code}",
                body = "html.length=${html.length}c"
            ))

            val pathRegex = Regex("""src\s*=\s*"(/[^/]+/[^?]+)\?.*"""")
            val fullRegex = Regex("""scriptElement\.src\s*=\s*"(.*?)"""")

            val pathMatch = pathRegex.find(html)
            val fullMatch = fullRegex.find(html)

            if (pathMatch == null || fullMatch == null) {
                throw IOException("❌ لم يتم العثور على مسار سكريبت التحدي")
            }

            val rawPath = pathMatch.groupValues[1]
            val fullPath = fullMatch.groupValues[1]

            sensorPath = "$rawPath?d=$SAUDIA_HOST"
            scriptUrl = "https://$SAUDIA_HOST$fullPath"
        }
    }

    private fun fetchScript(url: String): String {
        val req = Request.Builder().url(url).get().header("User-Agent", userAgent).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("فشل جلب script: ${resp.code}")
            return resp.body?.string() ?: throw IOException("script فارغ")
        }
    }

    private fun fetchEgressIp(): String {
        val req = Request.Builder().url(IP_FETCH_URL).get().header("x-api-key", apiKey).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("رد فارغ")
            if (!resp.isSuccessful) throw IOException("IP fetch ${resp.code}: $body")
            return JSONObject(body).getString("ip")
        }
    }

    private fun generateSensor(script: String, scriptUrl: String, pageUrl: String, ip: String): String {
        val body = JSONObject().apply {
            put("userAgent", userAgent)
            put("pageUrl", pageUrl)
            put("scriptUrl", scriptUrl)
            put("script", script)
            put("ip", ip)
            put("acceptLanguage", acceptLanguage)
        }.toString()

        val req = Request.Builder()
            .url("$HYPER_BASE/reese84")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .build()

        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string() ?: throw IOException("Hyper فارغ")
            if (!resp.isSuccessful) throw IOException("Hyper ${resp.code}: ${respBody.take(500)}")
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
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("رد فارغ")
            if (!resp.isSuccessful) throw IOException("submit ${resp.code}")
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
