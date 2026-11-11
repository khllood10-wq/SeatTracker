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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class TestStep(val name: String, val status: String, val color: Color, val detail: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GetTestScreen(navController: NavController, viewModel: FlightViewModel) {
    var steps by remember { mutableStateOf<List<TestStep>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf("") }

    fun addStep(name: String, status: String, color: Color, detail: String) {
        steps = steps + TestStep(name, status, color, detail)
    }

    fun runTest() {
        if (running) return
        running = true
        steps = emptyList()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        CoroutineScope(Dispatchers.IO).launch {
            val cookies = mutableMapOf<String, String>()
            var xdToken = ""

            try {
                // ═══ 1. GET saudia.com/ar-SA (بدون كوكيز) ═══
                withContext(Dispatchers.Main) { currentStep = "1: GET /ar-SA" }
                val resp1 = client.newCall(
                    Request.Builder()
                        .url("https://www.saudia.com/ar-SA")
                        .header("User-Agent", ua)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7")
                        .get()
                        .build()
                ).execute()

                val code1 = resp1.code
                val setCookies1 = resp1.headers.values("Set-Cookie")
                val body1 = resp1.body?.string() ?: ""
                resp1.close()

                // استخراج كوكيز
                setCookies1.forEach { c ->
                    val first = c.split(";").first().trim()
                    val p = first.split("=", limit = 2)
                    if (p.size == 2) cookies[p[0].trim()] = p[1].trim()
                }

                // ⭐ محاولة استخراج x-d-token من HTML
                val xdRegex = Regex("""x-d-token["']?\s*[:=]\s*["']?(3:[A-Za-z0-9+/=:]{50,})""")
                val xdFromHtml = xdRegex.find(body1)?.groupValues?.get(1) ?: ""
                if (xdFromHtml.isNotEmpty()) xdToken = xdFromHtml

                addStep(
                    "1. GET /ar-SA",
                    "HTTP $code1",
                    if (code1 in 200..299) Color(0xFF00C853) else Color(0xFFD32F2F),
                    "Body: ${body1.length} chars\n" +
                    "Set-Cookie: ${setCookies1.size}\n" +
                    "Cookies: ${cookies.keys.joinToString(", ")}\n" +
                    "x-d-token in HTML: ${if (xdFromHtml.isNotEmpty()) "✅ ${xdFromHtml.length}ch" else "❌ not found"}\n\n" +
                    "Preview:\n${body1.take(400)}"
                )

                delay(800)

                // ═══ 2. GET / (الجذر — يجلب reese84) ═══
                withContext(Dispatchers.Main) { currentStep = "2: GET /" }
                val cookieHeader1 = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val resp2 = client.newCall(
                    Request.Builder()
                        .url("https://www.saudia.com/")
                        .header("User-Agent", ua)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7")
                        .apply { if (cookieHeader1.isNotEmpty()) header("Cookie", cookieHeader1) }
                        .get()
                        .build()
                ).execute()

                val code2 = resp2.code
                val setCookies2 = resp2.headers.values("Set-Cookie")
                val body2 = resp2.body?.string() ?: ""
                resp2.close()

                setCookies2.forEach { c ->
                    val first = c.split(";").first().trim()
                    val p = first.split("=", limit = 2)
                    if (p.size == 2) cookies[p[0].trim()] = p[1].trim()
                }

                val xdFromHtml2 = xdRegex.find(body2)?.groupValues?.get(1) ?: ""
                if (xdFromHtml2.isNotEmpty() && xdToken.isEmpty()) xdToken = xdFromHtml2

                addStep(
                    "2. GET /",
                    "HTTP $code2",
                    if (code2 in 200..299) Color(0xFF00C853) else Color(0xFFD32F2F),
                    "Body: ${body2.length} chars\n" +
                    "New Set-Cookie: ${setCookies2.size}\n" +
                    "Total cookies: ${cookies.size}\n" +
                    "Names: ${cookies.keys.joinToString(", ")}\n" +
                    "x-d-token: ${if (xdToken.isNotEmpty()) "✅ ${xdToken.length}ch" else "❌ none"}"
                )

                delay(1500)

                // ═══ 3. POST /token/init مع كل الكوكيز + x-d-token إذا وُجد ═══
                withContext(Dispatchers.Main) { currentStep = "3: POST token/init" }
                val cookieHeader2 = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

                val reqBuilder = Request.Builder()
                    .url("https://dapi.saudia.com/session/auth/web/token/initialization")
                    .header("User-Agent", ua)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", "https://www.saudia.com")
                    .header("Referer", "https://www.saudia.com/ar-SA")
                    .header("platform", "Web")
                    .header("version", "27.2")
                    .apply {
                        if (cookieHeader2.isNotEmpty()) header("Cookie", cookieHeader2)
                        if (xdToken.isNotEmpty()) header("x-d-token", xdToken)
                    }
                    .post("".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))

                val resp3 = client.newCall(reqBuilder.build()).execute()
                val code3 = resp3.code
                val body3 = resp3.body?.string() ?: ""
                resp3.close()

                val hasJWT = body3.contains("access_token")
                addStep(
                    "3. POST token/init",
                    if (hasJWT) "🎉 JWT!" else "HTTP $code3",
                    when {
                        hasJWT -> Color(0xFF00C853)
                        code3 in 200..299 -> Color(0xFFFF6D00)
                        else -> Color(0xFFD32F2F)
                    },
                    "x-d-token sent: ${if (xdToken.isNotEmpty()) "YES (${xdToken.length}ch)" else "NO"}\n" +
                    "Cookies sent: ${cookies.size}\n\n" +
                    "Response:\n${body3.take(600)}"
                )

                // ═══ 4. POST token/init بدون أي شيء (مرجع) ═══
                delay(2000)
                withContext(Dispatchers.Main) { currentStep = "4: POST بدون أي شيء" }
                val resp4 = client.newCall(
                    Request.Builder()
                        .url("https://dapi.saudia.com/session/auth/web/token/initialization")
                        .header("User-Agent", ua)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Origin", "https://www.saudia.com")
                        .header("Referer", "https://www.saudia.com/ar-SA")
                        .post("".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
                        .build()
                ).execute()

                val code4 = resp4.code
                val body4 = resp4.body?.string() ?: ""
                resp4.close()

                addStep(
                    "4. المرجع (بدون شيء)",
                    "HTTP $code4",
                    if (code4 in 200..299) Color(0xFF00C853) else Color(0xFFD32F2F),
                    "Response:\n${body4.take(400)}"
                )

            } catch (e: Exception) {
                Log.e("GetTest", "Error", e)
                addStep(
                    "EXCEPTION",
                    e.javaClass.simpleName,
                    Color(0xFFD32F2F),
                    e.message ?: ""
                )
            } finally {
                withContext(Dispatchers.Main) {
                    currentStep = ""
                    running = false
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "🧪 اختبار فكرة GET",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "GET → كوكيز → POST token/init → JWT?",
            style = MaterialTheme.typography.bodySmall
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(
                "4 خطوات:\n" +
                "1. GET saudia.com/ar-SA (بدون شيء)\n" +
                "2. GET saudia.com/ (يجلب reese84)\n" +
                "3. POST token/init + كوكيز + x-d-token\n" +
                "4. POST token/init بدون شيء (مرجع للمقارنة)",
                modifier = Modifier.padding(10.dp),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Button(
            onClick = { runTest() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onError
                )
                Spacer(Modifier.width(10.dp))
                Text(currentStep, fontSize = 11.sp)
            } else {
                Text("▶ تشغيل الاختبار", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        steps.forEach { step ->
            Card(border = BorderStroke(2.dp, step.color)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            step.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            step.status,
                            color = step.color,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(step.detail, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
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
