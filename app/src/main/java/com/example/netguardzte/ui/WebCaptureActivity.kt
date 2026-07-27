package com.example.netguardzte.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("SetJavaScriptEnabled")
class WebCaptureActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var logText: TextView
    private val capturedData = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val titleText = TextView(this).apply {
            text = "التقاط طلب الحظر من الراوتر"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }

        val instructionsText = TextView(this).apply {
            text = "1. سجّل الدخول للراوتر\n2. اذهب لصفحة MAC Filter\n3. احظر أي جهاز\n4. انتظر النتيجة"
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }

        val copyButton = Button(this).apply {
            text = "نسخ النتيجة"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("capture", capturedData.toString())
                clipboard.setPrimaryClip(clip)
                appendLog("تم النسخ!")
            }
        }

        logText = TextView(this).apply {
            textSize = 11f
            setPadding(0, 16, 0, 0)
        }

        val scrollLog = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 200
            )
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        layout.addView(titleText)
        layout.addView(instructionsText)
        layout.addView(copyButton)
        layout.addView(webView)
        layout.addView(scrollLog)

        setContentView(layout)
        setupWebView()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // ═══ WebViewClient واحد فقط يجمع كل شيء ═══
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: ""
                val method = request?.method ?: ""
                if (url.contains("goform_set_cmd_process") && method == "POST") {
                    appendLog("POST CAPTURED: $url")
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectInterceptor(view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage?.message() ?: ""
                if (msg.startsWith("CAPTURED|")) {
                    val cleanMsg = msg.removePrefix("CAPTURED|")
                    appendLog(cleanMsg)
                    capturedData.appendLine(cleanMsg)
                }
                return true
            }
        }

        webView.loadUrl("http://192.168.0.1")
        appendLog("Loading router...")
    }

    private fun injectInterceptor(view: WebView?) {
        val js = """
        (function() {
            console.log('CAPTURED|Interceptor injected!');

            var origOpen = XMLHttpRequest.prototype.open;
            var origSend = XMLHttpRequest.prototype.send;

            XMLHttpRequest.prototype.open = function(method, url) {
                this._captureMethod = method;
                this._captureUrl = url;
                origOpen.apply(this, arguments);
            };

            XMLHttpRequest.prototype.send = function(body) {
                if (this._captureUrl && this._captureUrl.indexOf('goform') !== -1) {
                    console.log('CAPTURED|==========');
                    console.log('CAPTURED|Method: ' + this._captureMethod);
                    console.log('CAPTURED|URL: ' + this._captureUrl);
                    console.log('CAPTURED|Body: ' + body);
                    console.log('CAPTURED|Cookies: ' + document.cookie);
                    console.log('CAPTURED|==========');
                }
                origSend.apply(this, arguments);
            };

            if (typeof $ !== 'undefined' && $.ajax) {
                var origAjax = $.ajax;
                $.ajax = function(settings) {
                    if (settings.url && settings.url.indexOf('goform') !== -1) {
                        console.log('CAPTURED|==========');
                        console.log('CAPTURED|jQuery AJAX');
                        console.log('CAPTURED|URL: ' + settings.url);
                        console.log('CAPTURED|Type: ' + settings.type);
                        console.log('CAPTURED|Data: ' + JSON.stringify(settings.data));
                        console.log('CAPTURED|Cookies: ' + document.cookie);
                        console.log('CAPTURED|==========');
                    }
                    return origAjax.apply(this, arguments);
                };
            }

            if (typeof $ !== 'undefined' && $.post) {
                var origPost = $.post;
                $.post = function(url, data, callback) {
                    if (url && url.indexOf('goform') !== -1) {
                        console.log('CAPTURED|==========');
                        console.log('CAPTURED|jQuery POST');
                        console.log('CAPTURED|URL: ' + url);
                        console.log('CAPTURED|Data: ' + (typeof data === 'string' ? data : JSON.stringify(data)));
                        console.log('CAPTURED|Cookies: ' + document.cookie);
                        console.log('CAPTURED|==========');
                    }
                    return origPost.apply(this, arguments);
                };
            }

            if (typeof cookWithRequest !== 'undefined') {
                console.log('CAPTURED|cookWithRequest: ' + cookWithRequest.toString().substring(0, 500));
            }
            if (typeof wr !== 'undefined') {
                console.log('CAPTURED|wr: ' + wr.toString().substring(0, 500));
            }
            if (typeof rd0 !== 'undefined') {
                console.log('CAPTURED|rd0: ' + rd0);
            }
            if (typeof rd1 !== 'undefined') {
                console.log('CAPTURED|rd1: ' + rd1);
            }

            console.log('CAPTURED|Ready! Now block a device.');
        })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
        appendLog("Interceptor ready")
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            capturedData.appendLine(msg)
            logText.append("\n$msg")
        }
    }
}
