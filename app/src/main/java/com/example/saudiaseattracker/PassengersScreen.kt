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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
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

    Box(modifier = Modifier.fillMaxSize()) {
        
        // ═══ WebView مخفي (شفاف) لالتقاط الطلب ═══
        if (!cartId.isNullOrEmpty() && !webViewStarted) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.01f) // شفاف جداً (المستخدم لن يراه) ولكن المتصفح يعمل بكامل قوته
                    .zIndex(-1f), // خلف الواجهة
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
                        
                        // إجبار المتصفح على تشغيل المؤقتات وعدم تجميدها
                        resumeTimers()
                        onResume()

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

                                        // اعتراض الطلبات
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

                                        function setInput(name, val) {
                                            var el = document.querySelector('input[formcontrolname="' + name + '"]');
                                            if (!el) return false;
                                            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                            setter.call(el, val);
                                            el.dispatchEvent(new Event('input', {bubbles:true}));
                                            el.dispatchEvent(new Event('change', {bubbles:true}));
                                            return true;
                                        }

                                        function selectCombo(name, matchText) {
                                            var el = document.querySelector('[formcontrolname="' + name + '"]');
                                            if (!el) return false;
                                            if (el.tagName.toLowerCase() === 'select') {
                                                for(var i=0; i<el.options.length; i++) {
                                                    if(el.options[i].text.includes(matchText)) {
                                                        el.selectedIndex = i;
                                                        el.dispatchEvent(new Event('change', {bubbles:true}));
                                                        return true;
                                                    }
                                                }
                                            } else {
                                                el.click(); // فتح القائمة المنسدلة
                                                setTimeout(function(){
                                                    var opts = document.querySelectorAll('mat-option, .mat-option');
                                                    for(var i=0; i<opts.length; i++){
                                                        if(opts[i].innerText.includes(matchText) || opts[i].textContent.includes(matchText)){
                                                            opts[i].click();
                                                            break;
                                                        }
                                                    }
                                                }, 500);
                                                return true;
                                            }
                                            return false;
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

                                        var step = 0;
                                        var timer = 0;
                                        var loop = setInterval(function() {
                                            timer++;
                                            
                                            if (step === 0) {
                                                if (setInput('firstName', 'Khaled') && setInput('lastName', 'Otudi')) {
                                                    AndroidBridge.onCaptured('__STEP_1_NAMES__', 'Names filled');
                                                    step = 1; timer = 0;
                                                }
                                            } else if (step === 1) {
                                                selectCombo('title', 'Mr');
                                                selectCombo('gender', 'Male');
                                                selectCombo('documentType', 'National ID');
                                                setInput('dateOfBirth', '1991-05-10');
                                                setInput('documentNumber', '1071580342');
                                                AndroidBridge.onCaptured('__STEP_2_DETAILS__', 'Details filled');
                                                step = 2; timer = 0;
                                            } else if (step === 2) {
                                                if(timer > 4) { 
                                                    var c = clickBtn(['تأكيد', 'Confirm', 'حفظ', 'موافق']);
                                                    AndroidBridge.onCaptured('__CONFIRM_CLICKED__', c || 'not found');
                                                    step = 3; timer = 0;
                                                }
                                            } else if (step === 3) {
                                                if(timer > 6) { 
                                                    setInput('email', 'Khllood10@gmail.com');
                                                    setInput('phoneNumber', '572260016');
                                                    selectCombo('phoneCountryCode', '966');
                                                    selectCombo('countryCode', '966');
                                                    
                                                    var n = clickBtn(['متابعة', 'Continue', 'التالي', 'استمرار', 'الدفع']);
                                                    AndroidBridge.onCaptured('__NEXT_CLICKED__', n || 'not found');
                                                    step = 4; timer = 0;
                                                }
                                            }
                                            
                                            if(timer > 60) { clearInterval(loop); AndroidBridge.onCaptured('__TIMEOUT__', 'Stopped at step ' + step); }
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

        // ═══ واجهة المستخدم (تظهر فوق المتصفح الشفاف) ═══
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
}
