package com.example.saudiaseattracker

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

object ShizukuHelper {
    const val PERMISSION_REQUEST_CODE = 1001
    private const val TAG = "ShizukuHelper"

    fun isReady(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) { false }

    fun isPermissionGranted(): Boolean = try {
        isReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    fun requestPermission() {
        try {
            if (isReady() && !isPermissionGranted()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    // دالة مساعدة لتنفيذ أمر عبر Reflection
    private fun execShizukuCommand(command: Array<String>): Boolean {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, command, null, null) as ShizukuRemoteProcess
            
            // الانتظار حتى ينتهي الأمر
            val exitCode = process.waitFor()
            process.destroy()
            
            Log.d(TAG, "Command: ${command.joinToString(" ")} -> exit=$exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "execShizukuCommand failed for: ${command.joinToString(" ")}", e)
            false
        }
    }

    suspend fun enableAirplaneMode(): Boolean = withContext(Dispatchers.IO) {
        // الطريقة 1: Android 12+ (cmd connectivity)
        if (execShizukuCommand(arrayOf("cmd", "connectivity", "airplane-mode", "enable"))) {
            Log.d(TAG, "✅ Airplane ON via cmd")
            return@withContext true
        }

        // الطريقة 2: Fallback (settings + broadcast)
        var success = execShizukuCommand(arrayOf("settings", "put", "global", "airplane_mode_on", "1"))
        execShizukuCommand(arrayOf("am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", "true"))
        
        // تعطيل البيانات والواي فاي أيضاً
        execShizukuCommand(arrayOf("svc", "data", "disable"))
        execShizukuCommand(arrayOf("svc", "wifi", "disable"))
        
        Log.d(TAG, "✅ Airplane ON via settings (success=$success)")
        success
    }

    suspend fun disableAirplaneMode(): Boolean = withContext(Dispatchers.IO) {
        // الطريقة 1: Android 12+ (cmd connectivity)
        if (execShizukuCommand(arrayOf("cmd", "connectivity", "airplane-mode", "disable"))) {
            Log.d(TAG, "✅ Airplane OFF via cmd")
            return@withContext true
        }

        // الطريقة 2: Fallback (settings + broadcast)
        var success = execShizukuCommand(arrayOf("settings", "put", "global", "airplane_mode_on", "0"))
        execShizukuCommand(arrayOf("am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", "false"))
        
        Log.d(TAG, "✅ Airplane OFF via settings (success=$success)")
        success
    }

    suspend fun enableMobileData(): Boolean = withContext(Dispatchers.IO) {
        val success = execShizukuCommand(arrayOf("svc", "data", "enable"))
        Log.d(TAG, "✅ Mobile data enabled (success=$success)")
        success
    }
}
