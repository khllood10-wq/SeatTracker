package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun LoginScreen(navController: NavController, viewModel: FlightViewModel) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("⏳ تسجيل الدخول إلى saudia.com...") }
    var progress by remember { mutableStateOf(0) }
    var captured by remember { mutableStateOf(false) }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onTokenCaptured(auth: String, xD: String, sid: String, ua: String) {
                if (captured) return
                captured = true
                viewModel.saveTokens(auth, xD, sid, ua)
                status = "✅ تم التقاط التوكن — جاري الانتقال..."
            }
        }
    }

    // الانتقال للرئيسية بمجرد التقاط التوكن
    LaunchedEffect(captured) {
        if (captured) {
            kotlinx.coroutines.delay(500)
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("🔐 تسجيل الدخول", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (progress in 1..99) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    addJavascriptInterface(bridge, "SaudiaBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            super.onPageStarted(view, url, favicon)
                            view?.evaluateJavascript("""
                                (function() {
                                    if (window.__loginHooked) return;
                                    window.__loginHooked = true;
                                    var origFetch = window.fetch;
                                    window.fetch = function(input, init) {
                                        try {
                                            var h = (init && init.headers) || {};
                                            var auth = h.Authorization || h.authorization;
                                            var xD = h['x-d-token'];
                                            var sid = h['saa-session-id'];
                                            if (auth && auth.indexOf('Bearer') > -1) {
                                                SaudiaBridge.onTokenCaptured(
                                                    auth, xD || '', sid || '', navigator.userAgent
                                                );
                                            }
                                        } catch(e) {}
                                        return origFetch.apply(this, arguments);
                                    };
                                    var origOpen = XMLHttpRequest.prototype.open;
                                    var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                                    XMLHttpRequest.prototype.open = function() {
                                        this.__h = {};
                                        return origOpen.apply(this, arguments);
                                    };
                                    XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
                                        if (this.__h) this.__h[k] = v;
                                        if (k.toLowerCase() === 'authorization' && v.indexOf('Bearer') > -1) {
                                            SaudiaBridge.onTokenCaptured(
                                                v, this.__h['x-d-token'] || '',
                                                this.__h['saa-session-id'] || '', navigator.userAgent
                                            );
                                        }
                                        return origSetHeader.apply(this, arguments);
                                    };
                                })();
                            """.trimIndent(), null)
                        }
                    }

                    loadUrl("https://www.saudia.com/ar-SA")
                }
            }
        )

        if (!captured) {
            OutlinedButton(
                onClick = {
                    val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
                    if (!prefs.getString("auth_token", null).isNullOrEmpty()) {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    } else {
                        status = "⚠️ لا يوجد توكن محفوظ بعد — سجّل دخول أولاً"
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("تخطي (إذا كنت مسجل دخول سابقاً)") }
        }
    }
}
