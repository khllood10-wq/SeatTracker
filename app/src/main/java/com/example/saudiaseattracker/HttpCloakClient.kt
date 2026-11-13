package com.example.saudiaseattracker

import android.util.Log
import io.github.sardanioss.httpcloak.Presets
import io.github.sardanioss.httpcloak.Session
import io.github.sardanioss.httpcloak.SessionConfig
import io.github.sardanioss.httpcloak.Body
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpCloakClient {

    companion object {
        private const val TAG = "HttpCloakClient"

        // ⚠️ Windows Chrome/131 — يطابق HyperSessionManager.DEFAULT_UA
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
    }

    // ═══════ جلسة واحدة دائمة — تحل مشكلة فقدان الكوكيز ═══════
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
                    // ✅ Preset صحيح — Windows (لأن Hyper يوقّع Windows)
                    preset = Presets.CHROME_LATEST_WINDOWS,
                    timeout = 30,
                    httpVersion = "h2"
                )
            )
            session = newS
            sessionCreatedAt = now
            DebugBus.emit(DebugEntry(
                source = DebugSource.HTTPCLOAK,
                title = "🆕 Session جديدة (Windows)",
                body = "preset=CHROME_LATEST_WINDOWS\ntimeout=30s\nhttpVersion=h2\nUA=$UA"
            ))
            return newS
        }
        return existing
    }

    fun resetSession() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        sessionCreatedAt = 0
        DebugBus.emit(DebugEntry(
            source = DebugSource.HTTPCLOAK,
            title = "🔄 Session أُعيد ضبطها",
            body = "بداية نظيفة"
        ))
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpCloakResponse = withContext(Dispatchers.IO) {
        doRequest("GET", url, headers, null)
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: Body? = null
    ): HttpCloakResponse = withContext(Dispatchers.IO) {
        doRequest("POST", url, headers, body)
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
            if (body != null) appendLine("  type=${body::class.simpleName}")
            else appendLine("  (null)")
        }

        try {
            val resp = if (method == "GET") {
                sess.get(url, headersIn)
            } else {
                sess.post(url, body, headersIn)
            }
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

            Log.d(TAG, "$method $url → ${resp.statusCode} (${respBodyStr.length}c)")

            return HttpCloakResponse(
                statusCode = resp.statusCode,
                text = respBodyStr,
                headers = ""
            )
        } catch (e: Exception) {
            val dt = System.currentTimeMillis() - t0
            DebugBus.emit(DebugEntry(
                source = DebugSource.HTTPCLOAK,
                method = method,
                url = url,
                title = "❌ $method ${url.takeLast(55)} → exception (${dt}ms)",
                body = buildString {
                    appendLine(reqSnapshot)
                    appendLine("── EXCEPTION ──")
                    appendLine("${e.javaClass.simpleName}: ${e.message}")
                    appendLine(e.stackTraceToString().take(3000))
                }
            ))
            throw e
        }
    }
}

data class HttpCloakResponse(
    val statusCode: Int,
    val text: String,
    val headers: String = ""
)
