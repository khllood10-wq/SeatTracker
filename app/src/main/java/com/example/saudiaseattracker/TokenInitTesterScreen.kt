package com.example.saudiaseattracker

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

// ⭐ x-d-token الكامل (النسخة القديمة من رسالتك السابقة — كاملة)
private const val CAPTURED_XD_TOKEN =
    "3:3MOuDgC9o9aZGi9lqZ9Hug==:mr119Ssxuu3BgqXq4/MzMlS4wXZ0OzBtIJnd8rXgilPtuHu+ib1ENbreyQYv4BimNvXyxWsN2XAHF4aP/yZrKICUI+6RMikjboJ+XChLrN4oxts8ZY/2S9+vNXJXKRlLzSjt9a0OZphBQeRWm2I+0xF3+BuZyentFtOVZJ2VzG4=:lxFXXnttAuAR3urdCchZGruXmuvvRCRkI/sLWSYeUeY=:z6grhOzHFxb1Jyk86YmLT5B4kYzwG8WvtQDhfP6PDPiSrN76roSLrJoln5eTH3woLjKaXhwhWoCzLQ5gS67UvJUgJVMDkSqmLwm9E6MU/oFtvX+UKbZlcHI67d8PKNKfZyB8c8HWjAiSvzcMo99/8GzAsfhkymKSgDCep3x1xY37cmhJDH0Iyr0Rri/jM34gcst7IyJvEBRzrXd3leRhnxgKzEFCJenQW93jz31qiXy9UEO5Mer1KeaC7/XuTeNpVDaK6eUg8MiQSKSBTeP7Ovdbj+TwgzsqhZX503E9jJBqVfpQ1RjZYYKelnhHWtUJzCMFAiJWxOkvgmDlb72CKbD1sVMuFIsjoLy+wUnWRZgVi/StcWB+V3GexaHJSmJ9RPEwdnisnIpewoZtu3uEZIRx8pRFZgTGSajEPAeWnKVn/I2J:"

// ⭐ key body الكامل من رسالتك
private const val CAPTURED_KEY_BODY =
    "ZERUVjZZbmF6Z2xETmwyNUxQQy80Vi9xUUpIU1Q1MEY4VUV0OXdtVTYrSW45K0h0NjYwcHBqU05kaWROTUJLS0kvTDMxVjkyQzZ6ZXpWV3ZWNW9XOER5Z2I3RGFKMm5GTXVUMzlLcTJmaDVUM0dpR3NJTFhLd1kxR2xHVWZmaklSRTJmcDNjREhmZllEdFF6Tm81"

