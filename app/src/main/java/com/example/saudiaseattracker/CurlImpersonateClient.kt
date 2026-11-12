package com.example.saudiaseattracker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

data class CurlResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String>
)

class CurlImpersonateClient(private val context: Context) {

    companion object {
        const val TAG = "CurlDiag"
    }

    @Volatile
    private var cachedBinary: File? = null

    @Volatile
    private var cachedUseLinker: Boolean = false

    /**
     * البحث الشامل عن الملف التنفيذي:
     * 1. nativeLibraryDir مباشرة
     * 2. نسخ من nativeLibraryDir إلى filesDir
     * 3. استخراج من APK إلى filesDir (خطة احتياطية)
     */
    private suspend fun findAndPrepareBinary(): File? = withContext(Dispatchers.IO) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        Log.e(TAG, "═══ DIAGNOSTIC START ═══")
        Log.e(TAG, "nativeLibraryDir: ${nativeDir.absolutePath}")
        Log.e(TAG, "nativeLibraryDir exists: ${nativeDir.exists()}")
        Log.e(TAG, "nativeLibraryDir files: ${nativeDir.list()?.joinToString() ?: "EMPTY"}")

        val filesDir = context.filesDir
        Log.e(TAG, "filesDir: ${filesDir.absolutePath}")
        Log.e(TAG, "filesDir files: ${filesDir.list()?.joinToString() ?: "EMPTY"}")

        val candidates = listOf(
            "libcurl-impersonate-chrome.so",
            "libcurl-impersonate.so"
        )

        // ═══ المحاولة 1: النسخ من nativeLibraryDir ═══
        for (name in candidates) {
            val direct = File(nativeDir, name)
            Log.e(TAG, "── Checking: ${direct.absolutePath}")
            Log.e(TAG, "   exists=${direct.exists()}, canRead=${direct.canRead()}, length=${if (direct.exists()) direct.length() else 0}")

            if (direct.exists() && direct.canRead()) {
                val copied = File(filesDir, name)
                try {
                    direct.inputStream().use { input ->
                        copied.outputStream().use { output -> input.copyTo(output) }
                    }
                    copied.setExecutable(true, false)
                    Log.e(TAG, "   ✅ Copied to filesDir: ${copied.absolutePath}")

                    if (testBinary(copied, useLinker = false)) {
                        cachedUseLinker = false
                        return@withContext copied
                    }
                    if (testBinary(copied, useLinker = true)) {
                        cachedUseLinker = true
                        return@withContext copied
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "   ❌ Copy failed: ${e.message}")
                }
            }
        }

        // ═══ المحاولة 2: استخراج من APK ═══
        val apkPath = context.applicationInfo.sourceDir
        Log.e(TAG, "── Fallback: extract from APK: $apkPath")
        Log.e(TAG, "   APK exists: ${File(apkPath).exists()}, size: ${File(apkPath).length()}")

        try {
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries().toList()
                    .filter { it.name.contains("curl-impersonate", ignoreCase = true) }
                    .map { it.name }
                Log.e(TAG, "   APK entries matching 'curl-impersonate': $entries")

                for (name in candidates) {
                    val possiblePaths = listOf(
                        "lib/arm64-v8a/$name",
                        "lib/arm64/$name",
                        name
                    )
                    for (path in possiblePaths) {
                        val entry = zip.getEntry(path)
                        if (entry != null) {
                            val out = File(filesDir, name)
                            zip.getInputStream(entry).use { input ->
                                out.outputStream().use { output -> input.copyTo(output) }
                            }
                            out.setExecutable(true, false)
                            Log.e(TAG, "   ✅ Extracted from APK: $path → ${out.absolutePath}")

                            if (testBinary(out, useLinker = false)) {
                                cachedUseLinker = false
                                return@withContext out
                            }
                            if (testBinary(out, useLinker = true)) {
                                cachedUseLinker = true
                                return@withContext out
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "   ❌ APK extraction failed: ${e.message}")
        }

        Log.e(TAG, "═══ DIAGNOSTIC END: NO WORKING BINARY ═══")
        return@withContext null
    }

    private fun testBinary(binary: File, useLinker: Boolean): Boolean {
        return try {
            val cmd = if (useLinker) {
                listOf("/system/bin/linker64", binary.absolutePath, "--version")
            } else {
                listOf(binary.absolutePath, "--version")
            }
            Log.e(TAG, "   Testing (linker=$useLinker): ${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            Log.e(TAG, "   Result: exit=$exitCode, output=${output.take(300)}")
            exitCode == 0 && output.contains("curl", ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "   Exception in testBinary: ${e.message}")
            false
        }
    }

    suspend fun execute(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): CurlResponse = withContext(Dispatchers.IO) {
        val binary = cachedBinary ?: findAndPrepareBinary() ?: throw IOException(
            "لم يتم العثور على ملف curl-impersonate. راجع سجل CurlDiag في Logcat."
        ).also { cachedBinary = null }
        cachedBinary = binary
        val useLinker = cachedUseLinker

        val cmd = mutableListOf<String>()
        if (useLinker) cmd.add("/system/bin/linker64")
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
            throw IOException("curl failed (exit=$exitCode): ${output.take(500)}")
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
