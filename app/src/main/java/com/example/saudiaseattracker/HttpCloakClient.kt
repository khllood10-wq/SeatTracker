package com.example.saudiaseattracker

import io.github.sardanioss.httpcloak.Presets
import io.github.sardanioss.httpcloak.Session
import io.github.sardanioss.httpcloak.SessionConfig
import io.github.sardanioss.httpcloak.Body
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpCloakClient {

    private fun newSession(): Session = Session(
        SessionConfig(
            preset = Presets.CHROME_LATEST_ANDROID,
            timeout = 20,          
            httpVersion = "h2"     
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

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: Body? = null
    ): HttpCloakResponse = withContext(Dispatchers.IO) {
        val session = newSession()
        try {
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
