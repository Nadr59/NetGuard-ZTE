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
    private lateinit var scrollLog: ScrollView
    private val capturedData = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ═══ التخطيط الرأسي ═══
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }

        // ═══ شريط علوي ═══
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
        }

        val titleText = TextView(this).apply {
            text = "التقاط طلب الحظر"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val copyButton = Button(this).apply {
            text = "نسخ"
            textSize = 12f
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("capture", capturedData.toString())
                clipboard.setPrimaryClip(clip)
                appendLog("تم النسخ!")
            }
        }

        val clearButton = Button(this).apply {
            text = "مسح"
            textSize = 12f
            setOnClickListener {
                capturedData.clear()
                logText.text = ""
            }
        }

        topBar.addView(titleText)
        topBar.addView(copyButton)
        topBar.addView(clearButton)

        // ═══ زر رجوع ═══
        val backButton = Button(this).apply {
            text = "← رجوع للتطبيق"
            textSize = 12f
            setOnClickListener { finish() }
        }

        // ═══ WebView ═══
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f  // يأخذ المساحة المتبقية
            )
        }

        // ═══ منطقة السجل ═══
        logText = TextView(this).apply {
            textSize = 10f
            setPadding(4, 4, 4, 4)
            setBackgroundColor(0xFF1E1E1E.toInt())
            setTextColor(0xFF00FF00.toInt())
        }

        scrollLog = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                250
            )
        }

        // ═══ تجميع كل شيء ═══
        layout.addView(topBar)
        layout.addView(backButton)
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
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // محاكاة متصفح سطح المكتب
            userAgentString = userAgentString.replace(
                "Mobile", "Desktop"
            ).replace("Android", "Windows")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: ""
                val method = request?.method ?: ""

                if (url.contains("goform") && method == "POST") {
                    appendLog("═══ POST ═══")
                    appendLog("URL: $url")
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                appendLog("Page: $url")
                injectInterceptor(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                appendLog("Error: ${error?.description}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage?.message() ?: ""
                if (msg.startsWith("CAPTURED|")) {
                    val clean = msg.removePrefix("CAPTURED|")
                    appendLog(clean)
                    capturedData.appendLine(clean)
                }
                return true
            }
        }

        // ═══ تحميل الراوتر ═══
        appendLog("Loading http://192.168.0.1 ...")
        webView.loadUrl("http://192.168.0.1")
    }

    private fun injectInterceptor(view: WebView?) {
        val js = """
        (function() {
            console.log('CAPTURED|Interceptor ready!');

            // اعتراض XMLHttpRequest
            var origOpen = XMLHttpRequest.prototype.open;
            var origSend = XMLHttpRequest.prototype.send;

            XMLHttpRequest.prototype.open = function(method, url) {
                this._m = method;
                this._u = url;
                origOpen.apply(this, arguments);
            };

            XMLHttpRequest.prototype.send = function(body) {
                if (this._u && this._u.indexOf('goform') !== -1) {
                    console.log('CAPTURED|==========');
                    console.log('CAPTURED|Method: ' + this._m);
                    console.log('CAPTURED|URL: ' + this._u);
                    console.log('CAPTURED|Body: ' + body);
                    console.log('CAPTURED|Cookies: ' + document.cookie);
                    console.log('CAPTURED|==========');
                }
                origSend.apply(this, arguments);
            };

            // اعتراض jQuery.ajax
            if (typeof $ !== 'undefined') {
                var origAjax = $.ajax;
                $.ajax = function(s) {
                    if (s.url && s.url.indexOf('goform') !== -1) {
                        console.log('CAPTURED|==========');
                        console.log('CAPTURED|jQuery ' + (s.type||'GET'));
                        console.log('CAPTURED|URL: ' + s.url);
                        console.log('CAPTURED|Data: ' +
                            (typeof s.data === 'string' ?
                                s.data : JSON.stringify(s.data)));
                        console.log('CAPTURED|Cookies: ' +
                            document.cookie);
                        console.log('CAPTURED|==========');
                    }
                    return origAjax.apply(this, arguments);
                };

                var origPost = $.post;
                $.post = function(url, data, cb) {
                    if (url && url.indexOf('goform') !== -1) {
                        console.log('CAPTURED|==========');
                        console.log('CAPTURED|POST: ' + url);
                        console.log('CAPTURED|Data: ' +
                            (typeof data === 'string' ?
                                data : JSON.stringify(data)));
                        console.log('CAPTURED|Cookies: ' +
                            document.cookie);
                        console.log('CAPTURED|==========');
                    }
                    return origPost.apply(this, arguments);
                };
            }

            // اطبع دوال التشفير
            try {
                if (typeof cookWithRequest !== 'undefined')
                    console.log('CAPTURED|cookWithRequest: ' +
                        cookWithRequest.toString().substring(0, 300));
                if (typeof wr !== 'undefined')
                    console.log('CAPTURED|wr: ' +
                        wr.toString().substring(0, 300));
                if (typeof rd0 !== 'undefined')
                    console.log('CAPTURED|rd0: ' + rd0);
                if (typeof rd1 !== 'undefined')
                    console.log('CAPTURED|rd1: ' + rd1);
            } catch(e) {}

            console.log('CAPTURED|Done! Block a device now.');
        })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            capturedData.appendLine(msg)
            logText.append("$msg\n")
            // اسحب للأسفل تلقائياً
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
