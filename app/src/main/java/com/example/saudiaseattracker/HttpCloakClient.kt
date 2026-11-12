package com.example.saudiaseattracker

import io.github.sardanioss.httpcloak.Session
import io.github.sardanioss.httpcloak.Presets
import io.github.sardanioss.httpcloak.Body
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpCloakClient {
    private val session = Session(Presets.CHROME_LATEST_ANDROID)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpCloakResponse = withContext(Dispatchers.IO) {
        val response = session.get(url, headers)
        HttpCloakResponse(response.statusCode, response.text)
    }

    // 👇 تم التعديل: نوع body أصبح Body?
    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: Body? = null
    ): HttpCloakResponse = withContext(Dispatchers.IO) {
        val response = session.post(url, headers, body)
        HttpCloakResponse(response.statusCode, response.text)
    }

    fun close() {
        try { session.close() } catch (e: Exception) { }
    }
}

data class HttpCloakResponse(
    val statusCode: Int,
    val text: String
)
