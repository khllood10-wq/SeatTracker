package com.example.saudiaseattracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengersScreen(
    navController: NavController,
    viewModel: FlightViewModel
) {
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value
    val rawCartResponse = viewModel.rawCartResponse.collectAsStateWithLifecycle().value.orEmpty()
    val foundFlight = viewModel.foundFlight.collectAsStateWithLifecycle().value
    val confirmResponse = viewModel.passengerConfirmResponse.collectAsStateWithLifecycle().value.orEmpty()
    val context = LocalContext.current

    // 📝 محرر JSON
    var customJson by remember {
        mutableStateOf("""{"passengerTypeCode":"ADT","gender":"male"}""")
    }

    // 🎯 قائمة اختبارات جاهزة (اضغط زر → يتحط الـ JSON في المربع)
    val testCases = remember {
        listOf(
            "✅ 1. (أساس - نجح)" to """{"passengerTypeCode":"ADT","gender":"male"}""",
            "🧪 2. + title" to """{"passengerTypeCode":"ADT","gender":"male","title":"MR"}""",
            "🧪 3. + givenName + surname" to """{"passengerTypeCode":"ADT","gender":"male","givenName":"TEST","surname":"TEST"}""",
            "🧪 4. + firstName + lastName (بديل)" to """{"passengerTypeCode":"ADT","gender":"male","firstName":"TEST","lastName":"TEST"}""",
            "🧪 5. + nationality" to """{"passengerTypeCode":"ADT","gender":"male","givenName":"TEST","surname":"TEST","nationality":"SA"}""",
            "🧪 6. + documents[]" to """{"passengerTypeCode":"ADT","gender":"male","givenName":"TEST","surname":"TEST","documents":[{"type":"NATIONAL_ID","number":"123456789"}]}""",
            "🧪 7. + documentNumber (بديل)" to """{"passengerTypeCode":"ADT","gender":"male","givenName":"TEST","surname":"TEST","documentNumber":"123456789"}""",
            "🎯 8. المجموعة الكاملة" to """{"passengerTypeCode":"ADT","gender":"male","title":"MR","givenName":"TEST","surname":"TEST","dateOfBirth":"1991-05-10","nationality":"SA","documents":[{"type":"NATIONAL_ID","number":"123456789","countryOfIssuance":"SA"}]}"""
        )
    }

    LaunchedEffect(Unit) {
        viewModel.createCartAndProceedToPassenger()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("صفحة المسافرين - اختبار", style = MaterialTheme.typography.headlineMedium)

        foundFlight?.let { f ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("ملخص الحجز:", fontWeight = FontWeight.Bold)
                    Text("الرحلة: ${f.flightNumber} | المسار: ${f.origin} → ${f.destination}")
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (cartId.isNullOrEmpty()) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(" جاري إنشاء السلة...")
            } else {
                Text("✅ cartId: $cartId", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        HorizontalDivider()

        // ═══════════ الأزرار الجاهزة ═══════════
        Text("اختر السيناريو:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

        testCases.forEach { (label, json) ->
            Button(
                onClick = { customJson = json },
                modifier = Modifier.fillMaxWidth(),
                colors = if (label.startsWith("✅")) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                } else {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                }
            ) {
                Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
            }
        }

        HorizontalDivider()

        // ═══════════ محرر JSON ═══════════
        Text("JSON المُرسل:", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = customJson,
            onValueChange = { customJson = it },
            modifier = Modifier.fillMaxWidth().height(180.dp),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        )

        Button(
            onClick = { viewModel.sendRawPassengerPayload(customJson) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !cartId.isNullOrEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("📤 إرسال", fontWeight = FontWeight.Bold)
        }

        // ═══════════ عرض الرد ═══════════
        if (confirmResponse.isNotEmpty()) {
            Text("نتيجة الطلب:", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = confirmResponse,
                        modifier = Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }

        HorizontalDivider()

        // ═══════════ استجابة السلة ═══════════
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("استجابة السلة (JSON):", style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("CartJSON", rawCartResponse))
                    Toast.makeText(context, "تم النسخ!", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text(" نسخ")
            }
        }

        SelectionContainer {
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = rawCartResponse.ifEmpty { "بانتظار الاستجابة..." },
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp
                )
            }
        }
    }
}
