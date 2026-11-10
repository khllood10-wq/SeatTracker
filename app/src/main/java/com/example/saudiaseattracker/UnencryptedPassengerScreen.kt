package com.example.saudiaseattracker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class TestResult(
    val name: String,
    val code: Int,
    val body: String
) {
    val color: Color get() = when {
        code == 200 && (body.contains("names") || body.contains("firstName")) -> Color(0xFF00C853)
        code == 200 -> Color(0xFFFFAB00)  // نجح لكن بدون اسم
        code in 400..499 -> Color(0xFFFF6D00)
        else -> Color(0xFFD32F2F)
    }
    val hasName: Boolean get() = body.contains("names") || body.contains("firstName") || body.contains("KHALED")
    val hasDoc: Boolean get() = body.contains("travellerDoc") || body.contains("nationalId") || body.contains("documentType")
    val verdict: String get() = when {
        code == 200 && hasName && hasDoc -> "🎉🎉 نجح! الاسم + الهوية محفوظان"
        code == 200 && hasName -> "🎉 نجح! الاسم محفوظ (بلا هوية)"
        code == 200 -> "✅ نجح لكن بدون اسم/هوية"
        code in 400..499 -> "❌ ${body.substringAfter("message\":\"").substringBefore("\"")}"
        else -> "⚠️ خطأ"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnencryptedPassengerScreen(navController: NavController, viewModel: FlightViewModel) {
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value
    var results by remember { mutableStateOf<List<TestResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var currentTest by remember { mutableStateOf("") }

    fun runTests() {
        if (running || cartId.isNullOrEmpty()) return
        running = true
        results = emptyList()

        viewModel.viewModelScope.launch {
            val travelerId = "SKH-1-EXT"
            val uuid = "$cartId$travelerId"
            val dob = "1991-05-10"

            // U1 نجح مسبقاً — نجرب الآن U3-U7 (تحتوي على الاسم والهوية)
            val tests = listOf(
                "U3: names array" to
                    """[{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"$dob","names":[{"firstName":"Khaled","lastName":"Otudi","title":"Mr","nameType":"universal"}]}]""",

                "U4: flat firstName/lastName" to
                    """[{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"$dob","firstName":"Khaled","lastName":"Otudi","title":"Mr"}]""",

                "U5: + nationality" to
                    """[{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"$dob","firstName":"Khaled","lastName":"Otudi","title":"Mr","nationality":"SA"}]""",

                "U6: + travellerDoc" to
                    """[{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"$dob","firstName":"Khaled","lastName":"Otudi","title":"Mr","nationality":"SA","travellerDoc":{"docType":"National ID","nationalId":"1071580342","number":"1071580342"}}]""",

                "U7: full /web format" to
                    """[{"tid":null,"id":"$travelerId","title":"Mr","passengerTypeCode":"ADT","firstName":"Khaled","lastName":"Otudi","dateOfBirth":"$dob","nationality":"SA","rememberMe":true,"uuid":"$uuid","travellerDoc":{"docType":"National ID","passportNumber":"","expiryDate":"","issuingCountry":"","nationalId":"1071580342","gccId":"","residentId":"","number":"1071580342","id":null}}]"""
            )

            for ((name, payload) in tests) {
                currentTest = name
                try {
                    val body = payload.toRequestBody("application/json".toMediaType())
                    val resp = RetrofitClient.api.updateTravelerRaw(
                        cartId = cartId,
                        travelerId = travelerId,
                        headers = viewModel.buildHeadersPublic(
                            "2",
                            "https://www.saudia.com/ar-SA/booking/passengerDetails"
                        ),
                        request = body
                    )
                    val respBody = resp.body()?.string() ?: resp.errorBody()?.string() ?: "(فارغ)"
                    results = results + TestResult(name, resp.code(), respBody)
                } catch (e: Exception) {
                    results = results + TestResult(name, -1, e.message ?: "exception")
                }
                delay(1500)
            }
            currentTest = ""
            running = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("🔓 اختبار الاسم + الهوية", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "U1 نجح! الآن نجرب U3–U7 لإيجاد الشكل الذي يقبل الاسم والهوية",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )

        if (cartId.isNullOrEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("❌ لا يوجد cartId", Modifier.padding(12.dp))
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(10.dp)) {
                    Text("cartId: $cartId", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("البيانات: Khaled Otudi / 1071580342 / 1991-05-10 / SA / National ID", fontSize = 10.sp)
                }
            }

            Button(
                onClick = { runTests() },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("جاري: $currentTest", fontSize = 11.sp)
                } else {
                    Text("▶ اختبار U3 → U7", fontWeight = FontWeight.Bold)
                }
            }

            results.forEach { r ->
                Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(2.dp, r.color)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(r.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("HTTP ${r.code}", color = r.color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            if (r.code == 200) {
                                Spacer(Modifier.width(10.dp))
                                if (r.hasName) Text("👤 اسم ✓", fontSize = 11.sp, color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
                                if (r.hasDoc) Text("📄 هوية ✓", fontSize = 11.sp, color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(r.verdict, color = r.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(r.body.take(500), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        }
                    }
                }
            }

            if (results.any { it.code == 200 && it.hasName }) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    border = BorderStroke(3.dp, Color(0xFF00C853))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("🎉 وجدنا الشكل الصحيح!", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "الشكل: ${results.first { it.code == 200 && it.hasName }.name}",
                            fontSize = 12.sp
                        )
                        Text(
                            "أرسل هذه الرسالة كاملة إلى المساعد لإكمال الحل النهائي.",
                            fontSize = 11.sp
                        )
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
}
