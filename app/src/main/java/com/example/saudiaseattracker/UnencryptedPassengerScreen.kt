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
        code == 200 -> Color(0xFF00C853)
        code == 404 -> Color(0xFF9E9E9E)
        code in 400..499 -> Color(0xFFFF6D00)
        else -> Color(0xFFD32F2F)
    }
    val verdict: String get() = when {
        code == 200 -> "🎉 نجح! السيرفر قبـل البيانات كاملة"
        code == 404 -> "⚠️ السلة انتهت"
        body.contains("dateOfBirth") -> "🟠 تاريخ الميلاد — تحقق من الصيغة"
        body.contains("firstName") -> "🟠 firstName مطلوب"
        body.contains("lastName") -> "🟠 lastName مطلوب"
        body.contains("nationality") -> "🟠 nationality مطلوب"
        body.contains("travellerDoc") -> "🟠 travellerDoc مطلوب"
        body.contains("Unexpected") -> "❌ حقل مرفوض"
        else -> "⚠️ راجع التفاصيل"
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

            // ⭐ التصحيح: dateOfBirth بصيغة YYYY-MM-DD فقط
            val dob = "1991-05-10"

            val tests = listOf(
                "U1: 3 حقول + date صحيح" to
                    """{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"$dob"}""",

                "U2: U1 (array)" to
                    """[{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"$dob"}]""",

                "U3: names array" to
                    """[{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"$dob","names":[{"firstName":"Khaled","lastName":"Otudi","title":"Mr","nameType":"universal"}]}]""",

                "U4: flat names" to
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

                    // إذا نجح أحد الاختبارات، توقف
                    if (resp.code() == 200) break
                } catch (e: Exception) {
                    results = results + TestResult(name, -1, e.message ?: "exception")
                }
                delay(1800)
            }
            currentTest = ""
            running = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("🔓 اختبار بدون تشفير (v2)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "dateOfBirth بصيغة YYYY-MM-DD — نتوقف عند أول 200",
            style = MaterialTheme.typography.bodySmall
        )

        if (cartId.isNullOrEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("❌ لا يوجد cartId", Modifier.padding(12.dp))
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(10.dp)) {
                    Text("cartId: $cartId", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("التاريخ المرسل: 1991-05-10", fontSize = 11.sp)
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
                    Text("▶ تشغيل (يتوقف عند أول نجاح)", fontWeight = FontWeight.Bold)
                }
            }

            results.forEach { r ->
                Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(2.dp, r.color)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(r.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("HTTP ${r.code}", color = r.color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(r.verdict, color = r.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(r.body.take(350), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
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
}
