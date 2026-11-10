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

    val airBoundId = foundFlight?.airBoundId ?: ""

    var status by remember { mutableStateOf("⏳ تحميل الصفحة الرئيسية...") }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var progress by remember { mutableStateOf(0) }
    var patchSent by remember { mutableStateOf(false) }
    var patchBodySize by remember { mutableStateOf(0) }
    var apiResultCode by remember { mutableStateOf(0) }
    var apiResultBody by remember { mutableStateOf("") }
    var apiSuccess by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun addLog(msg: String) {
        logs = (logs + msg).takeLast(20)
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onCartCreated(newCartId: String) {
                Handler(Looper.getMainLooper()).post {
                    viewModel.updateCartId(newCartId)
                    status = "✅ السلة: $newCartId. جاري التشفير المباشر..."
                    addLog("🛒 Cart created: $newCartId")
                    
                    // سحر التشفير: نطلب من الجافاسكريبت تنفيذ التشفير وإرسال الـ PATCH
                    webViewRef?.evaluateJavascript(getEncryptionJS(newCartId, authToken, xDToken, sessionId), null)
                }
            }

            @JavascriptInterface
            fun onPatchRequest(bodyLength: Int) {
                Handler(Looper.getMainLooper()).post {
                    patchBodySize = bodyLength
                    status = "📤 تم تشفير البيانات (حجم = $bodyLength حرف). جاري الإرسال..."
                    addLog("📤 PATCH prepared: $bodyLength chars")
                }
            }

            @JavascriptInterface
            fun onPatchResponse(statusCode: Int, responseBody: String) {
                Handler(Looper.getMainLooper()).post {
                    apiResultCode = statusCode
                    apiResultBody = responseBody
                    patchSent = true
                    apiSuccess = (statusCode == 200)

                    if (statusCode == 200) {
                        status = "🎉 نجح! السيرفر قبل البيانات."
                        addLog("🎉 HTTP 200 OK")
                    } else {
                        status = "❌ فشل: HTTP $statusCode"
                        addLog("❌ HTTP $statusCode")
                    }
                }
            }

            @JavascriptInterface
            fun onLog(msg: String) {
                Handler(Looper.getMainLooper()).post {
                    addLog(msg)
                }
            }
        }
    }

    fun startCartCreation() {
        status = "🛒 جاري إنشاء السلة عبر WebView..."
        addLog("🛒 بدء إنشاء السلة للرحلة: $airBoundId")
        webViewRef?.evaluateJavascript("""
            (function() {
                var airBoundId = '$airBoundId';
                var authToken = '$authToken';
                var xDToken = '$xDToken';
                var sessionId = '$sessionId';
                
                SaudiaBridge.onLog('Starting XHR for Cart...');
                var xhr = new XMLHttpRequest();
                xhr.open("POST", "https://dapi.saudia.com/shopping/carts?guestOfficeId=JEDSV08MA&lang=ar-SA", true);
                xhr.setRequestHeader("Content-Type", "application/json");
                xhr.setRequestHeader("Authorization", authToken);
                xhr.setRequestHeader("x-d-token", xDToken);
                xhr.setRequestHeader("saa-session-id", sessionId);
                xhr.setRequestHeader("x-api-version", "2");
                xhr.setRequestHeader("platform", "Web");
                xhr.setRequestHeader("version", "27.2");
                xhr.setRequestHeader("channel", "MOBILE_WEB");
                xhr.setRequestHeader("browserid", "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9");
                
                xhr.onreadystatechange = function() {
                    if (xhr.readyState === 4) {
                        if (xhr.status === 200 || xhr.status === 201) {
                            try {
                                var data = JSON.parse(xhr.responseText);
                                var cartId = data.id || (data.data && data.data.id);
                                if (cartId) {
                                    SaudiaBridge.onCartCreated(cartId);
                                } else {
                                    SaudiaBridge.onLog('Cart ID missing in response');
                                }
                            } catch(e) {
                                SaudiaBridge.onLog('Parse error: ' + e.message);
                            }
                        } else {
                            SaudiaBridge.onLog('XHR Failed: ' + xhr.status);
                        }
                    }
                };
                xhr.send(JSON.stringify({ airBoundIds: [airBoundId] }));
            })();
        """.trimIndent(), null)
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("🤖 تشفير مباشر (بدون UI)", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                
                if (progress in 1..99 && !patchSent && !status.contains("✅")) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                
                if (patchBodySize > 0) {
                    Text("حجم البيانات المشفرة: $patchBodySize حرف", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.height(8.dp))
                Button(onClick = { startCartCreation() }, modifier = Modifier.fillMaxWidth()) {
                    Text("🛒 بدء العملية (إنشاء سلة + تشفير + إرسال)")
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(250.dp).padding(horizontal = 8.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        clearCache(true)
                        clearHistory()
                        clearFormData()

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; SM-S918B) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        
                        addJavascriptInterface(bridge, "SaudiaBridge")

                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url != null && url.contains("saudia.com/ar-SA")) {
                                    status = "✅ الصفحة الرئيسية جاهزة. اضغط الزر لبدء العملية."
                                    addLog("Page loaded. Ready.")
                                }
                            }
                        }

                        loadUrl("https://www.saudia.com/ar-SA")
                    }
                }
            )
        }

        if (logs.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("سجل العمليات:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    logs.forEach { line ->
                        Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        if (patchSent) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                border = BorderStroke(2.dp, if (apiSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("HTTP $apiResultCode", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (apiSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(apiResultBody.take(400), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }

        if (apiSuccess) {
            Button(
                onClick = {
                    navController.navigate(Screen.Payment.route) {
                        popUpTo(Screen.Passengers.route) { inclusive = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(8.dp).height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("➡️ الانتقال لصفحة الدفع", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        if (!patchSent) {
            OutlinedButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) { Text("إلغاء والرجوع") }
        }
    }
}

// 🪄 دالة JavaScript التي تقوم بكل السحر (استخراج المفتاح + التشفير + الإرسال)
fun getEncryptionJS(cartId: String, authToken: String, xDToken: String, sessionId: String): String {
    return """
        (function() {
            var cartId = '$cartId';
            var authToken = '$authToken';
            var xDToken = '$xDToken';
            var sessionId = '$sessionId';
            
            async function run() {
                try {
                    SaudiaBridge.onLog('Fetching JS bundle to find RSA key...');
                    let res = await fetch('https://www.saudia.com/dist/SaudiaWebApp/prod/browser/chunk-QUL4NBYT.js');
                    let txt = await res.text();
                    let keyMatch = txt.match(/MII[A-Za-z0-9+/]{200,}/g);
                    if (!keyMatch) throw new Error("Key not found in bundle");
                    let rsaPem = "-----BEGIN PUBLIC KEY-----\n" + keyMatch[0] + "\n-----END PUBLIC KEY-----";
                    
                    SaudiaBridge.onLog('Building payload...');
                    let plaintext = JSON.stringify([{
                        tid: null, id: "SKH-1-EXT", title: "Mr", passengerTypeCode: "ADT",
                        firstName: "Khaled", lastName: "Otudi", dateOfBirth: "1991-05-09T21:00:00.000Z",
                        nationality: "SA", rememberMe: true, uuid: cartId + "SKH-1-EXT",
                        travellerDoc: {
                            docType: "National ID", passportNumber: "", expiryDate: "",
                            issuingCountry: "", nationalId: "1071580342", gccId: "",
                            residentId: "", number: "1071580342", id: null
                        }
                    }]);

                    SaudiaBridge.onLog('Encrypting...');
                    let aesKey = await crypto.subtle.generateKey({ name: "AES-CBC", length: 256 }, true, ["encrypt"]);
                    let iv = crypto.getRandomValues(new Uint8Array(16));
                    let encBodyBuffer = await crypto.subtle.encrypt({ name: "AES-CBC", iv: iv }, aesKey, new TextEncoder().encode(plaintext));
                    
                    let bodyBytes = new Uint8Array(iv.length + encBodyBuffer.byteLength);
                    bodyBytes.set(iv, 0);
                    bodyBytes.set(new Uint8Array(encBodyBuffer), iv.length);
                    let bodyHex = Array.from(bodyBytes).map(b => b.toString(16).padStart(2, '0')).join('');

                    let rawAesKey = await crypto.subtle.exportKey("raw", aesKey);
                    let pemContents = rsaPem.replace(/-----(BEGIN|END) PUBLIC KEY-----/g, '').replace(/\s/g, '');
                    let binaryDerString = atob(pemContents);
                    let binaryDer = new Uint8Array(binaryDerString.length);
                    for (let i = 0; i < binaryDerString.length; i++) binaryDer[i] = binaryDerString.charCodeAt(i);
                    let publicKey = await crypto.subtle.importKey("spki", binaryDer, { name: "RSA-OAEP", hash: "SHA-256" }, false, ["encrypt"]);
                    let encKeyBuffer = await crypto.subtle.encrypt({ name: "RSA-OAEP" }, publicKey, rawAesKey);
                    let keyBase64 = btoa(String.fromCharCode.apply(null, new Uint8Array(encKeyBuffer)));

                    let payload = JSON.stringify({ key: keyBase64, body: bodyHex });
                    SaudiaBridge.onPatchRequest(payload.length);
                    
                    var xhr = new XMLHttpRequest();
                    xhr.open("PATCH", "https://dapi.saudia.com/shopping/carts/" + cartId + "/travelers/web/SKH-1-EXT?guestOfficeId=JEDSV08MA&lang=ar-SA", true);
                    xhr.setRequestHeader("Content-Type", "application/json");
                    xhr.setRequestHeader("Authorization", authToken);
                    xhr.setRequestHeader("x-d-token", xDToken);
                    xhr.setRequestHeader("saa-session-id", sessionId);
                    xhr.setRequestHeader("x-api-version", "2");
                    xhr.setRequestHeader("platform", "Web");
                    xhr.setRequestHeader("version", "27.2");
                    xhr.setRequestHeader("channel", "MOBILE_WEB");
                    
                    xhr.onreadystatechange = function() {
                        if (xhr.readyState === 4) {
                            SaudiaBridge.onPatchResponse(xhr.status, xhr.responseText);
                        }
                    };
                    xhr.send(payload);
                } catch(e) {
                    SaudiaBridge.onLog('Encryption Error: ' + e.message);
                }
            }
            run();
        })();
    """.trimIndent()
}
