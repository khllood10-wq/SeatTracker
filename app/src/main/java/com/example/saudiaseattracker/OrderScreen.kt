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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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

data class OrderStep(
    val name: String,
    val code: Int,
    val body: String
) {
    val isOk get() = code in 200..299
    val color: Color get() = when {
        code in 200..299 -> Color(0xFF00C853)
        code == 404 -> Color(0xFF9E9E9E)
        else -> Color(0xFFD32F2F)
    }
    val pnr: String?
        get() = if (isOk) Regex("\"id\"\\s*:\\s*\"([A-Z0-9]{4,8})\"").find(body)?.groupValues?.get(1) else null
    val sadadBill: String?
        get() = if (isOk) Regex("\"numericId\"\\s*:\\s*\"([0-9]{10,14})\"").find(body)?.groupValues?.get(1) else null
    val deadline: String?
        get() = Regex("\"paymentTimeLimit\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderScreen(navController: NavController, viewModel: FlightViewModel) {
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value.orEmpty()
    val clipboard = LocalClipboardManager.current

    var email by remember { mutableStateOf("khllood10@gmail.com") }
    var phone by remember { mutableStateOf("572260016") }

    var steps by remember { mutableStateOf<List<OrderStep>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf("") }
    var orderSuccess by remember { mutableStateOf(false) }
    var pnr by remember { mutableStateOf("") }
    var sadad by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf("") }

    fun addStep(s: OrderStep) { steps = steps + s }

    fun runFlow() {
        if (running || cartId.isEmpty()) return
        running = true
        steps = emptyList()
        orderSuccess = false
        pnr = ""; sadad = ""; deadline = ""

        viewModel.viewModelScope.launch {
            // ═══════════════════════════════════════════
            // Step 1: YQENSTATUS extension (بدون تشفير)
            // ═══════════════════════════════════════════
            currentStep = "1/3 — حفظ التفعيل"
            try {
                val extBody = """[{"content":"eyJzdGF0dXMiOiJWRVJJRklFRCIsImhlYWx0aENoZWNrU3RhdHVzIjoiTk9fUkVTUE9OU0UifQ==","travelerId":"SKH-1-EXT","extensionType":"TextExtension","name":"YQENSTATUS"}]"""
                val resp = RetrofitClient.api.addCartExtension(
                    cartId = cartId,
                    guestOfficeId = "JEDSV08AA",
                    headers = viewModel.buildHeadersPublic("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
                    request = extBody.toRequestBody("application/json".toMediaType())
                )
                val body = resp.body()?.string() ?: resp.errorBody()?.string() ?: ""
                addStep(OrderStep("1. YQENSTATUS extension", resp.code(), body))
                if (!resp.isSuccessful) {
                    currentStep = "❌ فشل 1"
                    running = false
                    return@launch
                }
            } catch (e: Exception) {
                addStep(OrderStep("1. exception", -1, e.message ?: ""))
                running = false
                return@launch
            }
            delay(800)

            // ═══════════════════════════════════════════
            // Step 2: Contacts (بدون تشفير)
            // ═══════════════════════════════════════════
            currentStep = "2/3 — حفظ الاتصال"
            try {
                val contactsBody = """[
                    {"address":"$email","category":"personal","contactType":"Email","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},
                    {"address":"$email","category":"personal","contactType":"Email","purpose":"standard"},
                    {"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"$phone","deviceType":"mobile","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},
                    {"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"$phone","deviceType":"mobile","purpose":"standard"}
                ]"""
                val resp = RetrofitClient.api.saveContacts(
                    cartId = cartId,
                    headers = viewModel.buildHeadersPublic("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
                    request = contactsBody.toRequestBody("application/json".toMediaType())
                )
                val body = resp.body()?.string() ?: resp.errorBody()?.string() ?: ""
                addStep(OrderStep("2. contacts", resp.code(), body))
                if (!resp.isSuccessful) {
                    currentStep = "❌ فشل 2"
                    running = false
                    return@launch
                }
            } catch (e: Exception) {
                addStep(OrderStep("2. exception", -1, e.message ?: ""))
                running = false
                return@launch
            }
            delay(800)

            // ═══════════════════════════════════════════
            // Step 3: /purchase/orders (بدون /web) — 5 أشكال
            // ═══════════════════════════════════════════
            val payloads = listOf(
                "3.1 {}" to """{}""",
                "3.2 paymentMethod" to """{"paymentMethod":"SADAD"}""",
                "3.3 payment" to """{"payment":{"method":"SADAD"}}""",
                "3.4 with cartId" to """{"cartId":"$cartId","paymentMethod":"SADAD"}""",
                "3.5 SADAD type" to """{"paymentMethod":"SADAD","paymentMethodType":"SADAD"}"""
            )

            for ((name, payload) in payloads) {
                currentStep = name
                try {
                    val resp = RetrofitClient.api.createOrderRaw(
                        cartId = cartId,
                        headers = viewModel.buildHeadersPublic("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
                        request = payload.toRequestBody("application/json".toMediaType())
                    )
                    val body = resp.body()?.string() ?: resp.errorBody()?.string() ?: ""
                    val step = OrderStep(name, resp.code(), body)
                    addStep(step)

                    if (step.isOk && (step.pnr != null || step.sadadBill != null)) {
                        orderSuccess = true
                        pnr = step.pnr ?: ""
                        sadad = step.sadadBill ?: ""
                        deadline = step.deadline ?: ""
                        currentStep = "🎉 نجح! PNR=$pnr"
                        running = false
                        return@launch
                    }
                } catch (e: Exception) {
                    addStep(OrderStep(name + " (exc)", -1, e.message ?: ""))
                }
                delay(1500)
            }
            currentStep = "انتهى — كل الأشكال فشلت"
            running = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("💳 إتمام الحجز — SADAD", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "يجرب /purchase/orders (بدون /web) بـ 5 أشكال. إذا نجا أي منها، يصلك PNR + رقم سداد فوراً.",
            style = MaterialTheme.typography.bodySmall
        )

        if (cartId.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("❌ لا يوجد cartId", Modifier.padding(12.dp))
            }
            return@Column
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(10.dp)) {
                Text("cartId: $cartId", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("البريد الإلكتروني", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("رقم الجوال (بدون 966)", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )

        Button(
            onClick = { runFlow() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onTertiary)
                Spacer(Modifier.width(10.dp))
                Text(currentStep, fontSize = 11.sp)
            } else {
                Text("▶ بدء إتمام الحجز", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        if (orderSuccess) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                border = BorderStroke(3.dp, Color(0xFF00C853))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("🎉 تم الحجز!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00C853))
                    Spacer(Modifier.height(8.dp))
                    if (pnr.isNotEmpty()) {
                        Row {
                            Text("PNR: ", fontWeight = FontWeight.Bold)
                            Text(pnr, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Color(0xFF00C853))
                        }
                    }
                    if (sadad.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text("رقم سداد: ", fontWeight = FontWeight.Bold)
                            Text(sadad, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Color(0xFF00C853))
                        }
                    }
                    if (deadline.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("آخر موعد للدفع: $deadline", fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(sadad)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📋 نسخ رقم سداد") }
                }
            }
        }

        if (steps.isNotEmpty()) {
            Text("الخطوات (${steps.size}):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            steps.forEach { s ->
                Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(2.dp, s.color)) {
                    Column(Modifier.padding(8.dp)) {
                        Row {
                            Text(s.name, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("HTTP ${s.code}", color = s.color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        if (s.pnr != null) Text("🆔 PNR: ${s.pnr}", fontSize = 11.sp, color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
                        if (s.sadadBill != null) Text("💳 SADAD: ${s.sadadBill}", fontSize = 11.sp, color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        SelectionContainer {
                            Text(s.body.take(500), fontFamily = FontFamily.Monospace, fontSize = 8.sp)
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
