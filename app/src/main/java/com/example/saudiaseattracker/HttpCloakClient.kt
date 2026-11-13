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
                "Chrome/131.0.0.0 Safari/537.36"
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
                    timeout = 25,              // ← مهلة داخل المكتبة
                    httpVersion = "auto",      // ✅ بدل "h2" الإجباري
                    retry = 1                  // ✅ إعادة محاولة واحدة
                )
            )
            session = newS
            sessionCreatedAt = now
            DebugBus.emit(DebugEntry(
                source = DebugSource.HTTPCLOAK,
                title = "🆕 Session جديدة (Windows)",
                body = "preset=CHROME_LATEST_WINDOWS\ntimeout=25s\nhttpVersion=auto\nretry=1\nUA=$UA"
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

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpCloakResponse =
        withContext(Dispatchers.IO) {
            // ✅ تغليف بـ withTimeout لضمان عدم التعليق
            withTimeout(35_000L) {
                doRequest("GET", url, headers, null)
            }
        }

    suspend fun post(url: String, headers: Map<String, String> = emptyMap(), body: Body? = null): HttpCloakResponse =
        withContext(Dispatchers.IO) {
            withTimeout(35_000L) {
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

        val reqSnapshot = buildString {
            appendLine("═══ $method $url ═══")
            appendLine("── headers (${headersIn.size}) ──")
            headersIn.forEach { (k, v) -> appendLine("  $k: ${v.take(350)}") }
            appendLine("── body ──")
            if (body != null) appendLine("  type=${body::class.simpleName}") else appendLine("  (null)")
        }

        try {
            val resp = if (method == "GET") sess.get(url, headersIn) else sess.post(url, body, headersIn)
            val dt = System.currentTimeMillis() - t0
            val respBodyStr = resp.text

            DebugBus.emit(DebugEntry(
                source = DebugSource.HTTPCLOAK,
                method = method,
                url = url,
                statusCode = resp.statusCode,
                title = "$method ${url.takeLast(55)} → ${resp.statusCode} (${dt}ms, ${respBodyStr.length}c)",
                headers = reqSnapshot,
                body = respBodyStr.take(15000)
            ))
            return HttpCloakResponse(resp.statusCode, respBodyStr, "")
        } catch (e: Exception) {
            val dt = System.currentTimeMillis() - t0
            DebugBus.emit(DebugEntry(
                source = DebugSource.HTTPCLOAK,
                method = method,
                url = url,
                title = "❌ $method ${url.takeLast(55)} → ${e.javaClass.simpleName} (${dt}ms)",
                body = "$reqSnapshot\n── EXCEPTION ──\n${e.javaClass.simpleName}: ${e.message}"
            ))
            throw e
        }
    }
}

data class HttpCloakResponse(val statusCode: Int, val text: String, val headers: String = "")
