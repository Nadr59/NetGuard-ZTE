package com.example.netguardzte.ui.screens.login

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.ui.viewmodel.NetGuardViewModel

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    viewModel: NetGuardViewModel,
    onLoginSuccess: () -> Unit
) {
    var routerIp by remember { mutableStateOf("192.168.0.1") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var showWebView by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "NetGuard ZTE",
            fontSize = 28.sp,
            color = Color(0xFFE8C547)
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = routerIp,
            onValueChange = { routerIp = it },
            label = { Text("Router IP") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFE8C547),
                unfocusedBorderColor = Color.Gray
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFE8C547),
                unfocusedBorderColor = Color.Gray
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFE8C547),
                unfocusedBorderColor = Color.Gray
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (password.isBlank()) {
                    statusMessage = "اكتب كلمة المرور"
                    return@Button
                }
                isLoading = true
                statusMessage = "جاري تسجيل الدخول..."
                showWebView = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE8C547)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("تسجيل الدخول", color = Color.Black, fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (statusMessage.isNotBlank()) {
            Text(statusMessage, color = Color.White, fontSize = 14.sp)
        }

        // ═══ WebView مخفي لتسجيل الدخول ═══
        if (showWebView && password.isNotBlank()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(1, 1)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)

                                if (url?.contains("/m/index.html") == true ||
                                    url?.contains("192.168.0.1") == true) {

                                    // انتظر قليلاً ثم نفّذ JavaScript
                                    view?.postDelayed({
                                        val js = """
                                            (function() {
                                                try {
                                                    var ldResp = new XMLHttpRequest();
                                                    ldResp.open('GET', '/goform/goform_get_cmd_process?cmd=LD', false);
                                                    ldResp.send();
                                                    var ldData = JSON.parse(ldResp.responseText);
                                                    var ld = ldData.LD;

                                                    if (!ld) {
                                                        Android.loginResult('ERROR', 'LD is empty');
                                                        return;
                                                    }

                                                    var pass = '$password';
                                                    var shaPass = SHA256(pass);
                                                    var encoded = SHA256(shaPass + ld);

                                                    var xhr = new XMLHttpRequest();
                                                    xhr.open('POST', '/goform/goform_set_cmd_process', false);
                                                    xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
                                                    xhr.send('isTest=false&goformId=LOGIN&password=' + encoded + '&save_login=false');

                                                    var result = JSON.parse(xhr.responseText);
                                                    Android.loginResult(JSON.stringify(result), 'LD=' + ld + ' SHA=' + shaPass.substring(0,16) + ' ENC=' + encoded.substring(0,16));
                                                } catch(e) {
                                                    Android.loginResult('ERROR', e.toString());
                                                }
                                            })();
                                        """.trimIndent()

                                        view?.evaluateJavascript(js, null)
                                    }, 3000)
                                }
                            }
                        }

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun loginResult(result: String, debug: String) {
                                android.util.Log.d("LOGIN", "Result: $result Debug: $debug")

                                if (result.contains("\"result\":\"0\"") ||
                                    result.contains("\"result\":0")) {

                                    // استخرج الكوكيز
                                    val cookieManager = CookieManager.getInstance()
                                    val cookies = cookieManager.getCookie("http://192.168.0.1")
                                    android.util.Log.d("LOGIN", "Cookies: $cookies")

                                    // حوّل الكوكيز لقائمة
                                    if (cookies != null) {
                                        for (cookie in cookies.split(";")) {
                                            val parts = cookie.trim().split("=", limit = 2)
                                            if (parts.size == 2) {
                                                RetrofitClient.setSessionCookie(
                                                    parts[0].trim(),
                                                    parts[1].trim()
                                                )
                                            }
                                        }
                                    }

                                    viewModel.saveCredentials(routerIp, username, password)
                                    isLoading = false
                                    statusMessage = "تم الاتصال!"
                                    showWebView = false
                                    onLoginSuccess()
                                } else {
                                    isLoading = false
                                    statusMessage = "فشل: $result ($debug)"
                                    showWebView = false
                                }
                            }
                        }, "Android")

                        loadUrl("http://$routerIp/m/index.html")
                        webViewRef = this
                    }
                },
                modifier = Modifier.size(1.dp)
            )
        }
    }
}
