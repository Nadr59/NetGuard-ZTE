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
    private lateinit var ipInput: EditText
    private lateinit var passInput: EditText
    private lateinit var statusText: TextView
    private val capturedData = StringBuilder()
    private var routerIp = "192.168.0.1"
    private var routerPassword = ""
    private var stokCookie = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // اقرأ IP المحفوظ
        val prefs = getSharedPreferences("netguard_prefs", MODE_PRIVATE)
        routerIp = prefs.getString("router_ip", "192.168.0.1") ?: "192.168.0.1"
        routerPassword = prefs.getString("password", "") ?: ""

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        // ═══ شريط التحكم العلوي ═══
        val controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }

        // صف العنوان
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val titleText = TextView(this).apply {
            text = "التقاط طلب الحظر"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val backButton = Button(this).apply {
            text = "رجوع"
            textSize = 11f
            setOnClickListener { finish() }
        }

        titleRow.addView(titleText)
        titleRow.addView(backButton)

        // صف الـ IP
        val ipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }

        ipInput = EditText(this).apply {
            setText(routerIp)
            hint = "عنوان الراوتر"
            textSize = 14f
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        passInput = EditText(this).apply {
            setText(routerPassword)
            hint = "كلمة المرور"
            textSize = 14f
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        ipRow.addView(ipInput)
        ipRow.addView(passInput)

        // صف الأزرار
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }

        val loadButton = Button(this).apply {
            text = "تحميل الراوتر"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                routerIp = ipInput.text.toString().trim()
                if (routerIp.isBlank()) routerIp = "192.168.0.1"
                loadRouter()
            }
        }

        val refreshButton = Button(this).apply {
            text = "تحديث"
            textSize = 12f
            setOnClickListener { webView.reload() }
        }

        val copyButton = Button(this).apply {
            text = "نسخ السجل"
            textSize = 12f
            setOnClickListener {
                val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("capture", capturedData.toString()))
                appendLog("تم النسخ!")
            }
        }

        buttonRow.addView(loadButton)
        buttonRow.addView(refreshButton)
        buttonRow.addView(copyButton)

        statusText = TextView(this).apply {
            text = "اضغط 'تحميل الراوتر' للبدء"
            textSize = 11f
            setPadding(0, 4, 0, 0)
            setTextColor(0xFF666666.toInt())
        }

        controlBar.addView(titleRow)
        controlBar.addView(ipRow)
        controlBar.addView(buttonRow)
        controlBar.addView(statusText)

        // ═══ WebView ═══
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // ═══ السجل ═══
        logText = TextView(this).apply {
            textSize = 9f
            setPadding(4, 4, 4, 4)
            setBackgroundColor(0xFF1A1A2E.toInt())
            setTextColor(0xFF00FF41.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }

        scrollLog = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                180
            )
        }

        layout.addView(controlBar)
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
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            blockNetworkImage = false
            loadsImagesAutomatically = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: ""
                val method = request?.method ?: ""
                if (url.contains("goform") && method == "POST") {
                    appendLog("═══ POST CAPTURED ═══")
                    appendLog("URL: $url")
                    // نحاول قراءة الـ body من request headers
                    val headers = request?.requestHeaders ?: emptyMap()
                    for ((k, v) in headers) {
                        if (k.lowercase() !in listOf("cookie", "referer", "user-agent", "accept", "host", "connection")) {
                            appendLog("Header $k: $v")
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                statusText.text = "تم تحميل: $url"
                appendLog("Loaded: $url")
                injectInterceptor(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val desc = error?.description?.toString() ?: "unknown"
                if (request?.isForMainFrame == true) {
                    statusText.text = "خطأ: $desc"
                    appendLog("Error: $desc")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                appendLog("HTTP Error: ${errorResponse?.statusCode}")
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

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                statusText.text = "تحميل... $newProgress%"
            }
        }

        // حمّل تلقائياً
        loadRouter()
    }

    private fun loadRouter() {
        val url = "http://$routerIp"
        statusText.text = "جاري تحميل $url ..."
        appendLog("Loading $url ...")
        webView.loadUrl(url)
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

            // اعتراض jQuery
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
                        console.log('CAPTURED|Cookies: ' + document.cookie);
                        console.log('CAPTURED|==========');
                    }
                    return origAjax.apply(this, arguments);
                };
            }

            // دوال التشفير
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
                if (typeof paswordAlgorithmsCookie !== 'undefined')
                    console.log('CAPTURED|paswordAlgorithmsCookie: ' +
                        paswordAlgorithmsCookie.toString().substring(0, 300));
            } catch(e) {
                console.log('CAPTURED|Auth funcs not loaded yet: ' + e.message);
            }

            console.log('CAPTURED|Done! Block a device now.');
        })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            capturedData.appendLine(msg)
            logText.append("$msg\n")
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
