package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun AutoConfirmScreen(navController: NavController, viewModel: FlightViewModel) {
    val context = LocalContext.current
    val foundFlight = viewModel.foundFlight.collectAsStateWithLifecycle().value
    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    val sessionId = prefs.getString("session_id", "") ?: ""
    val xDToken = prefs.getString("xd_token", "") ?: ""
    val authToken = prefs.getString("auth_token", "") ?: ""
    val savedCookies = prefs.getString("dynamic_cookies", "") ?: ""
    val retrofitCartId = viewModel.cartId.collectAsStateWithLifecycle().value ?: ""
    val airBoundId = foundFlight?.airBoundId ?: ""

    var status by remember { mutableStateOf("⏳ جاري التحميل...") }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var success by remember { mutableStateOf(false) }
    var cartIdUsed by remember { mutableStateOf(retrofitCartId) }
    var step by remember { mutableStateOf(0) }

    fun addLog(msg: String) {
        logs = (logs + msg).takeLast(40)
        android.util.Log.d("AutoConfirm", msg)
    }

    fun dumpCookies(label: String) {
        try {
            val cm = CookieManager.getInstance()
            val sau = cm.getCookie("https://www.saudia.com") ?: "(empty)"
            val dapi = cm.getCookie("https://dapi.saudia.com") ?: "(empty)"
            addLog("🍪 [$label]")
            addLog("  www.saudia.com (${sau.length}ch): ${sau.take(250)}")
            addLog("  dapi.saudia.com (${dapi.length}ch): ${dapi.take(250)}")
            // حفظ للـ prefs
            val merged = "$sau; $dapi"
            prefs.edit().putString("dynamic_cookies", merged).apply()
        } catch (e: Exception) {
            addLog("❌ Cookie dump error: ${e.message}")
        }
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onPageReady(pageName: String) {
                Handler(Looper.getMainLooper()).post {
                    addLog("📄 Page ready: $pageName")
                    dumpCookies("After load $pageName")

                    when (pageName) {
                        "home" -> {
                            // بعد تحميل الرئيسية، أنشئ سلة جديدة
                            step = 1
                            status = "🛒 جاري إنشاء السلة..."
                            createCartViaWebView()
                        }
                        "passengers" -> {
                            // بعد تحميل صفحة المسافرين، تحقق من الفورم
                            step = 3
                            status = "🔍 جاري فحص الفورم..."
                            // انتظر 8 ثوانٍ للـ Angular
                            Handler(Looper.getMainLooper()).postDelayed({
                                checkForm()
                            }, 8000)
                        }
                    }
                }
            }

            @JavascriptInterface
            fun onCartCreated(cartId: String) {
                Handler(Looper.getMainLooper()).post {
                    cartIdUsed = cartId
                    viewModel.updateCartId(cartId)
                    addLog("✅ Cart created: $cartId")
                    dumpCookies("After cart creation")

                    // انتظر ثانيتين لاستقرار الكوكيز ثم انتقل
                    Handler(Looper.getMainLooper()).postDelayed({
                        status = "➡️ الانتقال لصفحة المسافرين..."
                        step = 2
                        webViewRef?.loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                    }, 2000)
                }
            }

            @JavascriptInterface
            fun onFormCheckResult(hasForm: Boolean, details: String) {
                Handler(Looper.getMainLooper()).post {
                    addLog("🔍 Form check: hasForm=$hasForm | $details")
                    if (hasForm) {
                        status = "📝 تم العثور على الفورم. جاري الملء..."
                        fillForm()
                    } else {
                        status = "❌ الفورم غير موجود — الصفحة بيضاء. راجع سجل الكوكيز."
                        dumpCookies("NO FORM after 8s")
                    }
                }
            }

            @JavascriptInterface
            fun onFilled() {
                Handler(Looper.getMainLooper()).post {
                    addLog("📝 Form filled. Clicking 'تأكيد'...")
                    status = "👆 جاري الضغط على تأكيد..."
                    clickConfirm()
                }
            }

            @JavascriptInterface
            fun onPatch(code: Int) {
                Handler(Looper.getMainLooper()).post {
                    addLog("📡 PATCH → HTTP $code")
                    if (code == 200) {
                        success = true
                        status = "🎉 تم تأكيد المسافر (HTTP 200)!"
                    } else {
                        status = "❌ PATCH فشل: HTTP $code"
                    }
                }
            }
        }
    }

    fun createCartViaWebView() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var xhr = new XMLHttpRequest();
                xhr.open("POST", "https://dapi.saudia.com/shopping/carts?guestOfficeId=JEDSV08MA&lang=ar-SA", true);
                xhr.setRequestHeader("Content-Type", "application/json");
                xhr.setRequestHeader("Authorization", "$authToken");
                xhr.setRequestHeader("x-d-token", "$xDToken");
                xhr.setRequestHeader("saa-session-id", "$sessionId");
                xhr.setRequestHeader("x-api-version", "2");
                xhr.setRequestHeader("platform", "Web");
                xhr.setRequestHeader("version", "27.2");
                xhr.setRequestHeader("channel", "MOBILE_WEB");
                xhr.withCredentials = true;
                xhr.onreadystatechange = function() {
                    if (xhr.readyState === 4 && (xhr.status === 200 || xhr.status === 201)) {
                        try {
                            var data = JSON.parse(xhr.responseText);
                            var id = data.id || (data.data && data.data.id);
                            if (id) SaudiaBridge.onCartCreated(id);
                        } catch(e) {}
                    }
                };
                xhr.send(JSON.stringify({ airBoundIds: ["$airBoundId"] }));
            })();
        """.trimIndent(), null)
    }

    fun checkForm() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var fn = document.querySelector('[formcontrolname="firstName"]') || document.querySelector('[name="firstName"]');
                var hasForm = fn !== null;
                var fields = [];
                if (hasForm) {
                    ['firstName','lastName','dateOfBirth','nationalId','documentNumber'].forEach(function(n) {
                        if (document.querySelector('[formcontrolname="'+n+'"]') || document.querySelector('[name="'+n+'"]')) fields.push(n);
                    });
                } else {
                    // عدّاد الحقول الموجودة
                    var allInputs = document.querySelectorAll('input');
                    fields.push('total_inputs=' + allInputs.length);
                }
                SaudiaBridge.onFormCheckResult(hasForm, fields.join(','));
            })();
        """.trimIndent(), null)
    }

    fun fillForm() {
        webViewRef?.evaluateJavascript("""
            (function() {
                function setVal(name, val) {
                    var el = document.querySelector('[formcontrolname="'+name+'"]') || document.querySelector('[name="'+name+'"]');
                    if (!el) return false;
                    var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    setter.call(el, val);
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                    el.dispatchEvent(new Event('blur', {bubbles:true}));
                    return true;
                }
                setVal('firstName', 'Khaled');
                setVal('lastName', 'Otudi');
                setVal('dateOfBirth', '1990-05-10');
                setVal('nationalId', '1071580342');
                setVal('documentNumber', '1071580342');
                SaudiaBridge.onFilled();
            })();
        """.trimIndent(), null)
    }

    fun clickConfirm() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var btns = document.querySelectorAll('button');
                for (var i = 0; i < btns.length; i++) {
                    var t = (btns[i].innerText || '').trim();
                    if (t.indexOf('تأكيد بيانات') > -1 || t.indexOf('تأكيد المسافر') > -1) {
                        btns[i].click();
                        return;
                    }
                }
            })();
        """.trimIndent(), null)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text("🔬 تشخيص الكوكيز + التأكيد", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(status, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("cartId: $cartIdUsed", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text("step: $step/4", fontSize = 10.sp)
            }
        }

        // WebView مرئي (لنرى ما يحدث)
        Box(modifier = Modifier.fillMaxWidth().height(280.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; SM-S918B) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        addJavascriptInterface(bridge, "SaudiaBridge")

                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        // لا نحقن كوكيز قديمة — نترك WebView يبدأ جلسته الخاصة

                        webChromeClient = object : WebChromeClient() {}

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                // Hook للـ PATCH
                                view?.evaluateJavascript("""
                                    (function() {
                                        if (window.__hooked) return;
                                        window.__hooked = true;
                                        var origFetch = window.fetch;
                                        window.fetch = function(input, init) {
                                            var u = typeof input === 'string' ? input : (input && input.url);
                                            var m = ((init && init.method) || 'GET').toUpperCase();
                                            if (u && u.indexOf('/travelers/web') > -1 && m === 'PATCH') {
                                                var p = origFetch.apply(this, arguments);
                                                p.then(function(r) { SaudiaBridge.onPatch(r.status); });
                                                return p;
                                            }
                                            return origFetch.apply(this, arguments);
                                        };
                                    })();
                                """.trimIndent(), null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url == null || view == null) return
                                if (url.contains("passengerDetails")) {
                                    bridge.onPageReady("passengers")
                                } else if (url.contains("saudia.com/ar-SA") && !url.contains("booking")) {
                                    bridge.onPageReady("home")
                                }
                            }
                        }

                        loadUrl("https://www.saudia.com/ar-SA")
                    }
                }
            )
        }

        // سجل التشخيص
        if (logs.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text("سجل التشخيص (${logs.size}):", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Column {
                            logs.forEach { line ->
                                Text(line, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }

        if (success) {
            Button(
                onClick = {
                    navController.navigate(Screen.Payment.route) {
                        popUpTo(Screen.Passengers.route) { inclusive = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("➡️ الانتقال لصفحة الدفع", fontWeight = FontWeight.Bold)
            }
        }

        if (logs.any { it.contains("NO FORM") }) {
            OutlinedButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("← رجوع (لنعرف المشكلة)") }
        }
    }
}
