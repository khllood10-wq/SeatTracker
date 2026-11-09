package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled")
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

    var webViewStarted by remember { mutableStateOf(false) }
    var capturedBody by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.createCartAndProceedToPassenger()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("صفحة المسافرين", style = MaterialTheme.typography.headlineMedium)

        foundFlight?.let { f ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("ملخص الحجز:", fontWeight = FontWeight.Bold)
                    Text("الرحلة: ${f.flightNumber}")
                    Text("المسار: ${f.origin} → ${f.destination}")
                    Text("السعر: ${f.price} SAR")
                    Text("الفئة: ${f.fareFamily}")
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (cartId.isNullOrEmpty()) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("جاري إنشاء السلة...")
            } else {
                Text("✅ cartId: $cartId", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        // ═══ WebView مخفي لالتقاط PATCH travelers/web المشفر ═══
        if (!cartId.isNullOrEmpty() && !webViewStarted) {
            AndroidView(
                modifier = Modifier.size(1.dp),
                factory = { ctx ->
                    webViewStarted = true
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onCaptured(url: String, body: String) {
                                viewModel.addLog("🎯 URL: $url")
                                viewModel.addLog("🎯 BODY: ${body.take(120)}...")
                                capturedBody = "$url\n\n$body"
                            }
                        }, "AndroidBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                viewModel.addLog("📄 WebView page loaded: $url")
                                
                                view?.evaluateJavascript("""
                                    (function() {
                                        if (window.__captureInstalled) return;
                                        window.__captureInstalled = true;

                                        // اعتراض fetch و XHR
                                        var of = window.fetch;
                                        window.fetch = function(u, o) {
                                            try {
                                                if (o && o.body && u && String(u).indexOf('travelers/web') > -1) {
                                                    AndroidBridge.onCaptured(String(u), String(o.body));
                                                }
                                            } catch(e) {}
                                            return of.apply(this, arguments);
                                        };

                                        var ox = XMLHttpRequest.prototype.send;
                                        XMLHttpRequest.prototype.send = function(b) {
                                            try {
                                                if (b && this.__u__ && this.__u__.indexOf('travelers/web') > -1) {
                                                    AndroidBridge.onCaptured(String(this.__u__), String(b));
                                                }
                                            } catch(e) {}
                                            return ox.apply(this, arguments);
                                        };

                                        var oo = XMLHttpRequest.prototype.open;
                                        XMLHttpRequest.prototype.open = function(m, u) {
                                            this.__u__ = String(u);
                                            return oo.apply(this, arguments);
                                        };

                                        AndroidBridge.onCaptured('__INSTALLED__', 'capture ready');

                                        function setVal(sel, val) {
                                            var el = document.querySelector(sel);
                                            if (!el) return false;
                                            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                            setter.call(el, val);
                                            el.dispatchEvent(new Event('input', {bubbles:true}));
                                            el.dispatchEvent(new Event('change', {bubbles:true}));
                                            return true;
                                        }

                                        function clickBtn(keywords) {
                                            var elements = document.querySelectorAll('button, [role="button"], a');
                                            for (var i = 0; i < elements.length; i++) {
                                                var text = (elements[i].innerText || elements[i].textContent || '').trim();
                                                for (var k = 0; k < keywords.length; k++) {
                                                    if (text.indexOf(keywords[k]) > -1) {
                                                        elements[i].click();
                                                        return text;
                                                    }
                                                }
                                            }
                                            return null;
                                        }

                                        // نظام المراقبة الذكي: يفحص الصفحة كل نصف ثانية
                                        var state = 0;
                                        var stateTimer = 0;
                                        var globalInterval = setInterval(function() {
                                            stateTimer++;
                                            
                                            if (state === 0) {
                                                // الخطوة 0: البحث عن حقول المسافر
                                                if (setVal('input[formcontrolname="firstName"]', 'Khaled') && 
                                                    setVal('input[formcontrolname="lastName"]', 'Otudi')) {
                                                    
                                                    setVal('input[formcontrolname="dateOfBirth"]', '1991-05-10');
                                                    setVal('input[formcontrolname="documentNumber"]', '1071580342');
                                                    
                                                    AndroidBridge.onCaptured('__FILLED__', 'passenger fields filled');
                                                    state = 1;
                                                    stateTimer = 0;
                                                } else if (stateTimer > 60) { // مهلة 30 ثانية كحد أقصى للتحميل
                                                    clearInterval(globalInterval);
                                                    AndroidBridge.onCaptured('__TIMEOUT__', 'Form not found');
                                                }
                                            } else if (state === 1) {
                                                // الخطوة 1: الانتظار 3 ثواني (6 * 500ms) ثم الضغط على تأكيد
                                                if (stateTimer > 6) { 
                                                    var clickedConfirm = clickBtn(['تأكيد', 'Confirm', 'حفظ', 'موافق']);
                                                    AndroidBridge.onCaptured('__CONFIRM_CLICKED__', clickedConfirm || 'not found');
                                                    state = 2;
                                                    stateTimer = 0;
                                                }
                                            } else if (state === 2) {
                                                // الخطوة 2: الانتظار 3 ثواني ثم ملء بيانات الاتصال والضغط على متابعة
                                                if (stateTimer > 6) { 
                                                    setVal('input[formcontrolname="email"]', 'Khllood10@gmail.com');
                                                    setVal('input[formcontrolname="phoneNumber"]', '572260016');
                                                    var clickedNext = clickBtn(['متابعة', 'Continue', 'التالي', 'استمرار', 'الدفع']);
                                                    AndroidBridge.onCaptured('__NEXT_CLICKED__', clickedNext || 'not found');
                                                    state = 3;
                                                    stateTimer = 0;
                                                }
                                            }
                                        }, 500);
                                    })();
                                """.trimIndent(), null)
                            }
                        }

                        loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                    }
                }
            )
        }

        HorizontalDivider()

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
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp
                    )
                }
            }
        }

        // ═══ عرض الـ captured body ═══
        if (capturedBody != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🎯 Captured Body:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Button(onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("Captured", capturedBody))
                    Toast.makeText(context, "تم النسخ!", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Text(" نسخ")
                }
            }
            SelectionContainer {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = capturedBody!!,
                        modifier = Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp
                    )
                }
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("استجابة السلة (JSON):", style = MaterialTheme.typography.titleSmall)
            Button(onClick = {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("CartJSON", rawCartResponse))
                Toast.makeText(context, "تم النسخ!", Toast.LENGTH_SHORT).show()
            }, enabled = rawCartResponse.isNotEmpty()) {
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
