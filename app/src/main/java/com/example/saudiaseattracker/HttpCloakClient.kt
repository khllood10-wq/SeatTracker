package com.example.saudiaseattracker

import android.util.Log
import io.github.sardanioss.httpcloak.Presets
import io.github.sardanioss.httpcloak.Session
import io.github.sardanioss.httpcloak.SessionConfig
import io.github.sardanioss.httpcloak.Body
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpCloakClient {

    private fun newSession(): Session {
        return Session(
            SessionConfig(
                preset = Presets.CHROME_LATEST_ANDROID,
                timeout = 30,              
                httpVersion = "h2"         // إجبار HTTP/2
                // تم حذف disableHTTP3 لتجنب خطأ المترجم
            )
        )
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpCloakResponse = withContext(Dispatchers.IO) {
        Log.d("HttpCloakClient", "🚀 بدء طلب GET إلى: $url")
        newSession().use { session ->
            try {
                val response = session.get(url, headers)
                Log.d("HttpCloakClient", "✅ استجابة GET: HTTP ${response.statusCode}، طول: ${response.text.length}")
                HttpCloakResponse(response.statusCode, response.text)
            } catch (e: Exception) {
                // 👇 طباعة الخطأ الداخلي بالكامل (Stack Trace)
                Log.e("HttpCloakClient", "❌ فشل GET: ${e.javaClass.simpleName} - ${e.message}", e)
                throw e
            }
        }
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: Body? = null
    ): HttpCloakResponse = withContext(Dispatchers.IO) {
        Log.d("HttpCloakClient", "🚀 بدء طلب POST إلى: $url")
        newSession().use { session ->
            try {
                val response = session.post(
                    url = url,
                    body = body,
                    headers = headers
                )
                Log.d("HttpCloakClient", "✅ استجابة POST: HTTP ${response.statusCode}، طول: ${response.text.length}")
                HttpCloakResponse(response.statusCode, response.text)
            } catch (e: Exception) {
                Log.e("HttpCloakClient", "❌ فشل POST: ${e.javaClass.simpleName} - ${e.message}", e)
                throw e
            }
        }
    }
}

data class HttpCloakResponse(
    val statusCode: Int,
    val text: String
)
