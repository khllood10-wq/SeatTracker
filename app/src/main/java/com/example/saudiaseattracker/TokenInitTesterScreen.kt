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
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════════════════════
// القيم الملتقطة من السكريبت (الجديدة — timestamp 1794367508992)
// ══════════════════════════════════════════════════════════
private const val CAPTURED_XD_TOKEN =
    "3:hZcTrr6YK93lEdp5NFgzPQ==:AiDNK0Qg3GNenZyYa3hXh+LDSmw4BrryVr+IjlGDRaV6VDiRgHd1tr7u5GiRg3lrgwm5S7JTgUtPVl4vcR2e+eADx6/KpUTVfdQB1+ZR3Oy2bFyhjud7PG5NnaMrrzDHibYnTJ4XOkB+7vamdj6ec0thu0ErLq99h8tBuhx+FKk=:bhsavVVDGyj8BwFeChe3IfW1J5GfxKzCEFJsOk+69Ek=:b195C0bceVKfSjfFuTmR2/FOWZNDT7Yuxvrva5RzIybGsuEug/CmrT+WvKFisHWmR3MbR3FJeBnQttFv2PatTQd6VUOwKe6kHvNfyma+p5VKUHt/A3mC65rPW6MHhxRRhaECf0YBwQz0BjmAQaNSl89Nq0LzU4mXKxlXoJX40m7m1uXDi24adMqprXxNxlfp6Q+F9oSqsNtgM+xeqQ5qvkBjNvBhJ7Cggq73F6IpGtpM19gHauHhoSlqTdnQT0o8WS4Uo1CIw+DhgSl+St/+ZQPiiwD/YVitnU07skHIX5OsHZV9BaIJtB1NsjBQo9U8vW7j2Kza6GxVXB6vOiw/2ip8rS5lfV+Asded4dow/Kuuemnq2Srs/au2iQ1cOncWggdYMhi4817iOZYKCC8uyxoRNAnJ734El8QzQXSNUioYebBOHpz2pWiMYnGBhO96sn33zQq5kK4SpBfvyA==:"

private const val CAPTURED_KEY =
    "UUh2b1ptWElibEoraXR4N21KVUlpdGNuQWlKa2g4eUdXOFBzdUlCQU5aemlRRmhTK09paG1mZVZLNnJPdUxRN3FrUjdjYWxVL0tKRDBFSkxFUm5wK21rNkViMDhlcitYQThYTlNCaEZBQTRaMERTOWV2MXNOMFNWNzhKSDJzYUZ4SWQxOUNEZFJkUk1zVGhFaEhSTjBEcXpVUHdtMGNKNmoyUEF0eEs4NE0wcXZIVHNVOHVkTjNUWktBaW5hcjJkL0REdEpWckRhS2d1c3UvaXVFM1JyMDEybHZzZnl6Q2hrdEdBLzViMEdNUlh1VzMva1ZTWHVwYnFmU3UrUzRySTJjKzdybGU3ODcrT2JYQjVyaWREcGZQK3NQWFd2Zjd6SFVkeStXVTZ4dGNIN1NEZ01lMy84em5Dd3c3dTJ5WS9NeTROamd5eTVNdWlsVlJHM1VtOFRsVW1GcHJncUx3TVljSjZCMHowZUtnZVZWUlRtalBKZnd5dCtDNGMyVTFpUjVPa2RWNzBMZEg5UTVYaWhBSnVORHBFakZ2Wmc5bGs4a2JiNmtMd2w0c2F0b3QzR1gxZHBUYmlGdU8zaTZ6UlBRZmV0Q0FuR0ZVRUFVTk1MNTkzMUdBK1hzSGsyUzNsbUdoaElrczNac0hQZkVUdStyNG5qUFBqUGpxZTROZXM2QWwvaTZFdDhYbTNMd3dWVmVYT1F0R3FUOU1Wc04rSHU4eVBlV3J4cmdOYW9ubWlmMkxkeDV1Rm9oSUhvSjI4U0RBd0lnMGdiZVM3OXp3N3FMRHQ3blR5NWNEL3Y0TURPd3cwOERCY1lpdEs0bGJKNUFQSjhnWC8ycGN3REk1K1NLSHp4VlRyT3lrbDQ5V2Fhb2pnaHh4TTdJYlJxMXhGMG95UDJxRDVIU2c9"

private const val CAPTURED_BODY =
    "NjMyMmZkNGJjNDJjZmZiMzIwMzlhZTYzNTQ4ZGFkZjFkNmZhMmJkN2M4OTdkOGYwZGI2Y2VhZDU2YzYxMTdhODUxNmM0OWJjYmY5NjQ4OTRlN2E0OTRiMjM0MTQ1ZTU4NGY2NGQ1YTg5MmIzYjkzYjk0YmU3OWI3ZWMwNzVhYWYwYWFjZjY0YWY1NzMyOTI0MDU0NDA3MzMxNzI2ZTI1YzczNjY5OWYwYjU2OWZmOTU2MDM1NmZmMjVmOTg3NjhkNWQ0MWM5NTg5YzFkODE2YmRlM2RhZjA2ZjM1ZWUyZTU3ZWVjMGE4YjQ1ZThmY2NkZjI3Nzc1ZjAxN2M3NDVkNTBmMDFiOTFkNmVmMTljZWEzYThjZjg0ODEzNTkzOTJiMzZiODFjMzIyZmYyNWRmYzk5OWM4MzA3NzViNzRmOWJkNTU4MjczMzZiM2E2NGQ3NDhkNGI4MDdiNTYyOTMxYWY3MmIzYWFlMDFkZGY4NzZjOTljOTI2YjZmMjA5MWM4ZDE1ZGI4ODcyNjg0NDllODQwZmQ5ZjUzN2JmOWNmZDZhNDMzMTUwY2I5OGFmZDQ3NmM2N2RmZWRhMDE0YTU5NGU1YmJlMGM3NjIwMzUzMDkzNTcwOGUxZTEzMzg0NGFlYzlkODU4M2JiZTc5MGZmYjU1ZmE3N2YxZTAwY2FkNGY="

