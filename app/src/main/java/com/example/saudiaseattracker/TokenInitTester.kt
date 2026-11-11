package com.example.saudiaseattracker

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import java.util.concurrent.TimeUnit

// ⭐ القيم الملتقطة من السجل — من جلسة المتصفح
private const val CAPTURED_XD_TOKEN =
    "3:EAnSExmeW0k7qR5o3BvR1Q==:LOW62nhdIjA4aPsgPQA4Y+bgyLVUFKr1E0ZwZqqFJnHTIZENzs0xUEwwkiJjsGZk1jubPpK5V..."

private const val CAPTURED_KEY_BODY =
    "ZERUVjZZbmF6Z2xETmwyNUxQQy80Vi9xUUpIU1Q1MEY4VUV0OXdtVTYrSW45K0h0NjYwcHBqU05kaWROTUJLS0kvTDMxVjkyQzZ6ZXpWV3ZWNW9XOER5Z2I3RGFKMm5GTXVUMzlLcTJmaDVUM0dpR3NJTFhLd1kxR2xHVWZmaklSRTJmcDNjREhmZllEdFF6Tm81"

data class InitTestResult(
    val label: String,
    val code: Int,
    val hasJwt: Boolean,
    val hasSid: Boolean,
    val jwt: String,
    val sid: String,
    val bodyPreview: String
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
        hasJwt -> "🎉 JWT جديد! (يُعاد استخدام x-d-token)"
        code in 200..299 -> "200 لكن بدون JWT"
        code == 401 -> "❌ التوكن مرفوض"
        code == 403 -> "❌ Imperva يحظر"
        code == 0 -> "استثناء"
        else -> "خطأ $code"
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

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            val endpoint = "https://dapi.saudia.com/session/auth/web/token/initialization"

            // ═══════════════════════════════════════════════
            // اختبار 1: نفس x-d-token + نفس key body (بدون كوكيز)
            // ═══════════════════════════════════════════════
            withContext(Dispatchers.Main) { currentTest = "T1: نفس التوكن والجسم" }
            val r1 = testTokenInit(client, endpoint, CAPTURED_XD_TOKEN, CAPTURED_KEY_BODY, null, "T1: نفس الشيء")
            withContext(Dispatchers.Main) { results = results + r1 }
            if (r1.hasJwt) {
                saveAndExit(prefs, r1)
                withContext(Dispatchers.Main) { running = false }
                return@launch
            }
            kotlinx.coroutines.delay(1500)

            // ═══════════════════════════════════════════════
            // اختبار 2: نفس x-d-token + نفس key body + كوكيز محفوظة
            // ═══════════════════════════════════════════════
            val savedCookies = prefs.getString("dynamic_cookies", "") ?: ""
            withContext(Dispatchers.Main) { currentTest = "T2: مع كوكيز محفوظة" }
            val r2 = testTokenInit(
                client, endpoint, CAPTURED_XD_TOKEN, CAPTURED_KEY_BODY,
                savedCookies.ifEmpty { null }, "T2: + كوكيز"
            )
            withContext(Dispatchers.Main) { results = results + r2 }
            if (r2.hasJwt) {
                saveAndExit(prefs, r2)
                withContext(Dispatchers.Main) { running = false }
                return@launch
            }
            kotlinx.coroutines.delay(1500)

            // ═══════════════════════════════════════════════
            // اختبار 3: نفس x-d-token + نفس key body + كوكيز Imperva فقط
            // ═══════════════════════════════════════════════
            val impervaParts = savedCookies.split("; ").filter {
                it.startsWith("reese84=") || it.startsWith("incap_ses_") || it.startsWith("visid_incap_")
            }
            val impervaCookies = if (impervaParts.isNotEmpty()) impervaParts.joinToString("; ") else null
            withContext(Dispatchers.Main) { currentTest = "T3: Imperva فقط" }
            val r3 = testTokenInit(
                client, endpoint, CAPTURED_XD_TOKEN, CAPTURED_KEY_BODY,
                impervaCookies, "T3: Imperva فقط"
            )
            withContext(Dispatchers.Main) { results = results + r3 }
            if (r3.hasJwt) {
                saveAndExit(prefs, r3)
                withContext(Dispatchers.Main) { running = false }
                return@launch
            }

            withContext(Dispatchers.Main) {
                currentTest = ""
                running = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "🔬 اختبار إعادة استخدام x-d-token",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "نعيد إرسال نفس x-d-token + key body الذي التقطناه — إذا أعاد JWT جديد، لا نحتاج WebView بعد الآن!",
            style = MaterialTheme.typography.bodySmall
        )

        // ── القيم الملتقطة ──
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(10.dp)) {
                Text("📋 القيم الملتقطة:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("x-d-token: ${CAPTURED_XD_TOKEN.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text("key body: ${CAPTURED_KEY_BODY.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                val savedCookies = prefs.getString("dynamic_cookies", "") ?: ""
                Text("Cookies محفوظة: ${savedCookies.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                val currentToken = prefs.getString("auth_token", "") ?: ""
                Text(
                    "Bearer حالي: ${if (currentToken.isEmpty()) "❌" else "✅ ${currentToken.take(30)}..."}",
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp
                )
            }
        }

        // ── زر التشغيل ──
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
                Text("▶ اختبار إعادة الاستخدام", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        // ── النجاح ──
        val foundJwt = results.find { it.hasJwt }
        foundJwt?.let { r ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                border = androidx.compose.foundation.BorderStroke(3.dp, Color(0xFF00C853))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("🎉🎉 نجحنا!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00C853))
                    Spacer(Modifier.height(8.dp))
                    Text("JWT جديد: ${r.jwt.take(60)}...", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Text("session_id: ${r.sid.take(30)}...", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Text(
                            "✅ الآن يمكننا تجديد الـ JWT بدون WebView!\n" +
                            "x-d-token + key body يعملان معاً.",
                            modifier = Modifier.padding(10.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Home.route) { inclusive = true }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🚀 جرّب الحجز") }
                }
            }
        }

        // ── النتائج ──
        if (results.isNotEmpty()) {
            Text("النتائج (${results.size}):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            results.forEach { r ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(2.dp, r.color)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(r.label, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("HTTP ${r.code}", color = r.color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Text(r.verdict, color = r.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (r.bodyPreview.isNotEmpty() && r.code in 200..299) {
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(r.bodyPreview.take(300), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("← رجوع") }
    }
}

// ══════════════════════════════════════════════════════════════
// Top-level helpers
// ══════════════════════════════════════════════════════════════
private suspend fun testTokenInit(
    client: OkHttpClient,
    endpoint: String,
    xdToken: String,
    keyBody: String,
    cookies: String?,
    label: String
): InitTestResult = withContext(Dispatchers.IO) {
    try {
        val body = "key=$keyBody"
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("x-d-token", xdToken)
            .header("platform", "Web")
            .header("version", "27.2")
            .header("Accept", "application/json")
            .header("Origin", "https://www.saudia.com")
            .header("Referer", "https://www.saudia.com/ar-SA")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

        cookies?.let { if (it.isNotEmpty()) reqBuilder.header("Cookie", it) }

        val response = client.newCall(reqBuilder.build()).execute()
        val respBody = response.body?.string() ?: ""
        val code = response.code
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
                if (accessToken.isNotEmpty()) {
                    hasJwt = true
                    jwt = accessToken
                }
                if (sessionId.isNotEmpty()) {
                    hasSid = true
                    sid = sessionId
                }
            } catch (e: Exception) {
                // Fallback regex
                val jwtRegex = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
                jwtRegex.find(respBody)?.let {
                    hasJwt = true
                    jwt = it.groupValues[1]
                }
                val sidRegex = Regex("\"session_id\"\\s*:\\s*\"([^\"]+)\"")
                sidRegex.find(respBody)?.let {
                    hasSid = true
                    sid = it.groupValues[1]
                }
            }
        }

        InitTestResult(
            label = label,
            code = code,
            hasJwt = hasJwt,
            hasSid = hasSid,
            jwt = jwt,
            sid = sid,
            bodyPreview = respBody.take(400)
        )
    } catch (e: Exception) {
        InitTestResult(
            label = label,
            code = 0,
            hasJwt = false,
            hasSid = false,
            jwt = "",
            sid = "",
            bodyPreview = e.message ?: "(exception)"
        )
    }
}

private fun saveAndExit(prefs: android.content.SharedPreferences, result: InitTestResult) {
    prefs.edit()
        .putString("auth_token", "Bearer ${result.jwt}")
        .putString("session_id", result.sid)
        .putString("xd_token", CAPTURED_XD_TOKEN)
        .putLong("token_saved_at", System.currentTimeMillis())
        .apply()
    Log.d("Session", "JWT saved from token init test")
}
