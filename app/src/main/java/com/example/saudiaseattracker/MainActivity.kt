@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KeyCaptureScreen(onKeyCaptured: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    
    // إذا كان المفتاح موجوداً مسبقاً، تجاهل هذه الشاشة
    LaunchedEffect(Unit) {
        val existing = prefs.getString("rsa_public_key", null)
        if (!existing.isNullOrEmpty()) {
            onKeyCaptured()
        }
    }
    
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onPublicKey(pem: String) {
                        // تحقق أنه RSA حقيقي
                        if (!pem.contains("MII") && !pem.contains("BEGIN")) return
                        
                        // احفظه
                        prefs.edit().putString("rsa_public_key", pem).apply()
                        
                        // أبلغ ViewModel
                        (context as? MainActivity)?.runOnUiThread {
                            onKeyCaptured()
                        }
                    }
                }, "AndroidBridge")
                
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        // حقن السّنارة قبل تحميل الصفحة — قبل أي كود تشفير
                        view?.evaluateJavascript("""
                            (function() {
                                if (window.__keyHookInstalled) return;
                                window.__keyHookInstalled = true;
                                
                                // Hook 1: Web Crypto API (الأكثر شيوعاً)
                                if (window.crypto && window.crypto.subtle) {
                                    var origImport = crypto.subtle.importKey;
                                    crypto.subtle.importKey = function(format, keyData, algorithm, extractable, keyUsages) {
                                        try {
                                            if (algorithm && (algorithm.name === 'RSA-OAEP' || algorithm.name === 'RSAES-PKCS1-v1_5')) {
                                                var bytes = new Uint8Array(keyData);
                                                var b64 = btoa(String.fromCharCode.apply(null, bytes));
                                                // SPKI format — قد يكون DER مباشرة أو PEM
                                                var pem = '-----BEGIN PUBLIC KEY-----\n';
                                                for (var i = 0; i < b64.length; i += 64) pem += b64.substring(i, i + 64) + '\n';
                                                pem += '-----END PUBLIC KEY-----';
                                                AndroidBridge.onPublicKey(pem);
                                            }
                                        } catch(e) {}
                                        return origImport.apply(this, arguments);
                                    };
                                }
                                
                                // Hook 2: JSEncrypt
                                var checkJSEncrypt = setInterval(function() {
                                    if (window.JSEncrypt && window.JSEncrypt.prototype) {
                                        clearInterval(checkJSEncrypt);
                                        var origSet = JSEncrypt.prototype.setPublicKey;
                                        JSEncrypt.prototype.setPublicKey = function(pem) {
                                            try { AndroidBridge.onPublicKey(pem); } catch(e) {}
                                            return origSet.apply(this, arguments);
                                        };
                                    }
                                }, 200);
                                
                                // Hook 3: forge
                                var checkForge = setInterval(function() {
                                    if (window.forge && window.forge.pki) {
                                        clearInterval(checkForge);
                                        var origFromPem = forge.pki.publicKeyFromPem;
                                        forge.pki.publicKeyFromPem = function(pem) {
                                            try { AndroidBridge.onPublicKey(pem); } catch(e) {}
                                            return origFromPem.apply(this, arguments);
                                        };
                                    }
                                }, 200);
                            })();
                        """.trimIndent(), null)
                    }
                }
                
                // افتح صفحة المسافرين — قد يستورد المفتاح عند التهيئة
                loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails")
            }
        }
    )
}
