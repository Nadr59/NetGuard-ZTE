package com.example.netguardzte.data.api

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RouterCommandExecutor(private val context: Context) {

    private var webView: WebView? = null
    var webViewRef: WebView? = null
    private var ready = false
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    fun init(ip: String, onReady: () -> Unit) {
        handler.post {
            webView?.destroy()
            webView = WebView(context.applicationContext).apply {
                webViewRef = this  
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (!ready) {
                            ready = true
                            onReady()
                        }
                    }
                }
                loadUrl("http://$ip/m/index.html")
            }
        }
    }

    fun executeLogin(
        ip: String,
        password: String,
        callback: (Boolean, String) -> Unit
    ) {
        handler.post {
            val wv = webView ?: run {
                callback(false, "WebView not ready")
                return@post
            }

            wv.evaluateJavascript("""
                (function() {
                    try {
                        // ═══ LOGOUT أولاً ═══
                        var lx = new XMLHttpRequest();
                        lx.open('POST', '/goform/goform_set_cmd_process', false);
                        lx.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
                        lx.send('isTest=false&goformId=LOGOUT');

                        // ═══ انتظر قليلاً ═══
                        var start = new Date().getTime();
                        while (new Date().getTime() - start < 2000) {}

                        // ═══ اجلب LD ═══
                        var ldXhr = new XMLHttpRequest();
                        ldXhr.open('GET', '/goform/goform_get_cmd_process?cmd=LD', false);
                        ldXhr.send();
                        var ldData = JSON.parse(ldXhr.responseText);
                        var ld = ldData.LD;

                        if (!ld) return JSON.stringify({ok:false, msg:'LD empty'});

                        // ═══ شفر ═══
                        var pass = '$password';
                        var shaPass = SHA256(pass);
                        var encoded = SHA256(shaPass + ld);

                        // ═══ LOGIN ═══
                        var xhr = new XMLHttpRequest();
                        xhr.open('POST', '/goform/goform_set_cmd_process', false);
                        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
                        xhr.send('isTest=false&goformId=LOGIN&password=' + encoded + '&save_login=false');

                        return JSON.stringify({
                            ok: xhr.responseText.indexOf('"result":"0"') >= 0 || xhr.responseText.indexOf('"result":0') >= 0,
                            response: xhr.responseText,
                            ld: ld.substring(0, 16)
                        });
                    } catch(e) {
                        return JSON.stringify({ok:false, msg:e.toString()});
                    }
                })();
            """.trimIndent()) { result ->
                val clean = result.replace("\\\"", "\"").trim('"')
                val ok = clean.contains("\"ok\":true") || clean.contains("\"ok\": true")

                // استخرج الكوكيز
                val cookieMgr = CookieManager.getInstance()
                val cookies = cookieMgr.getCookie("http://$ip") ?: ""

                // حوّل الكوكيز لـ RetrofitClient
                for (cookie in cookies.split(";")) {
                    val parts = cookie.trim().split("=", limit = 2)
                    if (parts.size == 2) {
                        RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
                    }
                }

                callback(ok, clean)
            }
        }
    }

    fun executeBlock(
        ip: String,
        macList: String,
        callback: (Boolean, String) -> Unit
    ) {
        handler.post {
            val wv = webView ?: run {
                callback(false, "WebView not ready")
                return@post
            }

            wv.evaluateJavascript("""
                (function() {
                    try {
                        // ═══ اجلب LD ═══
                        var ldXhr = new XMLHttpRequest();
                        ldXhr.open('GET', '/goform/goform_get_cmd_process?cmd=LD', false);
                        ldXhr.send();
                        var ld = JSON.parse(ldXhr.responseText).LD || '';

                        // ═══ احسب AD ═══
                        var waXhr = new XMLHttpRequest();
                        waXhr.open('GET', '/goform/goform_get_cmd_process?cmd=wa_inner_version', false);
                        waXhr.send();
                        var wa = JSON.parse(waXhr.responseText).wa_inner_version || '';

                        var crXhr = new XMLHttpRequest();
                        crXhr.open('GET', '/goform/goform_get_cmd_process?cmd=cr_version', false);
                        crXhr.send();
                        var cr = JSON.parse(crXhr.responseText).cr_version || '';

                        var rdXhr = new XMLHttpRequest();
                        rdXhr.open('GET', '/goform/goform_get_cmd_process?cmd=RD', false);
                        rdXhr.send();
                        var rd = JSON.parse(rdXhr.responseText).RD || '';

                        var ad = '';
                        if (wa && cr && rd) {
                            ad = SHA256(SHA256(wa + cr) + rd);
                        }

                        // ═══ ارسل الحظر ═══
                        var body = 'isTest=false&goformId=setDeviceAccessControlList' +
                            '&AclMode=2&BlackMacList=' + encodeURIComponent('$macList') +
                            '&WhiteMacList=&WhiteNameList=&BlackNameList=&AD=' + ad;

                        var xhr = new XMLHttpRequest();
                        xhr.open('POST', '/goform/goform_set_cmd_process', false);
                        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
                        xhr.send(body);

                        return xhr.responseText;
                    } catch(e) {
                        return JSON.stringify({ok:false, msg:e.toString()});
                    }
                })();
            """.trimIndent()) { result ->
                val clean = result.replace("\\\"", "\"").trim('"')
                val ok = clean.contains("\"result\":\"success\"") ||
                         clean.contains("\"result\":0") ||
                         clean.contains("\"result\":\"0\"")
                callback(ok, clean)
            }
        }
    }

    fun executeUnblock(
        ip: String,
        aclMode: String,
        macList: String,
        callback: (Boolean, String) -> Unit
    ) {
        handler.post {
            val wv = webView ?: run {
                callback(false, "WebView not ready")
                return@post
            }

            wv.evaluateJavascript("""
                (function() {
                    try {
                        var waXhr = new XMLHttpRequest();
                        waXhr.open('GET', '/goform/goform_get_cmd_process?cmd=wa_inner_version', false);
                        waXhr.send();
                        var wa = JSON.parse(waXhr.responseText).wa_inner_version || '';

                        var crXhr = new XMLHttpRequest();
                        crXhr.open('GET', '/goform/goform_get_cmd_process?cmd=cr_version', false);
                        crXhr.send();
                        var cr = JSON.parse(crXhr.responseText).cr_version || '';

                        var rdXhr = new XMLHttpRequest();
                        rdXhr.open('GET', '/goform/goform_get_cmd_process?cmd=RD', false);
                        rdXhr.send();
                        var rd = JSON.parse(rdXhr.responseText).RD || '';

                        var ad = '';
                        if (wa && cr && rd) ad = SHA256(SHA256(wa + cr) + rd);

                        var body = 'isTest=false&goformId=setDeviceAccessControlList' +
                            '&AclMode=$aclMode&BlackMacList=' + encodeURIComponent('$macList') +
                            '&WhiteMacList=&WhiteNameList=&BlackNameList=&AD=' + ad;

                        var xhr = new XMLHttpRequest();
                        xhr.open('POST', '/goform/goform_set_cmd_process', false);
                        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
                        xhr.send(body);

                        return xhr.responseText;
                    } catch(e) {
                        return JSON.stringify({ok:false, msg:e.toString()});
                    }
                })();
            """.trimIndent()) { result ->
                val clean = result.replace("\\\"", "\"").trim('"')
                val ok = clean.contains("\"result\":\"success\"") ||
                         clean.contains("\"result\":0") ||
                         clean.contains("\"result\":\"0\"")
                callback(ok, clean)
            }
        }
    }

    fun executeGetAcl(
        ip: String,
        callback: (String) -> Unit
    ) {
        handler.post {
            val wv = webView ?: run {
                callback("{}")
                return@post
            }

            wv.evaluateJavascript("""
                (function() {
                    try {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '/goform/goform_get_cmd_process?cmd=queryDeviceAccessControlList', false);
                        xhr.send();
                        return xhr.responseText;
                    } catch(e) {
                        return '{}';
                    }
                })();
            """.trimIndent()) { result ->
                callback(result.replace("\\\"", "\"").trim('"'))
            }
        }
    }
        fun executeGet(
        cmd: String,
        callback: (String) -> Unit
    ) {
        handler.post {
            val wv = webView ?: run {
                callback("{}")
                return@post
            }
            wv.evaluateJavascript("""
                (function() {
                    try {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '/goform/goform_get_cmd_process?cmd=$cmd', false);
                        xhr.send();
                        return xhr.responseText;
                    } catch(e) { return '{}'; }
                })();
            """.trimIndent()) { result ->
                callback(result.replace("\\\"", "\"").trim('"'))
            }
        }
    }

    fun executeGetUrl(
        url: String,
        callback: (String) -> Unit
    ) {
        handler.post {
            val wv = webView ?: run {
                callback("{}")
                return@post
            }
            wv.evaluateJavascript("""
                (function() {
                    try {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', false);
                        xhr.send();
                        return xhr.responseText;
                    } catch(e) { return '{}'; }
                })();
            """.trimIndent()) { result ->
                callback(result.replace("\\\"", "\"").trim('"'))
            }
        }
    }

    fun destroy() {
        handler.post {
            webView?.destroy()
            webView = null
            ready = false
        }
    }
}
