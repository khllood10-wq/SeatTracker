package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

class WebAppInterface(private val onSuccess: (String) -> Unit, private val onError: (String) -> Unit) {
    @JavascriptInterface
    fun onSearchResult(jsonResult: String) { onSuccess(jsonResult) }
    
    @JavascriptInterface
    fun onError(error: String) { onError(error) }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    viewModel: FlightViewModel
) {
    var flightNumber by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchLogs by viewModel.searchLogs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isJsRunning by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        if (uiState == SearchState.FOUND) {
            navController.navigate(Screen.Passengers.route)
            viewModel.stopSearch() 
        } else if (uiState == SearchState.TOKEN_EXPIRED) {
            navController.navigate(Screen.Login.route) 
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("البحث عن رحلة", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = flightNumber,
            onValueChange = { flightNumber = it },
            label = { Text("رقم الرحلة (مثال: SV123)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState != SearchState.SEARCHING
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        if (uiState != SearchState.SEARCHING) {
            Button(
                onClick = { 
                    isJsRunning = true
                    val token = viewModel.getSavedAuthTokenForJs() ?: ""
                    val xDToken = viewModel.getSavedXDTokenForJs() ?: ""
                    val sessionId = viewModel.getSavedSessionIdForJs() ?: ""
                    
                    val calendar = java.util.Calendar.getInstance()
                    calendar.add(java.util.Calendar.DAY_OF_YEAR, 2)
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    val futureDate = sdf.format(calendar.time)
                    val departureDate = "${futureDate}T00:00:00.000"

                    // طلب البحث الأول (عبر المتصفح لتجاوز Imperva)
                    val jsCode = """
                        fetch('https://dapi.saudia.com/airsearch/search/air-bounds?guestOfficeId=JEDSV08MA&lang=ar-SA', {
                            method: 'POST',
                            headers: {
                                'Authorization': '$token',
                                'x-d-token': '$xDToken',
                                'saa-session-id': '$sessionId',
                                'x-api-version': '4',
                                'platform': 'Web',
                                'version': '27.0',
                                'Accept': 'application/json',
                                'Content-Type': 'application/json',
                                'Origin': 'https://www.saudia.com',
                                'Referer': 'https://www.saudia.com/'
                            },
                            body: JSON.stringify({
                                "region": "KSA",
                                "fromRegion": "KSA",
                                "toRegion": "KSA",
                                "airBoundsSearchInputs": {
                                    "itineraries": [{
                                        "originLocationCode": "JED",
                                        "destinationLocationCode": "AHB",
                                        "departureDateTime": "$departureDate",
                                        "directFlights": false,
                                        "isRequestedBound": true
                                    }],
                                    "travelers": [{"passengerTypeCode": "ADT"}],
                                    "commercialFareFamilies": ["NCFFGUESTN", "NCFFBUSN", "NCFFFIRSTN"],
                                    "searchPreferences": {"showUnavailableEntries": false, "showMilesPrice": false, "showSoldOut": true}
                                },
                                "offerCodeAvailabilityInput": {
                                    "channel": "MOBILE_WEB",
                                    "pointOfSale": "SA",
                                    "currencyCode": "SAR",
                                    "language": "AR",
                                    "browserId": "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9"
                                }
                            })
                        })
                        .then(response => {
                            if(response.ok) { return response.text(); }
                            else { throw new Error('HTTP Status ' + response.status); }
                        })
                        .then(data => Android.onSearchResult(data))
                        .catch(error => Android.onError(error.message));
                    """.trimIndent()

                    webViewInstance?.evaluateJavascript(jsCode, null)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = flightNumber.isNotBlank() && !isJsRunning
            ) {
                if (isJsRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("بدء البحث")
                }
            }
        } else {
            Button(
                onClick = { viewModel.stopSearch() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("إيقاف البحث")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("سجل الاتصال:", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                val fullLog = searchLogs.joinToString(separator = "\n")
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Logs", fullLog))
                Toast.makeText(context, "تم النسخ!", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "نسخ")
                Text(" نسخ")
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))

        // الـ WebView المخفي
        AndroidView(
            modifier = Modifier.size(1.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    
                    addJavascriptInterface(WebAppInterface(
                        onSuccess = { result ->
                            isJsRunning = false
                            viewModel.addLogToScreen("✅ نجح الطلب الأول عبر المتصفح! جاري استخراج تذكرة المرور...")
                            
                            // 🔥 سحب الكوكيز بعد نجاح الطلب (هنا يكون reese84 موجود)
                            val cookies = CookieManager.getInstance().getCookie("https://dapi.saudia.com") ?: ""
                            if (cookies.contains("reese84")) {
                                viewModel.saveImpervaCookie(cookies)
                                viewModel.addLogToScreen("🎉 تم تجاوز Imperva بنجاح! سيتم استخدام الـ API من الآن.")
                                
                                // تشغيل البحث المستمر عبر الـ API
                                viewModel.startSearchingFlight("SV")
                            } else {
                                viewModel.addLogToScreen("⚠️ لم نجد كوكي reese84. قد نحتاج لمحاولة أخرى.")
                            }
                        },
                        onError = { error ->
                            isJsRunning = false
                            viewModel.addLogToScreen("❌ فشل الطلب الأول: $error")
                        }
                    ), "Android")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url != null && url.contains("saudia.com")) {
                                webViewInstance = view
                            }
                        }
                    }
                    loadUrl("https://www.saudia.com/ar-SA")
                }
            }
        )

        SelectionContainer {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(searchLogs) { log ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(text = log, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
