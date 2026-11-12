package com.example.saudiaseattracker

import android.content.Context
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

class CurlImpersonateClient(
    private val context: Context,
    private val addLog: (String) -> Unit
) {

    @Volatile
    private var cachedBinary: File? = null

    @Volatile
    private var cachedUseLinker: Boolean = false

    private suspend fun findAndPrepareBinary(): File? = withContext(Dispatchers.IO) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        addLog("═══ CurlDiag START ═══")
        addLog("nativeLibraryDir: ${nativeDir.absolutePath}")
        addLog("exists: ${nativeDir.exists()}")
        addLog("files: ${nativeDir.list()?.joinToString() ?: "EMPTY"}")

        val filesDir = context.filesDir
        addLog("filesDir: ${filesDir.absolutePath}")
        addLog("filesDir contents: ${filesDir.list()?.joinToString() ?: "EMPTY"}")

        val candidates = listOf(
            "libcurl-impersonate-chrome.so",
            "libcurl-impersonate.so"
        )

        for (name in candidates) {
            val direct = File(nativeDir, name)
            addLog("── Checking: $name")
            addLog("   exists=${direct.exists()}, length=${if (direct.exists()) direct.length() else 0}")

            if (direct.exists() && direct.canRead()) {
                val copied = File(filesDir, name)
                try {
                    direct.inputStream().use { input ->
                        copied.outputStream().use { output -> input.copyTo(output) }
                    }
                    copied.setExecutable(true, false)
                    addLog("   ✅ Copied: ${copied.length()} bytes")

                    if (testBinary(copied, useLinker = false)) {
                        cachedUseLinker = false
                        return@withContext copied
                    }
                    if (testBinary(copied, useLinker = true)) {
                        cachedUseLinker = true
                        return@withContext copied
                    }
                } catch (e: Exception) {
                    addLog("   ❌ Copy failed: ${e.message}")
                }
            }
        }

        val apkPath = context.applicationInfo.sourceDir
        addLog("── APK fallback: $apkPath")
        addLog("   APK exists: ${File(apkPath).exists()}")

        try {
            ZipFile(apkPath).use { zip ->
                val matching = zip.entries().toList()
                    .filter { it.name.contains("curl-impersonate", ignoreCase = true) }
                    .map { it.name }
                addLog("   APK entries with 'curl-impersonate': $matching")

                for (name in candidates) {
                    for (path in listOf("lib/arm64-v8a/$name", "lib/arm64/$name")) {
                        val entry = zip.getEntry(path)
                        if (entry != null) {
                            val out = File(filesDir, name)
                            zip.getInputStream(entry).use { input ->
                                out.outputStream().use { output -> input.copyTo(output) }
                            }
                            out.setExecutable(true, false)
                            addLog("   ✅ Extracted: $path → ${out.length()} bytes")

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
            addLog("   ❌ APK extraction failed: ${e.message}")
        }

        addLog("═══ NO WORKING BINARY ═══")
        return@withContext null
    }

    private fun testBinary(binary: File, useLinker: Boolean): Boolean {
        return try {
            val cmd = if (useLinker) {
                listOf("/system/bin/linker64", binary.absolutePath, "--version")
            } else {
                listOf(binary.absolutePath, "--version")
            }
            addLog("   Testing (linker=$useLinker)...")

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            addLog("   exit=$exitCode, output=${output.take(100)}")
            exitCode == 0 && output.contains("curl", ignoreCase = true)
        } catch (e: Exception) {
            addLog("   ❌ Exception: ${e.message}")
            false
        }
    }

    suspend fun execute(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): CurlResponse = withContext(Dispatchers.IO) {
        val binary = cachedBinary ?: findAndPrepareBinary()
            ?: throw IOException("لم يتم العثور على curl-impersonate. راجع سجل التطبيق.")

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
            throw IOException("curl failed (exit=$exitCode): ${output.take(300)}")
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
