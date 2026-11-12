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

    companion object {
        const val TAG = "CurlDiag"
    }

    /**
     * محاولة العثور على الملف التنفيذي الصحيح.
     * ترتيب المحاولات:
     * 1. libcurl-impersonate-chrome.so (من nativeLibraryDir)
     * 2. libcurl-impersonate.so (من nativeLibraryDir)
     * 3. نسخ أي منهما إلى filesDir وتشغيله من هناك
     */
    private suspend fun findWorkingBinary(): Triple<File, Boolean, String>? = withContext(Dispatchers.IO) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val candidates = listOf(
            "libcurl-impersonate-chrome.so",
            "libcurl-impersonate.so"
        )

        for (name in candidates) {
            // 1. جرّب مباشرة من nativeLibraryDir
            val directFile = File(nativeDir, name)
            if (directFile.exists()) {
                val result = testBinary(directFile, useLinker = false)
                if (result != null) {
                    return@withContext Triple(directFile, false, "direct: $name")
                }
                // جرّب linker64
                val result2 = testBinary(directFile, useLinker = true)
                if (result2 != null) {
                    return@withContext Triple(directFile, true, "linker64: $name")
                }
            }

            // 2. انسخ إلى filesDir وجرّب
            val copiedFile = File(context.filesDir, name)
            if (!copiedFile.exists()) {
                if (directFile.exists()) {
                    try {
                        directFile.inputStream().use { input ->
                            copiedFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        copiedFile.setExecutable(true, false)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "فشل نسخ $name: ${e.message}")
                        continue
                    }
                }
            }

            if (copiedFile.exists() && copiedFile.canExecute()) {
                val result = testBinary(copiedFile, useLinker = false)
                if (result != null) {
                    return@withContext Triple(copiedFile, false, "copied: $name")
                }
                val result2 = testBinary(copiedFile, useLinker = true)
                if (result2 != null) {
                    return@withContext Triple(copiedFile, true, "copied+linker64: $name")
                }
            }
        }
        null
    }

    /**
     * اختبار سريع للملف: تشغيل --version
     * @return version string إذا نجح، null إذا فشل
     */
    private fun testBinary(binary: File, useLinker: Boolean): String? {
        return try {
            val cmd = if (useLinker) {
                listOf("/system/bin/linker64", binary.absolutePath, "--version")
            } else {
                listOf(binary.absolutePath, "--version")
            }

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            val process = pb.start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.contains("curl", ignoreCase = true)) {
                android.util.Log.d(TAG, "✅ نجح الاختبار ($useLinker): $output")
                output.lines().firstOrNull { it.contains("curl", ignoreCase = true) }
            } else {
                android.util.Log.e(TAG, "❌ فشل الاختبار ($useLinker): exit=$exitCode, output=$output")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ استثناء في testBinary: ${e.message}")
            null
        }
    }

    /**
     * نسخة الكاش بعد أول اكتشاف ناجح
     */
    @Volatile
    private var cachedBinary: File? = null
    @Volatile
    private var cachedUseLinker: Boolean = false

    suspend fun execute(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): CurlResponse = withContext(Dispatchers.IO) {
        // اكتشف الملف الصحيح مرة واحدة
        if (cachedBinary == null) {
            val found = findWorkingBinary() ?: throw IOException(
                "لم يتم العثور على ملف curl-impersonate قابل للتشغيل. " +
                "تحقق من وجود libcurl-impersonate-chrome.so أو libcurl-impersonate.so في jniLibs، " +
                "وتأكد من أن الجهاز arm64-v8a."
            )
            cachedBinary = found.first
            cachedUseLinker = found.second
            android.util.Log.d(TAG, "🎯 الملف المكتشف: ${found.third}")
        }

        val binary = cachedBinary!!
        val useLinker = cachedUseLinker

        val cmd = mutableListOf<String>()
        if (useLinker) {
            cmd.add("/system/bin/linker64")
        }
        cmd.add(binary.absolutePath)
        cmd.addAll(listOf(
            "--impersonate", "chrome120",
            "--silent", "--show-error", "--include", "--max-time", "30",
            "-X", method
        ))

        headers.forEach { (k, v) ->
            cmd.add("-H")
            cmd.add("$k: $v")
        }

        if (body != null) {
            cmd.add("--data-binary")
            cmd.add(body)
        }

        cmd.add(url)

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        val process = pb.start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IOException("curl-impersonate failed (exit=$exitCode): ${output.take(500)}")
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
