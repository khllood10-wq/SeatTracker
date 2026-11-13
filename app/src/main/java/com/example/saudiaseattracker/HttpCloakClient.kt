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

        // ⚠️ UA واحد متسق مع preset — Android Chrome 131
        // (Hyper سيستخدم نفس UA حتى reese84 يُوقّع لبصمة أندرويد)
        const val UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
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
                    preset = Presets.CHROME_LATEST_ANDROID,  // ⚠️ إن لم تُوجد، استبدلها بـ CHROME_LATEST
                    timeout = 30,                             // ← 30s بدل 10s
                    httpVersion = "h2"
                )
            )
            session = newS
            sessionCreatedAt = now
            DebugBus.emit(DebugEntry(
                source = DebugSource.HTTPCLOAK,
                title = "🆕 Session جديدة",
                body = "preset=CHROME_LATEST_ANDROID\ntimeout=30s\nhttpVersion=h2\nUA=$UA"
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

    private fun doRequest(
        method: String,
        url: String,
        headersIn: Map<String, String>,
        body: Body?
    ): HttpCloakResponse {
        val sess = getSession()
        val t0 = System.currentTimeMillis()

        // لقطة كاملة للطلب — قبل إرساله
        val reqSnapshot = buildString {
            appendLine("═══ $method $url ═══")
            appendLine("── headers (${headersIn.size}) ──")
            headersIn.forEach { (k, v) ->
                appendLine("  $k: ${v.take(350)}")
            }
            if (body != null) {
                appendLine("── body ──")
                appendLine("  type=${body::class.simpleName}")
            } else {
                appendLine("── body ──")
                appendLine("  (null)")
            }
        }

        try {
            val resp = if (method == "GET") {
                sess.get(url, headersIn)
            } else {
                sess.post(url, body, headersIn)
            }
            val dt = System.currentTimeMillis() - t0
            val respBodyStr = resp.text

            // محاولة استخراج headers من الرد (قد لا تكون مدعومة)
            val respHeadersStr = try {
                @Suppress("UNCHECKED_CAST")
                val h = (resp as? Any)?.let { r ->
                    val m = r.javaClass.methods.find { it.name == "getHeaders" || it.name == "headers" }
                    m?.invoke(r)?.toString() ?: "(no headers accessor)"
                }
                h ?: "(no headers)"
            } catch (e: Exception) { "(headers unavailable: ${e.message})" }

            val setCookieSnippet = try {
                val cookies = Regex("(?i)set-cookie:\\s*([^\\r\\n]+)").findAll(respHeadersStr)
                    .map { it.groupValues[1].take(200) }.toList()
                if (cookies.isEmpty()) "(none detected in headers dump)"
                else cookies.joinToString("\n  ")
            } catch (e: Exception) { "(parse fail)" }

            DebugBus.emit(DebugEntry(
                source = DebugSource.HTTPCLOAK,
                method = method,
                url = url,
                statusCode = resp.statusCode,
                title = "$method ${url.takeLast(55)} → ${resp.statusCode} (${dt}ms, ${respBodyStr.length}c)",
                headers = buildString {
                    appendLine("── REQUEST ──")
                    appendLine(reqSnapshot)
                    appendLine("── RESPONSE HEADERS ──")
                    appendLine(respHeadersStr.take(3000))
                    appendLine("── SET-COOKIE ──")
                    appendLine(setCookieSnippet)
                },
                body = respBodyStr.take(15000)
            ))

            Log.d(TAG, "$method $url → ${resp.statusCode} (${respBodyStr.length}c)")

            return HttpCloakResponse(
                statusCode = resp.statusCode,
                text = respBodyStr,
                headers = respHeadersStr
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
