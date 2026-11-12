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
        // 👇 تفعيل وضع التصحيح في المكتبة الأصلية
        // ملاحظة: إذا لم يتعرف المترجم على هذا السطر، احذفه وسنبحث عن بديل
        try {
            val loggerClass = Class.forName("io.github.sardanioss.httpcloak.HttpCloakLogger")
            val setLogLevel = loggerClass.getMethod("setLogLevel", String::class.java)
            setLogLevel.invoke(null, "DEBUG")
        } catch (e: Exception) {
            Log.w("HttpCloak", "لا يمكن تفعيل وضع التصحيح تلقائياً: ${e.message}")
        }

        return Session(
            SessionConfig(
                preset = Presets.CHROME_LATEST_ANDROID,
                timeout = 30,              
                httpVersion = "h2",        
                disableHTTP3 = true,       
                retry = 3                  
            )
        )
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpCloakResponse = withContext(Dispatchers.IO) {
        Log.d("HttpCloak", "🚀 بدء طلب GET إلى: $url")
        newSession().use { session ->
            try {
                val response = session.get(url, headers)
                Log.d("HttpCloak", "✅ استجابة GET: HTTP ${response.statusCode}، طول: ${response.text.length}")
                HttpCloakResponse(response.statusCode, response.text)
            } catch (e: Exception) {
                // 👇 طباعة الخطأ الداخلي بالكامل (Stack Trace)
                Log.e("HttpCloak", "❌ فشل GET: ${e.javaClass.simpleName} - ${e.message}", e)
                throw e
            }
        }
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: Body? = null
    ): HttpCloakResponse = withContext(Dispatchers.IO) {
        Log.d("HttpCloak", "🚀 بدء طلب POST إلى: $url")
        newSession().use { session ->
            try {
                val response = session.post(
                    url = url,
                    body = body,
                    headers = headers
                )
                Log.d("HttpCloak", "✅ استجابة POST: HTTP ${response.statusCode}، طول: ${response.text.length}")
                HttpCloakResponse(response.statusCode, response.text)
            } catch (e: Exception) {
                Log.e("HttpCloak", "❌ فشل POST: ${e.javaClass.simpleName} - ${e.message}", e)
                throw e
            }
        }
    }
}

data class HttpCloakResponse(
    val statusCode: Int,
    val text: String
)
