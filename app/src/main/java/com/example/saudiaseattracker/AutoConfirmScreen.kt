package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AutoConfirmScreen(navController: NavController, viewModel: FlightViewModel) {
    val context = LocalContext.current
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value
    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    val sessionId = prefs.getString("session_id", "") ?: ""
    val xDToken = prefs.getString("xd_token", "") ?: ""
    val authToken = prefs.getString("auth_token", "") ?: ""
    val cookies = prefs.getString("dynamic_cookies", "") ?: ""

    var status by remember { mutableStateOf("⏳ جاري تحميل صفحة المسافرين...") }
    var done by remember { mutableStateOf(false) }

    if (cartId.isNullOrEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("❌ لا يوجد cartId — أنشئ السلة أولاً")
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("🤖 تأكيد تلقائي (يستخدم كود الموقع الحقيقي)", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text("cartId: $cartId", style = MaterialTheme.typography.bodySmall)
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                    // حقن الكوكيز المحفوظة (حرج لنجاح الجلسة)
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    if (cookies.isNotEmpty()) {
                        listOf("https://www.saudia.com", "https://dapi.saudia.com").forEach {
                            cm.setCookie(it, cookies)
                        }
                        cm.flush()
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            // حقن التوكنات في localStorage قبل أي طلب
                            view?.evaluateJavascript("""
                                (function() {
                                    try {
                                        localStorage.setItem('saa-session-id', '$sessionId');
                                        localStorage.setItem('x-d-token', '$xDToken');
                                        localStorage.setItem('authorization', 'Bearer $authToken');
                                    } catch(e) {}
                                })();
                            """.trimIndent(), null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url == null) return

                            when {
                                url.contains("passengerDetails") -> {
                                    status = "📝 حقن بيانات المسافر والضغط على تأكيد..."
                                    // انتظر Angular يجهّز الفورم
                                    view?.postDelayed({
                                        injectFillAndSubmit(view)
                                    }, 3500)
                                }
                                url.contains("/payment") || url.contains("/services") || url.contains("/review") -> {
                                    status = "✅ نجح! انتقلنا إلى: $url"
                                    done = true
                                    prefs.edit().putString("booking_done_at", url).apply()
                                }
                                url.contains("error") -> {
                                    status = "⚠️ صفحة خطأ: $url"
                                }
                            }
                        }
                    }

                    loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                }
            }
        )

        if (done) {
            Button(
                onClick = {
                    navController.navigate(Screen.Payment.route) {
                        popUpTo(Screen.Passengers.route) { inclusive = true }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(8.dp).height(60.dp)
            ) {
                Text("✅ اذهب لصفحة الدفع", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * يحقن بيانات المسافر في الفورم ويضغط زر التأكيد برمجياً.
 * يعيد المحاولة 20 مرة لأن Angular يحتاج وقتاً للتحقق من الحقول.
 */
private fun injectFillAndSubmit(view: WebView) {
    val js = """
        (function() {
            console.log('[AutoConfirm] start');
            
            function setVal(name, val) {
                var el = document.querySelector('[formcontrolname="' + name + '"]')
                       || document.querySelector('[name="' + name + '"]');
                if (!el) return false;
                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                setter.call(el, val);
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                el.dispatchEvent(new Event('blur', {bubbles:true}));
                return true;
            }
            
            var attempts = 0;
            var iv = setInterval(function() {
                attempts++;
                var fnOk = setVal('firstName', 'Khaled');
                var lnOk = setVal('lastName', 'Otudi');
                setVal('dateOfBirth', '1990-05-10');
                if (!setVal('nationalId', '1071580342')) {
                    setVal('documentNumber', '1071580342');
                }
                
                if (fnOk && lnOk) {
                    clearInterval(iv);
                    console.log('[AutoConfirm] filled after ' + attempts);
                    setTimeout(function() {
                        var btns = document.querySelectorAll('button');
                        var clicked = false;
                        for (var i = 0; i < btns.length; i++) {
                            var t = (btns[i].innerText || '').trim();
                            if (t.indexOf('تأكيد') > -1 || t.indexOf('متابعة') > -1 || t.indexOf('حفظ') > -1) {
                                console.log('[AutoConfirm] click: ' + t);
                                btns[i].click();
                                clicked = true;
                                break;
                            }
                        }
                        if (!clicked) {
                            console.log('[AutoConfirm] no button. Available:');
                            btns.forEach(function(b){ console.log('  '+ (b.innerText||'')); });
                        }
                    }, 2000);
                }
                if (attempts >= 20) {
                    clearInterval(iv);
                    console.log('[AutoConfirm] gave up');
                }
            }, 500);
        })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
}
