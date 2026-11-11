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

data class Step(
    val name: String,
    val code: Int,
    val body: String
) {
    val isOk get() = code in 200..299
    val color: Color get() = when {
        isOk -> Color(0xFF00C853)
        code == 404 -> Color(0xFF9E9E9E)
        else -> Color(0xFFD32F2F)
    }
    val pnr: String? get() = Regex("\"id\"\\s*:\\s*\"([A-Z0-9]{4,8})\"").find(body)?.groupValues?.get(1)
    val sadad: String? get() = Regex("\"numericId\"\\s*:\\s*\"([0-9]{10,14})\"").find(body)?.groupValues?.get(1)
    val deadline: String? get() = Regex("\"paymentTimeLimit\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderScreen(navController: NavController, viewModel: FlightViewModel) {
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value.orEmpty()
    val clipboard = LocalClipboardManager.current

    var email by remember { mutableStateOf("khllood10@gmail.com") }
    var phone by remember { mutableStateOf("572260016") }
    var lastName by remember { mutableStateOf("Otudi") }

    var steps by remember { mutableStateOf<List<Step>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf("") }
    var success by remember { mutableStateOf(false) }
    var pnr by remember { mutableStateOf("") }
    var sadad by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf("") }

    fun addStep(s: Step) { steps = steps + s }

    fun runFlow() {
        if (running || cartId.isEmpty()) return
        running = true
        steps = emptyList()
        success = false
        pnr = ""; sadad = ""; deadline = ""

        viewModel.viewModelScope.launch {
            // ─── Step 1: YQENSTATUS extension ───
            currentStep = "1/4 — YQENSTATUS"
            try {
                val extBody = """[{"content":"eyJzdGF0dXMiOiJWRVJJRklFRCIsImhlYWx0aENoZWNrU3RhdHVzIjoiTk9fUkVTUE9OU0UifQ==","travelerId":"SKH-1-EXT","extensionType":"TextExtension","name":"YQENSTATUS"}]"""
                val resp = RetrofitClient.api.addCartExtension(
                    cartId = cartId,
                    guestOfficeId = "JEDSV08AA",
                    headers = viewModel.buildHeadersPublic("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
                    request = extBody.toRequestBody("application/json".toMediaType())
                )
                val body = resp.body()?.string() ?: resp.errorBody()?.string() ?: ""
                addStep(Step("1. YQENSTATUS", resp.code(), body))
                if (!resp.isSuccessful) { currentStep = "❌ فشل 1"; running = false; return@launch }
            } catch (e: Exception) {
                addStep(Step("1. exception", -1, e.message ?: "")); running = false; return@launch
            }
            delay(800)

            // ─── Step 2: Contacts ───
            currentStep = "2/4 — Contacts"
            try {
                val contactsBody = """[{"address":"$email","category":"personal","contactType":"Email","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"address":"$email","category":"personal","contactType":"Email","purpose":"standard"},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"$phone","deviceType":"mobile","purpose":"notification","lang":"ar","travelerIds":["SKH-1-EXT"]},{"category":"personal","contactType":"Phone","countryPhoneExtension":"+966","number":"$phone","deviceType":"mobile","purpose":"standard"}]"""
                val resp = RetrofitClient.api.saveContacts(
                    cartId = cartId,
                    headers = viewModel.buildHeadersPublic("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
                    request = contactsBody.toRequestBody("application/json".toMediaType())
                )
                val body = resp.body()?.string() ?: resp.errorBody()?.string() ?: ""
                addStep(Step("2. contacts", resp.code(), body))
                if (!resp.isSuccessful) { currentStep = "❌ فشل 2"; running = false; return@launch }
            } catch (e: Exception) {
                addStep(Step("2. exception", -1, e.message ?: "")); running = false; return@launch
            }
            delay(800)

            // ─── Step 3: Create order (no /web) ───
            currentStep = "3/4 — إنشاء الطلب"
            var orderId: String? = null
            try {
                val resp = RetrofitClient.api.createOrderRaw(
                    cartId = cartId,
                    headers = viewModel.buildHeadersPublic("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
                    request = "{}".toRequestBody("application/json".toMediaType())
                )
                val body = resp.body()?.string() ?: resp.errorBody()?.string() ?: ""
                val step = Step("3. create order", resp.code(), body)
                addStep(step)
                if (step.isOk) {
                    orderId = step.pnr
                    pnr = step.pnr ?: ""
                    sadad = step.sadad ?: ""
                    deadline = step.deadline ?: ""
                } else {
                    currentStep = "❌ فشل 3 — لا يمكن إنشاء الطلب"; running = false; return@launch
                }
            } catch (e: Exception) {
                addStep(Step("3. exception", -1, e.message ?: "")); running = false; return@launch
            }
            delay(800)

            if (orderId.isNullOrEmpty()) { currentStep = "❌ لم نستلم PNR"; running = false; return@launch }

            // ─── Step 4: Submit SADAD payment (بدون تشفير!) ───
            currentStep = "4/4 — إرسال طلب السداد"
            try {
                val payBody = """{
                    "orderPaymentRequest": {
                        "paymentRequests": [{
                            "paymentMethod": {"paymentType": "CustomPayment", "id": "SADAD"}
                        }]
                    },
                    "emailParameters": {
                        "displayParameters": {
                            "alharamain": true, "baggage": true, "bookingCom": true,
                            "eatmarna": false, "electronicVisa": false, "plusgrade": true,
                            "rental": true, "seat": true, "wifi": true, "onDemandTeasers": []
                        }
                    },
                    "isCardholderAbsent": false,
                    "isQueuePlacementEnabled": false
                }"""
                val resp = RetrofitClient.api.submitPayment(
                    orderId = orderId!!,
                    lastName = lastName,
                    headers = viewModel.buildHeadersPublic("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
                    request = payBody.toRequestBody("application/json".toMediaType())
                )
                val body = resp.body()?.string() ?: resp.errorBody()?.string() ?: ""
                addStep(Step("4. submit SADAD payment", resp.code(), body))
                if (resp.isSuccessful) {
                    success = true
                    currentStep = "🎉 نجح! الدفع مُقدّم — اذهب للبنك"
                } else {
                    currentStep = "❌ فشل 4 — الدفع لم يُقبل"
                }
            } catch (e: Exception) {
                addStep(Step("4. exception", -1, e.message ?: ""))
                currentStep = "❌ استثناء 4"
            }
            running = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("💳 إتمام الحجز — SADAD", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "4 خطوات بدون تشفير: extension → contacts → order → payment",
            style = MaterialTheme.typography.bodySmall
        )

        if (cartId.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("❌ لا يوجد cartId", Modifier.padding(12.dp))
            }
            return@Column
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text("cartId: $cartId", fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
        }

        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("البريد", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )
        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text("الجوال (بدون 966)", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )
        OutlinedTextField(
            value = lastName, onValueChange = { lastName = it },
            label = { Text("اسم العائلة (للدفع)", fontSize = 11.sp) },
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
                Text("▶ بدء الحجز الكامل", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        // Success card
        if (success) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                border = BorderStroke(3.dp, Color(0xFF00C853))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("🎉 نجح الحجز!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00C853))
                    Spacer(Modifier.height(8.dp))
                    if (pnr.isNotEmpty()) {
                        Row {
                            Text("PNR: ", fontWeight = FontWeight.Bold)
                            Text(pnr, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Color(0xFF00C853))
                        }
                    }
                    if (sadad.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Text("SADAD: ", fontWeight = FontWeight.Bold)
                            Text(sadad, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Color(0xFF00C853))
                        }
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { clipboard.setText(AnnotatedString(sadad)) }, modifier = Modifier.fillMaxWidth()) {
                            Text("📋 نسخ رقم سداد")
                        }
                    }
                    if (deadline.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Deadline: $deadline", fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("⚠️ اذهب للبنك الآن", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("ادفع رقم SADAD قبل انتهاء المهلة. بعد الدفع يصل إيميل + التذكرة تلقائياً.", fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // Steps
        if (steps.isNotEmpty()) {
            Text("الخطوات (${steps.size}/4):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            steps.forEach { s ->
                Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(2.dp, s.color)) {
                    Column(Modifier.padding(8.dp)) {
                        Row {
                            Text(s.name, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("HTTP ${s.code}", color = s.color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        if (s.pnr != null) Text("🆔 PNR: ${s.pnr}", fontSize = 11.sp, color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
                        if (s.sadad != null) Text("💳 SADAD: ${s.sadad}", fontSize = 11.sp, color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
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
