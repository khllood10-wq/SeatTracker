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
import androidx.navigation.NavController
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// ⭐ القيم الجديدة من السجل الأخير
private const val CAPTURED_CART_ID       = "1VPQ4KM07H2KZRXV"
private const val CAPTURED_TRAVELER_ID   = "SKH-1-EXT"
private const val CAPTURED_GUEST_OFFICE  = "JEDSV08AA"  // ← مهم: A وليس M
private const val CAPTURED_KEY = "oYn5LZBjBV+atLf5pBQnaOKhFQHnQe66fHu4Bs+HcIyY1noMy7bRr3rVuK0w+vzVldkE0Nd8q7vbijEBPEXbQ8w5fOhi3UkRGEzCEp8uLW2cwbmcjfUVmhMMoSI0ertHqhGB4fRYlVD69u2wgF/UmYHlBbglpjppSNlFDbIOP++cNu3hSSOY8m3y9wLu0tTiv0bJAObwIcGuwKJR1A7Qa1JQCk3xNjLdwRFRb7XRvtTGyD4JC3TV7YJzlz5Q0UXKVg8E3Kxf/DqTNGmDH9rbkINnMx9hcT8ObFUnjcMIEp9UjCJabg1CPlv8AtEpYDAPPQH7qmAUnCoFvPmDrRAg3WHRDrg99tM0xLJxEXmZuqgKVD9jgIFTQquW8j0NKX+wBbhdNHQ583RnMBSkiM08aAbfBBdQVd3s/oKnMlbXVBGqOVh1ZUzD1Y0eWZYw4kW/hiA4b0JZ3t1hg3eWvguax/1DeOcOS8Wp33RxNQb39jzyI4G1gP7gPRQz23lTSQnliBZz7lmubsUwoN0GCyJTTUn71BSUbYPOQEyMdUEuwySOSuzhj1y8niS9I36W9diGU3GvEYiiQ/2mmz8/0D2ltMgqZsn6DcA9nuEPxzhMf9mTnY2AjTgw0HzKOz3aDLdJcHADAnkIJpJQOJjc8vEbrHTmzIlYIdwSgPbOxyamgAs="
private const val CAPTURED_BODY = "d879afecfa689e4f7bec17f140e18ae17de94c188c1e7bd4b11a2f75fae7f2942768f4907347947f6b0bf45387072d6d0dbf36405a449c87a85a7a5b9282aacd8ee630c639b41d8c63e1b7945c6c58f1cfbefd63f03f94d29c6962965a4a7d2a8aefe64230091a527a1cd33f7dd74af57820a914c5aaa506173b848278ab6dd575acdf56dd6e7f434838d641f6fb0418ec8fcf98b24857817372ebc5f9ea48abb0cb7379c4ae1e448f31dec8d986aafb5fb4468cb7e8f00768166ad0a67463bdcfeb1465efa9dc9e1d0902766bcb6c4c91e740313db9bdcd534c56913ae1e57574ed55101c4fc712d7fa07adc9d0d7d0ae9a7970f614597d1083a6633fed2d829eaffa246224042077253fc6fa4af3d0640750437475a9636ae4ff53673173be48575c19d5713e640f7f9e6eb2faba1ba3cd597de9d0cb3da977accef888d290f979691eb07efbac1cfdee1a957a85f4e3675ec2e710526f6488a22677347a9535746595470c43a6e1b06400c1d57baff8b7d00b0fa7111f37dfbb5fc0d73803ee857fac3cb39be129ea950ef1a38cadd96f60268cc2dd5aea1231879ad14edad4b50ada72bfc6ed410fb11285be7c5b32c7742a8440e33c27afdd86b8dc019ef2ba92ed299244e5efe869bf1817b438821686cfa83b3c2fe738c6dcc760bac0afc68832fa7219c5fbf5016e82739683"

data class ReplayResult(
    val testName: String,
    val cartId: String,
    val httpCode: Int,
    val body: String
) {
    val color: Color get() = when {
        httpCode == 200 -> Color(0xFF00C853)
        httpCode == 404 -> Color(0xFF9E9E9E)
        httpCode == 403 -> Color(0xFFD50000)
        httpCode in 400..499 -> Color(0xFFFF6D00)
        else -> Color(0xFF1976D2)
    }
    val verdict: String get() = when {
        httpCode == 200 -> "🎉 نجح — السيرفر قبـل الطلب! Imperva ليست العائق"
        httpCode == 403 -> "🔴 Imperva يحظر Retrofit — WebView إجباري"
        httpCode == 404 -> "⚠️ السلة انتهت صلاحيتها (غير حاسم)"
        body.contains("passengerTypeCode") -> "❌ السيرفر لم يفك التشفير (Imperva يحجب)"
        body.contains("uuid") || body.contains("cartId") -> "🟠 التشفير فُكّ لكن uuid لا يطابق"
        body.contains("extension") || body.contains("missing") -> "🟠 السلة ناقصة — تحتاج خطوات إضافية"
        else -> "⚠️ رد آخر — راجع التفاصيل"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayScreen(navController: NavController, viewModel: FlightViewModel) {
    var results by remember { mutableStateOf<List<ReplayResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    fun runTests() {
        if (running) return
        running = true
        results = emptyList()

        viewModel.viewModelScope.launch {
            val payload = """{"key":"$CAPTURED_KEY","body":"$CAPTURED_BODY"}"""

            try {
                val resp = RetrofitClient.api.updateTravelerRaw(
                    cartId = CAPTURED_CART_ID,
                    travelerId = CAPTURED_TRAVELER_ID,
                    guestOfficeId = CAPTURED_GUEST_OFFICE,  // ← A وليس M
                    headers = viewModel.buildHeadersPublic("2",
                        "https://www.saudia.com/ar-SA/booking/passengerDetails"),
                    request = payload.toRequestBody("application/json".toMediaType())
                )
                val body = resp.body()?.string() ?: resp.errorBody()?.string() ?: "(empty)"
                results = results + ReplayResult(
                    "T-A (guestOfficeId=JEDSV08AA)",
                    CAPTURED_CART_ID,
                    resp.code(),
                    body
                )
            } catch (e: Exception) {
                results = results + ReplayResult(
                    "T-A (exception)",
                    CAPTURED_CART_ID,
                    -1,
                    e.message ?: ""
                )
            }

            running = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🧪 اختبار Replay", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "إرسال {key, body} الحقيقيين إلى cartId الحقيقي بـ guestOfficeId=JEDSV08AA",
            style = MaterialTheme.typography.bodySmall
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("cartId: $CAPTURED_CART_ID", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text("guestOfficeId: $CAPTURED_GUEST_OFFICE", fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("key: ${CAPTURED_KEY.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text("body: ${CAPTURED_BODY.length} حرف", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }

        Button(
            onClick = { runTests() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("جاري الإرسال...")
            } else {
                Text("▶ اختبار الآن (قبل انتهاء السلة)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        results.forEach { r ->
            Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(3.dp, r.color)) {
                Column(Modifier.padding(12.dp)) {
                    Text(r.testName, fontWeight = FontWeight.Bold)
                    Text("HTTP ${r.httpCode}", fontWeight = FontWeight.Bold,
                        color = r.color, fontSize = 20.sp)
                    Text(r.verdict, color = r.color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(r.body.take(500), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("← رجوع") }
    }
}
