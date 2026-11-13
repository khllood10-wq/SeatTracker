package com.example.saudiaseattracker

import android.util.Log
import io.github.sardanioss.httpcloak.Presets
import io.github.sardanioss.httpcloak.Session
import io.github.sardanioss.httpcloak.SessionConfig
import io.github.sardanioss.httpcloak.Body
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class HttpCloakClient {

    companion object {
        private const val TAG = "HttpCloakClient"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/155.0.0.0 Safari/537.36"
    }

    private var session: Session? = null
    private var sessionCreatedAt: Long = 0L

    @Synchronized
    private fun getSession(): Session {
        val now = System.currentTimeMillis()
        val existing = session
        if (existing == null || (now - sessionCreatedAt) > 30 * 60_000L) {
            try { existing?.close() } catch (_: Exception) {}
            val newS = Session(
                SessionConfig(
                    preset = Presets.CHROME_LATEST_WINDOWS,
                    timeout = 15,                  // مهلة داخلية أقصر
                    httpVersion = "auto",          
                    retry = 0,                     
                    withoutClientHints = true      
                )
            )
            session = newS
            sessionCreatedAt = now
            DebugBus.emit(DebugEntry(
                source = DebugSource.HTTPCLOAK,
                title = "🆕 Session جديدة (Windows 155)",
                body = "preset=CHROME_LATEST_WINDOWS\ntimeout=15s\nhttpVersion=auto\nUA=$UA"
            ))
            return newS
        }
        return existing
    }

    fun resetSession() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        sessionCreatedAt = 0
    }

    // ✅ دالة جديدة: تهيئة الجلسة (Warmup)
    suspend fun warmup(url: String, headers: Map<String, String>): HttpCloakResponse =
        withContext(Dispatchers.IO) {
            withTimeout(20_000L) {
                val sess = getSession()
                try {
                    // إرسال طلب خفيف لتهيئة الاتصال وتجنب التعليق
                    val resp = sess.get(url, headers)
                    HttpCloakResponse(resp.statusCode, resp.text, "")
                } catch (e: Exception) {
                    throw e
                }
            }
        }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpCloakResponse =
        withContext(Dispatchers.IO) {
            withTimeout(20_000L) {
                doRequest("GET", url, headers, null)
            }
        }

    suspend fun post(url: String, headers: Map<String, String> = emptyMap(), body: Body? = null): HttpCloakResponse =
        withContext(Dispatchers.IO) {
            withTimeout(20_000L) {
                doRequest("POST", url, headers, body)
            }
        }

    private suspend fun doRequest(
        method: String,
        url: String,
        headersIn: Map<String, String>,
        body: Body?
    ): HttpCloakResponse {
        val sess = getSession()
        val t0 = System.currentTimeMillis()

        try {
            val resp = if (method == "GET") sess.get(url, headersIn) else sess.post(url, body, headersIn)
            val dt = System.currentTimeMillis() - t0
            
            DebugBus.emit(DebugEntry(
                source = DebugSource.HTTPCLOAK,
                method = method,
                url = url,
                statusCode = resp.statusCode,
                title = "$method ${url.takeLast(55)} → ${resp.statusCode} (${dt}ms, ${resp.text.length}c)",
                headers = "── REQUEST ──\n" + headersIn.entries.joinToString("\n") { "  ${it.key}: ${it.value.take(100)}" },
                body = resp.text.take(5000)
            ))
            return HttpCloakResponse(resp.statusCode, resp.text, "")
        } catch (e: Exception) {
            val dt = System.currentTimeMillis() - t0
            DebugBus.emit(DebugEntry(
                source = DebugSource.HTTPCLOAK,
                method = method,
                url = url,
                title = "❌ $method ${url.takeLast(55)} → ${e.javaClass.simpleName} (${dt}ms)",
                body = "── EXCEPTION ──\n${e.javaClass.simpleName}: ${e.message}"
            ))
            throw e
        }
    }
}

data class HttpCloakResponse(val statusCode: Int, val text: String, val headers: String = "")
