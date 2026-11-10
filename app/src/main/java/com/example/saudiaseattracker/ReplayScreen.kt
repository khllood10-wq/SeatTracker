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

// ── القيم الملتقطة من المتصفح (طلب ناجح سابقاً) ──
private const val CAPTURED_CART_ID     = "1K51LG7NXP7LDV4K"
private const val CAPTURED_TRAVELER_ID = "SKH-1-EXT"
private const val CAPTURED_KEY = "HF7dpAZBrU2QoqRt6P3IPasqy8+mX+KwbVspc01DTtoWPw+jhu3jxhG6mEmLcfoDgAYBuzIfevD6/zG4iV07XYmUWhz3dyQkxkmPwvgRP4MI1M3gBUJWyB5S+xt7KzqyVk8MO8rkmCsVNGsjW84GsUOBu1wa/IaK32Y1HgA0QX0gPV2MRohEka5AM52l4A9irJHQuR5mB2kgXSOZl93AxikEb6yeorqoHnXV4ZEZYwCsh11Caopjk2H5bjK5n0fxxgM2xlAFFWtCZPNnHNXU3FJxAN0AXvvfz4gGOu1PmytUNx4G+SVLiHnSg7FI3fmPvxC2Qk/hOQLd+mcIV1n2hfW3pV1r5w2ycIjFCW1ocR0SKOd50skI4ZDqUWO/Z2RJJtRSOC3suMkMBogmMCFVZQhSThPdMgq6VOOjO8KxTiH5H1slu0hciocBlu+OqkBWIOObXD6R7JwmdvsXI0WRQyhMoJ/JGUi15iyE5JMmarnclg5N5g45LxyAfbHavaNc1Vu1RcKH8oLJM2JGgq5N8h6rdKmOY43bvy9G46Runzzo4C7vkUcfB/o+EqTxs5+fEGIoZ713cet56vBWSKrlASlzwn7coIzo59r+XKhvxBBthYi9NeRiE6i2+qqvC9kia2IEOCA0KfSMV0vrDdl/rIoPAftLkwkJT17Homv3Jkg="
private const val CAPTURED_BODY = "520c484019c567c00fb1d5a85b53f255c2aa52b1c346f94b4639a884d29f9b9ee038e8af7e11ca2922a2f53109a338a253863c6c2ebc6d7574cceb4541dc66a573c541caedbf39a994d2e8d417db0b91deabe0c531246c4f9c827479e1dc992efc2f68a170942068ecd9c5c015d67cf29cde6be5d51cce156a3c874a87175a965a78f68858730cdef9d5a59745bb44343298f9fc94f8267e59a226f9c6812f24f11f2829e262a68d99f64cab1d6a411207154f4648c9ad93930d1c465feff0ae4401671e1064b02e69fcb5756765e47feebba22995efef8367c4f42f1208e0c1a1f2ecae87d34834ece38869b8fa7c6d6bdf9a0e4287ba26b002084085b7be1453b9bc39456526167cfbadf400ec6dbc53808d3abf560a47f2f8f11cff84ebd4e6d4a8b291777311739a8ed36dcbe1733a1eb78deaf3377582bf1deb6056bd2fe9f63c3a1b1d2093b8d0eb04549961687045b2c2b976639dabf1d85542beaf65d71b7a54a22fe5e983ec881081a637e72efa52895e5aec6c280eae96dd0eaa668b23a0688fdbbb438c548a9d1ad9eef0379bc88eb90e5572bc8b3c54f56825bc434d106269ad81eea002938cd9652d4b66f1f007da8fd68d7c9ab99b4071c6fcfd925da06d85f842b24b24a88fa4054ac224704d3fa9649da9a42070a38c3e84a40cca291536a849b6ece5230f075748"

data class ReplayResult(
    val testName: String,
    val cartId: String,
    val httpCode: Int,
    val body: String
) {
    val color: Color get() = when {
        httpCode == 200 -> Color(0xFF00C853)
        httpCode == 404 -> Color(0xFF9E9E9E)
        httpCode in 400..499 -> Color(0xFFFF6D00)
        else -> Color(0xFF1976D2)
    }
    val verdict: String get() = when {
        httpCode == 200 -> "🎉 نجح — السيرفر قبل الطلب!"
        httpCode == 404 -> "⚠️ السلة القديمة محذوفة (غير حاسم)"
        body.contains("passengerTypeCode") -> "❌ السيرفر لم يفك التشفير"
        body.contains("uuid") || body.contains("cartId") -> "✅ السيرفر فكّ التشفير! (uuid لا يطابق)"
        else -> "🟠 ردّ مختلف — راجع التفاصيل"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayScreen(navController: NavController, viewModel: FlightViewModel) {
    val currentCartId = viewModel.cartId.collectAsStateWithLifecycle().value.orEmpty()
    var results by remember { mutableStateOf<List<ReplayResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    fun runTests() {
        if (running) return
        running = true
        results = emptyList()
        viewModel.viewModelScope.launch {
            val payload = """{"key":"$CAPTURED_KEY","body":"$CAPTURED_BODY"}"""

            if (currentCartId.isNotEmpty()) {
                try {
                    val resp = RetrofitClient.api.updateTravelerRaw(
                        cartId = currentCartId,
                        travelerId = CAPTURED_TRAVELER_ID,
                        headers = viewModel.buildHeadersPublic("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
                        request = payload.toRequestBody("application/json".toMediaType())
                    )
                    val body = resp.body()?.string() ?: resp.errorBody()?.string() ?: "(empty)"
                    results = results + ReplayResult("T1 (cartId الحالي)", currentCartId, resp.code(), body)
                    kotlinx.coroutines.delay(1500)
                } catch (e: Exception) {
                    results = results + ReplayResult("T1 (cartId الحالي)", currentCartId, -1, e.message ?: "")
                }
            }

            try {
                val resp2 = RetrofitClient.api.updateTravelerRaw(
                    cartId = CAPTURED_CART_ID,
                    travelerId = CAPTURED_TRAVELER_ID,
                    headers = viewModel.buildHeadersPublic("2", "https://www.saudia.com/ar-SA/booking/passengerDetails"),
                    request = payload.toRequestBody("application/json".toMediaType())
                )
                val body2 = resp2.body()?.string() ?: resp2.errorBody()?.string() ?: "(empty)"
                results = results + ReplayResult("T2 (cartId الملتقط)", CAPTURED_CART_ID, resp2.code(), body2)
            } catch (e: Exception) {
                results = results + ReplayResult("T2 (cartId الملتقط)", CAPTURED_CART_ID, -1, e.message ?: "")
            }
            
            running = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🧪 اختبار Replay", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("إرسال {key, body} الملتقط مباشرة بدون تشفير جديد", style = MaterialTheme.typography.bodySmall)
        
        if (currentCartId.isNotEmpty()) {
            Text("cartId الحالي: $currentCartId", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }

        Button(
            onClick = { runTests() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("جاري الاختبار...")
            } else {
                Text("▶ تشغيل الاختبار", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        results.forEach { r ->
            Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(2.dp, r.color)) {
                Column(Modifier.padding(12.dp)) {
                    Text(r.testName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text("cartId: ${r.cartId}", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("HTTP ${r.httpCode}", fontWeight = FontWeight.Bold, color = r.color, fontSize = 18.sp)
                    Text(r.verdict, color = r.color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(r.body.take(400), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
            Text("← رجوع")
        }
    }
}
