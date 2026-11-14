package com.example.saudiaseattracker

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

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

    suspend fun enableAirplaneMode(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Method 1: Android 12+ (cmd connectivity)
            var exitCode = -1
            try {
                val p1 = Shizuku.newProcess(arrayOf(
                    "cmd", "connectivity", "airplane-mode", "enable"
                ), null, null)
                exitCode = p1.waitFor()
                if (exitCode == 0) {
                    Log.d(TAG, "✅ Airplane ON via cmd")
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.w(TAG, "cmd connectivity failed, trying settings", e)
            }

            // Method 2: Fallback (settings + broadcast)
            val p2 = Shizuku.newProcess(arrayOf(
                "settings", "put", "global", "airplane_mode_on", "1"
            ), null, null)
            p2.waitFor()

            val p3 = Shizuku.newProcess(arrayOf(
                "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
                "--ez", "state", "true"
            ), null, null)
            p3.waitFor()

            // Also disable data + wifi
            try {
                Shizuku.newProcess(arrayOf("svc", "data", "disable"), null, null).waitFor()
                Shizuku.newProcess(arrayOf("svc", "wifi", "disable"), null, null).waitFor()
            } catch (e: Exception) {}

            Log.d(TAG, "✅ Airplane ON via settings")
            true
        } catch (e: Exception) {
            Log.e(TAG, "enableAirplaneMode failed", e)
            false
        }
    }

    suspend fun disableAirplaneMode(): Boolean = withContext(Dispatchers.IO) {
        try {
            var exitCode = -1
            try {
                val p1 = Shizuku.newProcess(arrayOf(
                    "cmd", "connectivity", "airplane-mode", "disable"
                ), null, null)
                exitCode = p1.waitFor()
                if (exitCode == 0) {
                    Log.d(TAG, "✅ Airplane OFF via cmd")
                }
            } catch (e: Exception) {
                Log.w(TAG, "cmd connectivity failed, trying settings", e)
            }

            if (exitCode != 0) {
                val p2 = Shizuku.newProcess(arrayOf(
                    "settings", "put", "global", "airplane_mode_on", "0"
                ), null, null)
                p2.waitFor()

                val p3 = Shizuku.newProcess(arrayOf(
                    "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
                    "--ez", "state", "false"
                ), null, null)
                p3.waitFor()
            }

            Log.d(TAG, "✅ Airplane OFF")
            true
        } catch (e: Exception) {
            Log.e(TAG, "disableAirplaneMode failed", e)
            false
        }
    }

    suspend fun enableMobileData(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shizuku.newProcess(arrayOf("svc", "data", "enable"), null, null).waitFor()
            Log.d(TAG, "✅ Mobile data enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "enableMobileData failed", e)
            false
        }
    }
}