data class InitTestResult(
    val label: String,
    val code: Int,
    val hasJwt: Boolean,
    val hasSid: Boolean,
    val jwt: String,
    val sid: String,
    val bodyPreview: String,
    val exceptionType: String = "",
    val exceptionMessage: String = "",
    val elapsedMs: Long = 0
) {
    val color: Color get() = when {
        hasJwt -> Color(0xFF00C853)
        code in 200..299 -> Color(0xFF64B5F6)
        code == 403 -> Color(0xFFEF5350)
        code == 401 -> Color(0xFFFFA726)
        code == 0 -> Color(0xFF9E9E9E)
        else -> Color(0xFFD32F2F)
    }
    val verdict: String get() = when {
        hasJwt -> "🎉 JWT جديد!"
        code in 200..299 -> "200 بدون JWT"
        code == 401 -> "❌ التوكن مرفوض (401)"
        code == 403 -> "❌ Imperva يحظر (403)"
        code == 0 && exceptionType.contains("Timeout") -> "⏱️ انتهت المهلة (${elapsedMs}ms)"
        code == 0 && exceptionType.contains("UnknownHost") -> "🌐 DNS فشل"
        code == 0 && exceptionType.contains("SSL") -> "🔒 TLS رُفض"
        code == 0 && exceptionType.contains("IOException") -> "📡 اتصال مقطوع"
        code == 0 -> "❌ استثناء: $exceptionType"
        else -> "❌ خطأ HTTP $code"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenInitTesterScreen(navController: NavController, viewModel: FlightViewModel) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)

    var results by remember { mutableStateOf<List<InitTestResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var currentTest by remember { mutableStateOf("") }

    fun runTests() {
        if (running) return
        running = true
        results = emptyList()

        // ⭐ Timeout أطول (60 ثانية)
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            val endpoint = "https://dapi.saudia.com/session/auth/web/token/initialization"
            val savedCookies = prefs.getString("dynamic_cookies", "") ?: ""

            // ═══════════════════════════════════════════════
            // T1: x-d-token + key + كوكيز محفوظة
            // ═══════════════════════════════════════════════
            withContext(Dispatchers.Main) { currentTest = "T1: مع كل الكوكيز" }
            val r1 = testTokenInit(client, endpoint, CAPTURED_XD_TOKEN, CAPTURED_KEY_BODY,
                savedCookies.ifEmpty { null }, "T1: كل الكوكيز")
            withContext(Dispatchers.Main) { results = results + r1 }
            if (r1.hasJwt) { saveJwt(prefs, r1); withContext(Dispatchers.Main) { running = false }; return@launch }
            kotlinx.coroutines.delay(1000)

            // ═══════════════════════════════════════════════
            // T2: بدون كوكيز
            // ═══════════════════════════════════════════════
            withContext(Dispatchers.Main) { currentTest = "T2: بدون كوكيز" }
            val r2 = testTokenInit(client, endpoint, CAPTURED_XD_TOKEN, CAPTURED_KEY_BODY,
                null, "T2: بدون كوكيز")
            withContext(Dispatchers.Main) { results = results + r2 }
            if (r2.hasJwt) { saveJwt(prefs, r2); withContext(Dispatchers.Main) { running = false }; return@launch }
            kotlinx.coroutines.delay(1000)

            // ═══════════════════════════════════════════════
            // T3: كوكيز Imperva فقط
            // ═══════════════════════════════════════════════
            val imperva = savedCookies.split("; ").filter {
                it.startsWith("reese84=") || it.startsWith("incap_ses_") || it.startsWith("visid_incap_")
            }
            withContext(Dispatchers.Main) { currentTest = "T3: Imperva فقط" }
            val r3 = testTokenInit(client, endpoint, CAPTURED_XD_TOKEN, CAPTURED_KEY_BODY,
                imperva.ifEmpty { null }?.joinToString("; "), "T3: Imperva فقط")
            withContext(Dispatchers.Main) { results = results + r3 }
            if (r3.hasJwt) { saveJwt(prefs, r3); withContext(Dispatchers.Main) { running = false }; return@launch }
            kotlinx.coroutines.delay(1000)

            // ═══════════════════════════════════════════════
            // T4: مع Connection: close
            // ═══════════════════════════════════════════════
            withContext(Dispatchers.Main) { currentTest = "T4: Connection: close" }
            val r4 = testTokenInit(client, endpoint, CAPTURED_XD_TOKEN, CAPTURED_KEY_BODY,
                savedCookies.ifEmpty { null }, "T4: Connection close",
                extraHeaders = mapOf("Connection" to "close"))
            withContext(Dispatchers.Main) { results = results + r4 }
            if (r4.hasJwt) { saveJwt(prefs, r4); withContext(Dispatchers.Main) { running = false }; return@launch }

            withContext(Dispatchers.Main) {
                currentTest = ""
                running = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("🔬 اختبار إعادة x-d-token (تشخيصي)",
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("يعرض تفاصيل الاستثناء الكاملة — نحتاج نعرف لماذا HTTP 0",
            style = MaterialTheme.typography.bodySmall)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(10.dp)) {
                Text("x-d-token: ${CAPTURED_XD_TOKEN.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text("key body: ${CAPTURED_KEY_BODY.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }

        Button(
            onClick = { runTests() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                Spacer(Modifier.width(10.dp))
                Text(currentTest, fontSize = 11.sp)
            } else {
                Text("▶ تشغيل التشخيص", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        if (results.isNotEmpty()) {
            Text("النتائج (${results.size}):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            results.forEach { r ->
                Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(2.dp, r.color)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("HTTP ${r.code}", color = r.color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Text(r.verdict, color = r.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("⏱️ ${r.elapsedMs}ms", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        if (r.exceptionType.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Column(Modifier.padding(8.dp)) {
                                    Text("🔴 Exception Type:", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    Text(r.exceptionType, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFFD32F2F))
                                    if (r.exceptionMessage.isNotEmpty()) {
                                        Text("Message:", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                        SelectionContainer {
                                            Text(r.exceptionMessage, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        } else if (r.bodyPreview.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(r.bodyPreview.take(400), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
            Text("← رجوع")
        }
    }
}

// ══════════════════════════════════════════════════════════════
private suspend fun testTokenInit(
    client: OkHttpClient,
    endpoint: String,
    xdToken: String,
    keyBody: String,
    cookies: String?,
    label: String,
    extraHeaders: Map<String, String> = emptyMap()
): InitTestResult = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()
    var exceptionType = ""
    var exceptionMessage = ""

    try {
        val body = "key=$keyBody"
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("x-d-token", xdToken)
            .header("platform", "Web")
            .header("version", "27.2")
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Origin", "https://www.saudia.com")
            .header("Referer", "https://www.saudia.com/ar-SA")
            .header("Accept-Language", "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        extraHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
        cookies?.let { if (it.isNotEmpty()) reqBuilder.header("Cookie", it) }

        val response = client.newCall(reqBuilder.build()).execute()
        val respBody = response.body?.string() ?: ""
        val code = response.code
        val elapsed = System.currentTimeMillis() - startTime
        response.close()

        var hasJwt = false
        var hasSid = false
        var jwt = ""
        var sid = ""

        if (code in 200..299) {
            try {
                val json = JSONObject(respBody)
                val accessToken = json.optString("access_token", "")
                val sessionId = json.optString("session_id", "")
                if (accessToken.isNotEmpty()) { hasJwt = true; jwt = accessToken }
                if (sessionId.isNotEmpty()) { hasSid = true; sid = sessionId }
            } catch (e: Exception) {
                Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(respBody)?.let { hasJwt = true; jwt = it.groupValues[1] }
                Regex("\"session_id\"\\s*:\\s*\"([^\"]+)\"").find(respBody)?.let { hasSid = true; sid = it.groupValues[1] }
            }
        }

        InitTestResult(label, code, hasJwt, hasSid, jwt, sid, respBody.take(400), "", "", elapsed)
    } catch (e: Exception) {
        exceptionType = e.javaClass.simpleName
        exceptionMessage = "${e.message ?: ""}\n\n${e.cause?.javaClass?.simpleName ?: ""}: ${e.cause?.message ?: ""}"
        Log.e("TokenInit", "[$label] $exceptionType: $exceptionMessage", e)

        InitTestResult(
            label = label,
            code = 0,
            hasJwt = false, hasSid = false, jwt = "", sid = "",
            bodyPreview = "",
            exceptionType = exceptionType,
            exceptionMessage = exceptionMessage.take(500),
            elapsedMs = System.currentTimeMillis() - startTime
        )
    }
}

private fun saveJwt(prefs: android.content.SharedPreferences, r: InitTestResult) {
    prefs.edit()
        .putString("auth_token", "Bearer ${r.jwt}")
        .putString("session_id", r.sid)
        .putString("xd_token", CAPTURED_XD_TOKEN)
        .putLong("token_saved_at", System.currentTimeMillis())
        .apply()
    Log.d("Session", "JWT saved from token init")
}
