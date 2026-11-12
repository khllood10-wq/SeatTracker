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
            timeout = 15,
            httpVersion = "h1",
            withoutHTTP3 = true
        )
    )

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpCloakResponse = withContext(Dispatchers.IO) {
        newSession().use { session ->
            val response = session.get(url, headers)
            HttpCloakResponse(response.statusCode, response.text)
        }
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: Body? = null
    ): HttpCloakResponse = withContext(Dispatchers.IO) {
        newSession().use { session ->
            // 👇 الترتيب الصحيح: url, body, headers
            val response = session.post(
                url = url,
                body = body,
                headers = headers
            )
            HttpCloakResponse(response.statusCode, response.text)
        }
    }
}

data class HttpCloakResponse(
    val statusCode: Int,
    val text: String
)
