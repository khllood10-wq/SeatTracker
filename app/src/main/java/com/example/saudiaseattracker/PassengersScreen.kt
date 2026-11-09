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
import androidx.compose.material.icons.filled.Refresh
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
    val foundFlight = viewModel.foundFlight.collectAsStateWithLifecycle().value
    val context = LocalContext.current

    var capturedBody by remember { mutableStateOf<String?>(null) }
    val logs = viewModel.searchLogs.collectAsStateWithLifecycle().value
    
    // نحتفظ بالـ webView كمرجع عشان نقدر نحدثه
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        // ننشئ السلة فقط إذا كانت غير موجودة (لتجنب إنشاء سلة جديدة وتغيير الـ cartId)
        if (viewModel.cartId.value.isNullOrEmpty()) {
            viewModel.createCartAndProceedToPassenger()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // ═══ 1. الجزء العلوي: السجل والتحكم ═══
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("حالة الحجز التلقائي", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث الصفحة")
                    }
                }
                Text("cartId: ${cartId ?: "جاري الإنشاء..."}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                
                // عرض آخر 4 أحداث
                logs.take(4).forEach { log ->
                    Text(log, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }

                if (capturedBody != null) {
                    Button(
                        onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("Captured", capturedBody))
                            Toast.makeText(context, "تم نسخ الـ Body المشفر!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Text("نسخ الطلب المشفر (Captured Body)")
                    }
                }
            }
        }

        // ═══ 2. الجزء السفلي: الـ WebView (ظاهر) ═══
        if (cartId.isNullOrEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Text("جاري إنشاء السلة...", modifier = Modifier.padding(top = 16.dp))
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
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
                                capturedBody = "$url\n\n$body"
                            }
                            @JavascriptInterface
                            fun onLog(msg: String) {
                                viewModel.addLog(msg)
                            }
                        }, "AndroidBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                viewModel.addLog("📄 WebView loaded: $url")
                                
                                // ننتظر 4 ثواني لضمان اكتمال تحميل Angular Data
                                view?.postDelayed({
                                    view.evaluateJavascript("""
                                        (function() {
                                            if (window.__captureInstalled) return;
                                            window.__captureInstalled = true;

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

                                            AndroidBridge.onLog("✅ JS Ready. Waiting for fields...");

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
                                                    el.click();
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
                                                        AndroidBridge.onLog("✅ Names filled");
                                                        step = 1; timer = 0;
                                                    }
                                                } else if (step === 1) {
                                                    selectCombo('title', 'Mr');
                                                    selectCombo('gender', 'Male');
                                                    selectCombo('documentType', 'National ID');
                                                    setInput('dateOfBirth', '1991-05-10');
                                                    setInput('documentNumber', '1071580342');
                                                    AndroidBridge.onLog("✅ Details filled");
                                                    step = 2; timer = 0;
                                                } else if (step === 2) {
                                                    if(timer > 4) { 
                                                        var c = clickBtn(['تأكيد', 'Confirm', 'حفظ', 'موافق']);
                                                        AndroidBridge.onLog("👉 Clicked Confirm: " + (c || "not found"));
                                                        step = 3; timer = 0;
                                                    }
                                                } else if (step === 3) {
                                                    if(timer > 6) { 
                                                        setInput('email', 'Khllood10@gmail.com');
                                                        setInput('phoneNumber', '572260016');
                                                        selectCombo('phoneCountryCode', '966');
                                                        selectCombo('countryCode', '966');
                                                        
                                                        var n = clickBtn(['متابعة', 'Continue', 'التالي', 'استمرار', 'الدفع']);
                                                        AndroidBridge.onLog("👉 Clicked Next: " + (n || "not found"));
                                                        step = 4; timer = 0;
                                                    }
                                                }
                                                
                                                if(timer > 60) { clearInterval(loop); AndroidBridge.onLog("⚠️ Timeout: Stopped at step " + step); }
                                            }, 500);
                                        })();
                                    """.trimIndent(), null)
                                }, 4000)
                            }
                        }

                        // هنا نمرر الـ cartId الصحيح
                        loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                    }
                },
                update = { view ->
                    // إذا تغير الـ cartId في التطبيق بعد تحميل الصفحة، نقوم بتحديث الرابط
                    if (view.url?.contains(cartId) == false) {
                        view.loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                    }
                }
            )
        }
    }
}
