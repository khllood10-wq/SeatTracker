package com.example.saudiaseattracker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// ⭐ Presets كلها Object الآن (بدون [ ])
private val PRESETS: Map<String, String> = mapOf(
    "V1" to """{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"1991-05-10"}""",
    "V2" to """{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"1991-05-10","firstName":"Khaled","lastName":"Otudi","title":"Mr"}""",
    "V3" to """{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"1991-05-10","names":[{"firstName":"Khaled","lastName":"Otudi","title":"Mr","nameType":"universal"}]}""",
    "V4" to """{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"1991-05-10","firstName":"Khaled","lastName":"Otudi","title":"Mr","nationality":"SA"}""",
    "V5" to """{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"1991-05-10","firstName":"Khaled","lastName":"Otudi","title":"Mr","nationality":"SA","travellerDoc":{"docType":"National ID","nationalId":"1071580342","number":"1071580342"}}""",
    "V6" to """{"tid":null,"id":"SKH-1-EXT","title":"Mr","passengerTypeCode":"ADT","firstName":"Khaled","lastName":"Otudi","dateOfBirth":"1991-05-10","nationality":"SA","rememberMe":true,"uuid":"__UUID__","travellerDoc":{"docType":"National ID","passportNumber":"","expiryDate":"","issuingCountry":"","nationalId":"1071580342","gccId":"","residentId":"","number":"1071580342","id":null}}""",
    "V7" to """{"passengerTypeCode":"ADT","gender":"male","dateOfBirth":"1991-05-10","names":[{"firstName":"Khaled","lastName":"Otudi","title":"Mr","nameType":"universal"}],"nationality":"SA","travellerDoc":{"docType":"National ID","nationalId":"1071580342","number":"1071580342"}}"""
)

data class TestResult(
    val name: String,
    val code: Int,
    val body: String
) {
    val hasName: Boolean
        get() = body.contains("names") || body.contains("firstName") ||
                body.contains("Khaled") || body.contains("KHALED")
    val hasDoc: Boolean
        get() = body.contains("travellerDoc") || body.contains("nationalId") ||
                body.contains("documentType") || body.contains("1071580342")
    val color: Color
        get() = when {
            code == 200 && hasName && hasDoc -> Color(0xFF00C853)
            code == 200 && hasName -> Color(0xFF66BB6A)
            code == 200 -> Color(0xFFFFAB00)
            code in 400..499 -> Color(0xFFFF6D00)
            else -> Color(0xFFD32F2F)
        }
    val verdict: String
        get() = when {
            code == 200 && hasName && hasDoc -> "🎉🎉 اسم + هوية محفوظان"
            code == 200 && hasName -> "🎉 الاسم محفوظ (بلا هوية)"
            code == 200 -> "✅ 200 لكن بدون اسم"
            body.contains("should be object") -> "🟠 أرسلت Array بدل Object"
            body.contains("dateOfBirth") -> "🟠 تاريخ"
            body.contains("Unexpected") -> "❌ حقل مرفوض"
            else -> "❌ ${body.substringAfter("\"message\":\"", "").substringBefore("\"").take(30)}"
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnencryptedPassengerScreen(navController: NavController, viewModel: FlightViewModel) {
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value.orEmpty()
    val travelerId = "SKH-1-EXT"
    val uuid = "$cartId$travelerId"

    // ⭐ الحقل فارغ من البداية
    var payloadText by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TestResult>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }

    fun send() {
        if (sending || cartId.isEmpty() || payloadText.isBlank()) return
        sending = true
        viewModel.viewModelScope.launch {
            try {
                val resp = RetrofitClient.api.updateTravelerRaw(
                    cartId = cartId,
                    travelerId = travelerId,
                    headers = viewModel.buildHeadersPublic(
                        "2",
                        "https://www.saudia.com/ar-SA/booking/passengerDetails"
                    ),
                    request = payloadText.toRequestBody("application/json".toMediaType())
                )
                val body = resp.body()?.string() ?: resp.errorBody()?.string() ?: "(فارغ)"
                val firstLine = payloadText.take(40).replace("\n", "")
                results = listOf(TestResult(firstLine, resp.code(), body)) + results
            } catch (e: Exception) {
                results = listOf(
                    TestResult(payloadText.take(40), -1, e.message ?: "err")
                ) + results
            }
            sending = false
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
            "🧪 مختبر Payload",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "الصق JSON (Object فقط) → اضغط إرسال. أو اضغط Preset لملء الحقل.",
            style = MaterialTheme.typography.bodySmall
        )

        if (cartId.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("❌ لا يوجد cartId", Modifier.padding(12.dp))
            }
            return@Column
        }

        // ── معلومات السلة ──
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(8.dp)) {
                Text("cartId: $cartId", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text("uuid: $uuid", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }

        // ── Presets أفقية ──
        Text("Presets (Objects):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PRESETS.keys.forEach { name ->
                AssistChip(
                    onClick = {
                        payloadText = PRESETS[name]!!.replace("__UUID__", uuid)
                    },
                    label = { Text(name, fontSize = 11.sp) }
                )
            }
        }

        // ── حقل التحرير (فارغ من البداية) ──
        OutlinedTextField(
            value = payloadText,
            onValueChange = { payloadText = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 280.dp),
            label = { Text("الصق JSON هنا", fontSize = 11.sp) },
            placeholder = { Text("{\"passengerTypeCode\":\"ADT\",...}", fontSize = 10.sp) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        )

        // ── زر إرسال واحد ──
        Button(
            onClick = { send() },
            enabled = !sending && payloadText.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onError
                )
                Spacer(Modifier.width(10.dp))
                Text("جاري الإرسال...")
            } else {
                Text("📤 إرسال", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // ── النتائج ──
        if (results.isNotEmpty()) {
            Text("النتائج (${results.size}):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            results.forEach { r ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(2.dp, r.color)
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                r.name,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "HTTP ${r.code}",
                                color = r.color,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        Text(r.verdict, color = r.color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        if (r.code == 200) {
                            Row {
                                if (r.hasName) Text("👤 اسم ✓  ", fontSize = 9.sp, color = Color(0xFF00C853))
                                if (r.hasDoc) Text("📄 هوية ✓", fontSize = 9.sp, color = Color(0xFF00C853))
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        SelectionContainer {
                            Text(
                                r.body.take(500),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp
                            )
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