data class InitTestResult(
    val label: String,
    val code: Int,
    val hasJwt: Boolean,
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
        code == 401 -> "❌ مرفوض 401"
        code == 403 -> "❌ محظور 403 (Imperva)"
        code == 0 && exceptionType.contains("Timeout") -> "⏱️ مهلة (${elapsedMs}ms)"
        code == 0 && exceptionType.contains("SSL") -> "🔒 TLS مرفوض"
        code == 0 -> "❌ $exceptionType"
        else -> "❌ خطأ $code"
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
            // T1: مع الكوكيز (أهم اختبار)
            // ═══════════════════════════════════════════════
            withContext(Dispatchers.Main) { currentTest = "T1: مع الكوكيز" }
            val r1 = testTokenInit(
                client, endpoint, CAPTURED_XD_TOKEN, CAPTURED_KEY, CAPTURED_BODY,
                savedCookies.ifEmpty { null }, "T1: مع الكوكيز"
            )
            withContext(Dispatchers.Main) { results = results + r1 }
            if (r1.hasJwt) { saveJwt(prefs, r1); withContext(Dispatchers.Main) { running = false }; return@launch }
            kotlinx.coroutines.delay(1000)

            // ═══════════════════════════════════════════════
            // T2: بدون كوكيز
            // ═══════════════════════════════════════════════
            withContext(Dispatchers.Main) { currentTest = "T2: بدون كوكيز" }
            val r2 = testTokenInit(
                client, endpoint, CAPTURED_XD_TOKEN, CAPTURED_KEY, CAPTURED_BODY,
                null, "T2: بدون كوكيز"
            )
            withContext(Dispatchers.Main) { results = results + r2 }
            if (r2.hasJwt) { saveJwt(prefs, r2); withContext(Dispatchers.Main) { running = false }; return@launch }

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
        Text("🔬 اختبار token/initialization",
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("يرسل key + body كاملاً (form-urlencoded)",
            style = MaterialTheme.typography.bodySmall)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(10.dp)) {
                Text("📋 القيم الملتقطة:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("x-d-token: ${CAPTURED_XD_TOKEN.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text("key: ${CAPTURED_KEY.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text("body: ${CAPTURED_BODY.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                val savedCookies = prefs.getString("dynamic_cookies", "") ?: ""
                Text("Cookies: ${savedCookies.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
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
                Text("▶ إرسال token/initialization", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        val foundJwt = results.find { it.hasJwt }
        foundJwt?.let { r ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                border = BorderStroke(3.dp, Color(0xFF00C853))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("🎉🎉 نجحنا!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00C853))
                    Spacer(Modifier.height(8.dp))
                    Text("JWT: ${r.jwt.take(60)}...", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Text("sid: ${r.sid.take(40)}...", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Text(
                            "✅ x-d-token + key + body يعملون من Kotlin!\n" +
                            "لا نحتاج WebView لتجديد JWT.",
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
                                    Text("🔴 ${r.exceptionType}", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color(0xFFD32F2F))
                                    if (r.exceptionMessage.isNotEmpty()) {
                                        SelectionContainer {
                                            Text(r.exceptionMessage, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        } else if (r.bodyPreview.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(r.bodyPreview.take(500), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
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
    key: String,
    bodyValue: String,
    cookies: String?,
    label: String
): InitTestResult = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()
    var exceptionType = ""
    var exceptionMessage = ""

    try {
        // ⚠️ مهم جداً: key=X&body=Y (form-urlencoded)
        val reqBody = "key=$key&body=$bodyValue"

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(reqBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
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

        cookies?.let { if (it.isNotEmpty()) reqBuilder.header("Cookie", it) }

        val response = client.newCall(reqBuilder.build()).execute()
        val respBody = response.body?.string() ?: ""
        val code = response.code
        val elapsed = System.currentTimeMillis() - startTime
        response.close()

        var hasJwt = false
        var jwt = ""
        var sid = ""

        if (code in 200..299) {
            try {
                val json = JSONObject(respBody)
                jwt = json.optString("access_token", "")
                sid = json.optString("session_id", "")
                hasJwt = jwt.isNotEmpty()
            } catch (e: Exception) {}
        }

        InitTestResult(label, code, hasJwt, jwt, sid, respBody.take(400), "", "", elapsed)
    } catch (e: Exception) {
        exceptionType = e.javaClass.simpleName
        exceptionMessage = "${e.message ?: ""}\n${e.cause?.javaClass?.simpleName ?: ""}: ${e.cause?.message ?: ""}"
        Log.e("TokenInit", "[$label] $exceptionType: $exceptionMessage", e)
        InitTestResult(
            label = label, code = 0, hasJwt = false, jwt = "", sid = "",
            bodyPreview = "", exceptionType = exceptionType,
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
    Log.d("Session", "JWT saved from token init test")
}
