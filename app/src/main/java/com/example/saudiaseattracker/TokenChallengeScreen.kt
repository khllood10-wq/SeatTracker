package com.example.saudiaseattracker

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ⭐ الـ x-d-token الكامل الذي أرسله المستخدم
private const val CAPTURED_XD_TOKEN =
    "3:3MOuDgC9o9aZGi9lqZ9Hug==:mr119Ssxuu3BgqXq4/MzMlS4wXZ0OzBtIJnd8rXgilPtuHu+ib1ENbreyQYv4BimNvXyxWsN2XAHF4aP/yZrKICUI+6RMikjboJ+XChLrN4oxts8ZY/2S9+vNXJXKRlLzSjt9a0OZphBQeRWm2I+0xF3+BuZyentFtOVZJ2VzG4=:lxFXXnttAuAR3urdCchZGruXmuvvRCRkI/sLWSYeUeY=:z6grhOzHFxb1Jyk86YmLT5B4kYzwG8WvtQDhfP6PDPiSrN76roSLrJoln5eTH3woLjKaXhwhWoCzLQ5gS67UvJUgJVMDkSqmLwm9E6MU/oFtvX+UKbZlcHI67d8PKNKfZyB8c8HWjAiSvzcMo99/8GzAsfhkymKSgDCep3x1xY37cmhJDH0Iyr0Rri/jM34gcst7IyJvEBRzrXd3leRhnxgKzEFCJenQW93jz31qiXy9UEO5Mer1KeaC7/XuTeNpVDaK6eUg8MiQSKSBTeP7Ovdbj+TwgzsqhZX503E9jJBqVfpQ1RjZYYKelnhHWtUJzCMFAiJWxOkvgmDlb72CKbD1sVMuFIsjoLy+wUnWRZgVi/StcWB+V3GexaHJSmJ9RPEwdnisnIpewoZtu3uEZIRx8pRFZgTGSajEPAeWnKVn/I2J:"

