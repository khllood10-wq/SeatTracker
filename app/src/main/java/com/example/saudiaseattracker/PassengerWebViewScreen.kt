package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PassengerWebViewScreen(
    navController: NavController,
    viewModel: FlightViewModel
) {
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value
    val captured = viewModel.capturedPayload.collectAsStateWithLifecycle().value
    val logs = viewModel.searchLogs.collectAsStateWithLifecycle().value

    LaunchedEffect(captured) {
        if (captured != null) {
            // نحن الآن في WebView — نعود لـ PassengersScreen تلقائياً
            // ViewModel سيرسل PATCH عبر Retrofit ثم ينتقل للدفع
            navController.popBackStack()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    "📝 املأ بيانات المسافر ثم اضغط تأكيد",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Test / Test — هوية 1071580342 — ميلاد 1991-05-10 — بريد + هاتف",
                    style = MaterialTheme.typography.bodySmall
                )
                cartId?.let {
                    Text(
                        "cartId: $it",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36"

                    // نقل كوكيز Retrofit إلى WebView (نفس الجلسة = نفس السلة)
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@apply, true)
                        viewModel.getCookiesForWebView().forEach { cookie ->
                            setCookie("https://www.saudia.com/", cookie)
                            setCookie("https://dapi.saudia.com/", cookie)
                        }
                    }

                    addJavascriptInterface(
                        WebBridge(viewModel),
                        "AndroidBridge"
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            viewModel.addLog("🌐 صفحة محملة: ${url?.take(80)}")
                            // نضخ الـ Hook — يستبدل XHR/fetch في الصفحة
                            view?.evaluateJavascript(JS_HOOK, null)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            // لا نسمح للصفحة بالانتقال لأي مكان بعد الحجز
                            if (!url.contains("saudia.com")) return true
                            return false
                        }
                    }

                    loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails")
                }
            }
        )

        // شريط السجل المصغر
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(80.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                logs.takeLast(4).forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }
}

/** جسر JS → Kotlin */
class WebBridge(private val viewModel: FlightViewModel) {

    @JavascriptInterface
    fun onTravelerPayload(key: String, body: String) {
        viewModel.addLog("🔑 تم استخراج التشفير من WebView")
        viewModel.onPayloadCaptured(key, body)
    }

    @JavascriptInterface
    fun onJsReady() {
        viewModel.addLog("✅ JS Hook جاهز في الصفحة")
    }

    @JavascriptInterface
    fun onJsError(msg: String) {
        viewModel.addLog("⚠️ JS: $msg")
    }
}

/** JS يُحقن في صفحة السعودية */
private const val JS_HOOK = """
(function() {
    if (window.__saudiaHookInstalled) return;
    window.__saudiaHookInstalled = true;

    // ═══ Override XMLHttpRequest (Angular يستخدمه) ═══
    const OrigXHR = window.XMLHttpRequest;
    window.XMLHttpRequest = function() {
        const xhr = new OrigXHR();
        const origOpen = xhr.open;
        const origSend = xhr.send;
        let capturedUrl = '';
        let capturedMethod = '';

        xhr.open = function(method, url) {
            capturedUrl = url || '';
            capturedMethod = method || '';
            return origOpen.apply(xhr, arguments);
        };

        xhr.send = function(body) {
            const isTravelerPatch = capturedUrl.indexOf('/travelers/web/') >= 0
                && capturedMethod.toUpperCase() === 'PATCH';
            if (isTravelerPatch && body && typeof body === 'string') {
                try {
                    const parsed = JSON.parse(body);
                    if (parsed.key && parsed.body) {
                        AndroidBridge.onTravelerPayload(parsed.key, parsed.body);
                        // نُعيد 200 وهمي لمنع الطلب الحقيقي
                        setTimeout(function() {
                            try {
                                Object.defineProperty(xhr, 'readyState', {value: 4, configurable: true});
                                Object.defineProperty(xhr, 'status', {value: 200, configurable: true});
                                Object.defineProperty(xhr, 'responseText', {value: '{"success":true}', configurable: true});
                                Object.defineProperty(xhr, 'response', {value: '{"success":true}', configurable: true});
                                if (xhr.onreadystatechange) xhr.onreadystatechange(new Event('readystatechange'));
                                if (xhr.onload) xhr.onload(new Event('load'));
                            } catch (e) {
                                AndroidBridge.onJsError('fake200: ' + e.message);
                            }
                        }, 30);
                        return;
                    }
                } catch (e) {
                    AndroidBridge.onJsError('parse XHR: ' + e.message);
                }
            }
            return origSend.apply(xhr, arguments);
        };
        return xhr;
    };

    // ═══ Override fetch (احتياط) ═══
    const origFetch = window.fetch;
    window.fetch = function(input, init) {
        const url = typeof input === 'string' ? input : (input && input.url) || '';
        const method = ((init && init.method) || 'GET').toUpperCase();
        if (url.indexOf('/travelers/web/') >= 0 && method === 'PATCH') {
            try {
                const body = init && init.body;
                if (body && typeof body === 'string') {
                    const parsed = JSON.parse(body);
                    if (parsed.key && parsed.body) {
                        AndroidBridge.onTravelerPayload(parsed.key, parsed.body);
                        return Promise.resolve(new Response('{"success":true}', {
                            status: 200,
                            headers: {'Content-Type': 'application/json'}
                        }));
                    }
                }
            } catch (e) {
                AndroidBridge.onJsError('parse fetch: ' + e.message);
            }
        }
        return origFetch.apply(this, arguments);
    };

    AndroidBridge.onJsReady();
})();
"""
