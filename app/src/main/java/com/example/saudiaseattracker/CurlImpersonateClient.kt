package com.example.saudiaseattracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class CurlResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String>
)

class CurlImpersonateClient(private val context: Context) {

    // الحصول على مسار المكتبة التنفيذية
    private val curlBinary: File by lazy {
        File(context.applicationInfo.nativeLibraryDir, "libcurl-impersonate.so")
    }

    suspend fun execute(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): CurlResponse = withContext(Dispatchers.IO) {
        if (!curlBinary.exists()) {
            throw IOException("curl-impersonate binary not found at ${curlBinary.absolutePath}")
        }

        val cmd = mutableListOf(
            curlBinary.absolutePath,
            "--impersonate", "chrome131_android", // استخدام بروفايل Chrome 131 لأندرويد
            "--silent", "--show-error", "--include", "--max-time", "30",
            "-X", method
        )

        headers.forEach { (k, v) ->
            cmd.add("-H")
            cmd.add("$k: $v")
        }

        if (body != null) {
            cmd.add("--data-binary")
            cmd.add(body)
        }

        cmd.add(url)

        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IOException("curl-impersonate failed with code $exitCode: $output")
        }

        val headerEnd = output.indexOf("\r\n\r\n")
        val (headerPart, bodyPart) = if (headerEnd > 0) {
            output.substring(0, headerEnd) to output.substring(headerEnd + 4)
        } else {
            output to ""
        }

        val lines = headerPart.lines()
        val statusLine = lines.firstOrNull() ?: ""
        val statusCode = Regex("HTTP/[\\d.]+ (\\d+)").find(statusLine)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val headersMap = lines.drop(1)
            .filter { it.contains(":") }
            .associate { line ->
                val idx = line.indexOf(":")
                line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
            }

        CurlResponse(statusCode, bodyPart, headersMap)
    }
}