data class TokenTest(
    val name: String,
    val code: Int,
    val hasJwt: Boolean,
    val jwt: String,
    val sessionId: String,
    val bodyPreview: String
) {
    val color: Color get() = when {
        hasJwt -> Color(0xFF00C853)
        code in 200..299 -> Color(0xFF64B5F6)
        code == 401 -> Color(0xFFFFA726)
        code == 403 -> Color(0xFFEF5350)
        code == 0 -> Color(0xFF9E9E9E)
        else -> Color(0xFFD32F2F)
    }
    val verdict: String get() = when {
        hasJwt -> "🎉 JWT مُلتقط!"
        code in 200..299 -> "200 (بدون JWT)"
        code == 401 -> "يحتاج توكن"
        code == 403 -> "Imperva يحظر"
        code == 0 -> "استثناء شبكة"
        else -> "خطأ $code"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenChallengeScreen(navController: NavController, viewModel: FlightViewModel) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)

    var results by remember { mutableStateOf<List<TokenTest>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var currentTest by remember { mutableStateOf("") }
    var foundJwt by remember { mutableStateOf<String?>(null) }
    var foundSid by remember { mutableStateOf<String?>(null) }

    fun runTests() {
        if (running) return
        running = true
        results = emptyList()
        foundJwt = null
        foundSid = null

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            val endpoint = "https://dapi.saudia.com/session/auth/web/token/initialization"
            val savedCookies = prefs.getString("dynamic_cookies", "") ?: ""

            // ═══════════════════════════════════════════════
            // Test 1: بدون كوكيز (مباشر)
            // ═══════════════════════════════════════════════
            withContext(Dispatchers.Main) { currentTest = "T1: بدون كوكيز" }
            val r1 = tryTokenInit(client, endpoint, CAPTURED_XD_TOKEN, null)
            withContext(Dispatchers.Main) { results = results + r1 }
            if (r1.hasJwt) {
                saveAndReturn(prefs, r1)
                withContext(Dispatchers.Main) {
                    foundJwt = r1.jwt
                    foundSid = r1.sessionId
                    currentTest = ""
                    running = false
                }
                return@launch
            }
            delay(1500)

            // ═══════════════════════════════════════════════
            // Test 2: مع الكوكيز المحفوظة
            // ═══════════════════════════════════════════════
            withContext(Dispatchers.Main) { currentTest = "T2: مع كوكيز محفوظة" }
            val r2 = tryTokenInit(client, endpoint, CAPTURED_XD_TOKEN, savedCookies.ifEmpty { null })
            withContext(Dispatchers.Main) { results = results + r2 }
            if (r2.hasJwt) {
                saveAndReturn(prefs, r2)
                withContext(Dispatchers.Main) {
                    foundJwt = r2.jwt
                    foundSid = r2.sessionId
                    currentTest = ""
                    running = false
                }
                return@launch
            }
            delay(1500)

            // ═══════════════════════════════════════════════
            // Test 3: مع reese84 فقط (Imperva fingerprint)
            // ═══════════════════════════════════════════════
            withContext(Dispatchers.Main) { currentTest = "T3: مع reese84 + incap_ses" }
            val cookieParts = savedCookies.split("; ").filter {
                it.startsWith("reese84=") || it.startsWith("incap_ses_") || it.startsWith("visid_incap_")
            }
            val impervaCookies = if (cookieParts.isNotEmpty()) cookieParts.joinToString("; ") else null
            val r3 = tryTokenInit(client, endpoint, CAPTURED_XD_TOKEN, impervaCookies)
            withContext(Dispatchers.Main) { results = results + r3 }
            if (r3.hasJwt) {
                saveAndReturn(prefs, r3)
                withContext(Dispatchers.Main) {
                    foundJwt = r3.jwt
                    foundSid = r3.sessionId
                    currentTest = ""
                    running = false
                }
                return@launch
            }
            delay(1500)

            // ═══════════════════════════════════════════════
            // Test 4: بدون User-Agent Chrome (يستخدم OkHttp الافتراضي)
            // ═══════════════════════════════════════════════
            withContext(Dispatchers.Main) { currentTest = "T4: بدون UA Chrome" }
            val r4 = tryTokenInit(client, endpoint, CAPTURED_XD_TOKEN, savedCookies.ifEmpty { null }, useCustomUA = false)
            withContext(Dispatchers.Main) { results = results + r4 }
            if (r4.hasJwt) {
                saveAndReturn(prefs, r4)
                withContext(Dispatchers.Main) {
                    foundJwt = r4.jwt
                    foundSid = r4.sessionId
                    currentTest = ""
                    running = false
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                currentTest = ""
                running = false
            }
        }
    }

    suspend fun tryTokenInit(
        client: OkHttpClient,
        endpoint: String,
        xdToken: String,
        cookies: String?,
        useCustomUA: Boolean = true
    ): TokenTest = withContext(Dispatchers.IO) {
        val name = when {
            cookies == null && useCustomUA -> "T1: بدون كوكيز"
            cookies == savedCookies() -> "T2: كوكيز محفوظة"
            cookies != null && cookies.contains("reese84") -> "T3: Imperva فقط"
            !useCustomUA -> "T4: بدون UA Chrome"
            else -> "T"
        }
        try {
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .header("x-d-token", xdToken)
                .header("platform", "Web")
                .header("version", "27.2")
                .header("Accept", "application/json")
                .header("Origin", "https://www.saudia.com")
                .header("Referer", "https://www.saudia.com/ar-SA")

            if (useCustomUA) {
                reqBuilder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            }

            cookies?.let { if (it.isNotEmpty()) reqBuilder.header("Cookie", it) }

            val resp = client.newCall(reqBuilder.build()).execute()
            val body = resp.body?.string() ?: ""

            val jwtRegex = Regex("eyJ[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{20,}")
            val jwtMatch = jwtRegex.find(body)
            val sidMatch = Regex("\"session_id\"\\s*:\\s*\"([^\"]+)\"").find(body)

            resp.close()

            TokenTest(
                name = name,
                code = resp.code,
                hasJwt = jwtMatch != null,
                jwt = jwtMatch?.value ?: "",
                sessionId = sidMatch?.groupValues?.get(1) ?: "",
                bodyPreview = body.take(400)
            )
        } catch (e: Exception) {
            TokenTest(
                name = name,
                code = 0,
                hasJwt = false,
                jwt = "",
                sessionId = "",
                bodyPreview = e.message ?: "(exception)"
            )
        }
    }

    fun saveAndReturn(prefs: android.content.SharedPreferences, result: TokenTest) {
        prefs.edit()
            .putString("auth_token", "Bearer ${result.jwt}")
            .putString("session_id", result.sessionId)
            .putString("xd_token", CAPTURED_XD_TOKEN)
            .apply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "🎯 تحدي: التقاط التوكن بدون WebView",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "نرسل POST إلى /session/auth/web/token/initialization بأربع طرق",
            style = MaterialTheme.typography.bodySmall
        )

        // ── معلومات الجلسة ──
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(10.dp)) {
                Text("x-d-token: ${CAPTURED_XD_TOKEN.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                val savedCookies = prefs.getString("dynamic_cookies", "") ?: ""
                Text("Cookies: ${savedCookies.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                val token = prefs.getString("auth_token", "") ?: ""
                Text(
                    "Bearer حالياً: ${if (token.isEmpty()) "❌ لا يوجد" else "✅ ${token.take(40)}..."}",
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
                Text("▶ تشغيل الاختبارات (4 طرق)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        // ── نجاح! ──
        foundJwt?.let { jwt ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                border = BorderStroke(3.dp, Color(0xFF00C853))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("🎉 نجحنا!", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF00C853))
                    Spacer(Modifier.height(8.dp))
                    Text("JWT: ${jwt.take(60)}...", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    foundSid?.let { Text("session_id: $it", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Home.route) { inclusive = true }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🚀 جرّب الحجز الآن") }
                }
            }
        }

        // ── النتائج ──
        if (results.isNotEmpty()) {
            Text("النتائج (${results.size}):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            results.forEach { r ->
                Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(2.dp, r.color)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("HTTP ${r.code}", color = r.color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Text(r.verdict, color = r.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (r.code in 200..299) {
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(r.bodyPreview, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
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

private fun savedCookies(): String = ""
