package com.example.saudiaseattracker

import io.github.sardanioss.httpcloak.Presets
import io.github.sardanioss.httpcloak.Session
import io.github.sardanioss.httpcloak.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpCloakClient {

    private fun newSession(): Session = Session(
        SessionConfig(
            preset = Presets.CHROME_LATEST_ANDROID,
            timeout = 20,          // مهلة 20 ثانية
            httpVersion = "h2"     // إجبار HTTP/2 لتجنب مشاكل QUIC
        )
    )

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpCloakResponse = withContext(Dispatchers.IO) {
        val session = newSession()
        try {
            val response = session.get(url, headers)
            HttpCloakResponse(response.statusCode, response.text)
        } finally {
            try { session.close() } catch (e: Exception) {}
        }
    }

    // 👇 تم التعديل: نوع body أصبح Map<String, String>
    suspend fun post(
        url: String,
        body: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): HttpCloakResponse = withContext(Dispatchers.IO) {
        val session = newSession()
        try {
            // استخدام المعاملات المسماة لتجنب أي التباس في الترتيب
            val response = session.post(
                url = url,
                body = body,
                headers = headers
            )
            HttpCloakResponse(response.statusCode, response.text)
        } finally {
            try { session.close() } catch (e: Exception) {}
        }
    }
}

data class HttpCloakResponse(
    val statusCode: Int,
    val text: String
)
