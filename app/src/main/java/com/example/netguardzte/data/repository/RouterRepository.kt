package com.example.netguardzte.data.repository

import android.util.Base64
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.data.api.ZteRouterApi
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class RouterRepository(private val storage: SecureStorage) {

    var lastRawResponse: String = ""
        private set
    var lastWorkingCommand: String = ""
        private set
    var loginDebug: String = ""
        private set
    var cookieDebug: String = ""
        private set
    var allCommandsDebug: String = ""
        private set

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    // ═══════════════════════════════════════════
    // أدوات مساعدة
    // ═══════════════════════════════════════════

    private fun encodeParam(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun isSuccess(body: String): Boolean {
        return body.contains("\"result\":\"success\"") ||
                body.contains("\"result\":0") ||
                body.contains("\"result\":\"0\"")
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun extractJsonField(json: String, field: String): String {
        // مع quotes
        Regex(""""$field"\s*:\s*"([^"]*?)"""").find(json)?.let {
            return it.groupValues[1]
        }
        // بدون quotes
        Regex(""""$field"\s*:\s*([0-9a-fA-F]+)""").find(json)?.let {
            return it.groupValues[1]
        }
        // كقيمة رقمية
        Regex(""""$field"\s*:\s*(\d+)""").find(json)?.let {
            return it.groupValues[1]
        }
        // أي قيمة
        Regex(""""$field"\s*:\s*"?([^",}]+)"?""").find(json)?.let {
            return it.groupValues[1].trim()
        }
        return ""
    }

    private fun readCookies(response: Response<*>, debug: StringBuilder) {
        try {
            for (c in response.headers().values("Set-Cookie")) {
                val parts = c.split(";")[0].split("=", limit = 2)
                if (parts.size == 2) {
                    RetrofitClient.setSessionCookie(parts[0].trim(), parts[1].trim())
                }
            }
            cookieDebug = "Cookies: ${RetrofitClient.getCookiesString()}"
            debug.appendLine(cookieDebug)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // جلب قيمة NV (LD أو RD)
    // من service.js: wr({nv:"LD"}).LD
    // ═══════════════════════════════════════════════════════════════

    private suspend fun fetchNvValue(
        api: ZteRouterApi,
        nvName: String,
        debug: StringBuilder
    ): String {
        // الطريقة 1: GET ?nv=LD
        try {
            val r = api.getNvParam(nv = nvName)
            val body = r.body()?.string() ?: ""
            debug.appendLine("GET nv=$nvName → ${body.take(150)}")
            val value = extractJsonField(body, nvName)
            if (value.isNotBlank()) return value
        } catch (e: Exception) {
            debug.appendLine("GET nv=$nvName error: ${e.message}")
        }

        // الطريقة 2: GET ?cmd=LD
        try {
            val r = api.getGenericCmd(cmd = nvName)
            val body = r.body()?.string() ?: ""
            debug.appendLine("GET cmd=$nvName → ${body.take(150)}")
            val value = extractJsonField(body, nvName)
            if (value.isNotBlank()) return value
        } catch (e: Exception) {
            debug.appendLine("GET cmd=$nvName error: ${e.message}")
        }

        // الطريقة 3: جلب كل الإصدارات معاً
        try {
            val r = api.getGenericCmd(cmd = "wa_inner_version,cr_version,LD,RD")
            val body = r.body()?.string() ?: ""
            debug.appendLine("GET multi → ${body.take(200)}")
            val value = extractJsonField(body, nvName)
            if (value.isNotBlank()) return value
        } catch (e: Exception) {
            debug.appendLine("GET multi error: ${e.message}")
        }

        debug.appendLine("⚠️ $nvName not found!")
        return ""
    }

    // ═══════════════════════════════════════════════════════════════
    // حساب AD — من service.js:
    // AD = MD5(MD5(wa_inner_version + cr_version) + RD)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun computeAdParameter(
        api: ZteRouterApi,
        debug: StringBuilder
    ): String {
        try {
            val waInner = fetchNvValue(api, "wa_inner_version", debug)
            val crVersion = fetchNvValue(api, "cr_version", debug)
            debug.appendLine("wa_inner=$waInner, cr=$crVersion")

            if (waInner.isBlank() || crVersion.isBlank()) {
                debug.appendLine("⚠️ No version info for AD")
                return ""
            }

            val rd = fetchNvValue(api, "RD", debug)
            debug.appendLine("RD=$rd")

            if (rd.isBlank()) {
                debug.appendLine("⚠️ No RD for AD")
                return ""
            }

            val step1 = md5(waInner + crVersion)
            val ad = md5(step1 + rd)
            debug.appendLine("AD=$ad (md5(md5($waInner+$crVersion)+$rd))")
            return ad

        } catch (e: Exception) {
            debug.appendLine("AD error: ${e.message}")
            return ""
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // تشفير كلمة المرور — جرّب كل الطرق:
    //
    // من service.js دالة de():
    //   if WEB_ATTR_IF_SUPPORT_SHA256 == 2:
    //     LD = wr({nv:"LD"}).LD
    //     password = SHA256(SHA256(plainPassword) + LD)
    //   else if WEB_ATTR_IF_SUPPORT_SHA256 == 1:
    //     password = Base64(plainPassword)
    //   else:
    //     password = plainPassword
    //
    // نجرب الكل تلقائياً
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun getPasswordEncodings(
        api: ZteRouterApi,
        plainPassword: String,
        debug: StringBuilder
    ): List<Pair<String, String>> {
        val encodings = mutableListOf<Pair<String, String>>()

        // 1. SHA256(SHA256(pass) + LD)
        val ld = fetchNvValue(api, "LD", debug)
        debug.appendLine("LD=$ld")

        if (ld.isNotBlank()) {
            val inner = sha256(plainPassword)
            val outer = sha256(inner + ld)
            debug.appendLine("SHA256+LD: $outer")
            encodings.add("SHA256+LD" to outer)
        }

        // 2. Base64
        val base64 = Base64.encodeToString(
            plainPassword.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        debug.appendLine("Base64: $base64")
        encodings.add("Base64" to base64)

        // 3. Plain text
        encodings.add("Plain" to plainPassword)

        // 4. MD5
        val md5Hash = md5(plainPassword)
        debug.appendLine("MD5: $md5Hash")
        encodings.add("MD5" to md5Hash)

        // 5. SHA256 فقط (بدون LD)
        val sha256Only = sha256(plainPassword)
        debug.appendLine("SHA256: $sha256Only")
        encodings.add("SHA256" to sha256Only)

        return encodings
    }

    // ═══════════════════════════════════════════════════════════════
    // تسجيل الدخول — يجرّب كل طرق التشفير
    // ═══════════════════════════════════════════════════════════════

    suspend fun login(
        routerIp: String,
        username: String,
        password: String
    ): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                // إعادة تعيين كاملة
                RetrofitClient.reset()
                RetrofitClient.setRouterAddress(routerIp)
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()

                debug.appendLine("=== LOGIN START ===")
                debug.appendLine("Router: $routerIp")
                debug.appendLine("User: $username")

                // 1. احصل على الكوكيز من الصفحة الرئيسية
                debug.appendLine("\n--- Get cookies ---")
                try {
                    val mainPage = api.getMainPage()
                    debug.appendLine("Main page: ${mainPage.code()}")
                    readCookies(mainPage, debug)
                } catch (e: Exception) {
                    debug.appendLine("Main page error: ${e.message}")
                }

                // 2. احصل على طرق التشفير المختلفة
                debug.appendLine("\n--- Get encodings ---")
                val encodings = getPasswordEncodings(api, password, debug)
                debug.appendLine("Will try ${encodings.size} encodings")

                // 3. جرب كل طريقة
                for ((label, encodedPass) in encodings) {
                    debug.appendLine("\n=== Try: $label ===")
                    debug.appendLine("Password: ${encodedPass.take(30)}...")

                    try {
                        // أعد تحميل الصفحة للحصول على كوكيز نظيفة
                        if (label != encodings.first().first) {
                            RetrofitClient.reset()
                            RetrofitClient.setRouterAddress(routerIp)
                            val freshApi = RetrofitClient.getApi()
                            try { freshApi.getMainPage() } catch (_: Exception) {}

                            val response = freshApi.login(password = encodedPass)
                            val body = response.body()?.string() ?: ""
                            debug.appendLine("Response: ${body.take(200)}")
                            readCookies(response, debug)

                            val result = handleLoginResult(body, routerIp, username, password, debug)
                            if (result != null) {
                                loginDebug = debug.toString()
                                return@withContext result
                            }
                            continue
                        }

                        val response = api.login(password = encodedPass)
                        val body = response.body()?.string() ?: ""
                        debug.appendLine("Response: ${body.take(200)}")
                        readCookies(response, debug)

                        val result = handleLoginResult(body, routerIp, username, password, debug)
                        if (result != null) {
                            loginDebug = debug.toString()
                            return@withContext result
                        }

                    } catch (e: Exception) {
                        debug.appendLine("$label error: ${e.message}")
                    }
                }

                // 4. فشلت كل المحاولات
                debug.appendLine("\n=== ALL FAILED ===")
                loginDebug = debug.toString()
                Result.failure(Exception("فشل تسجيل الدخول بكل الطرق"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطأ: ${e.message}"))
        }
    }

    // معالجة نتيجة تسجيل الدخول
    private fun handleLoginResult(
        body: String,
        routerIp: String,
        username: String,
        password: String,
        debug: StringBuilder
    ): Result<String>? {
        return when {
            body.contains("\"result\":\"0\"") || body.contains("\"result\":0") -> {
                debug.appendLine("✅ LOGIN SUCCESS!")
                storage.saveCredentials(routerIp, username, password)
                storage.setLoggedIn(true)
                Result.success("تم الاتصال بالراوتر")
            }
            body.contains("\"result\":\"1\"") || body.contains("\"result\":1") -> {
                debug.appendLine("⚠️ Already logged in elsewhere")
                // حاول المتابعة - قد يكون مسجل بالفعل
                storage.saveCredentials(routerIp, username, password)
                storage.setLoggedIn(true)
                Result.success("تم الاتصال (مسجل مسبقاً)")
            }
            body.contains("\"result\":\"3\"") || body.contains("\"result\":3") -> {
                debug.appendLine("❌ Wrong password")
                null // جرب الطريقة التالية
            }
            body.contains("\"result\":\"5\"") || body.contains("\"result\":5") -> {
                debug.appendLine("❌ Duplicate user")
                null // جرب الطريقة التالية
            }
            else -> {
                debug.appendLine("❓ Unknown: $body")
                null // جرب الطريقة التالية
            }
        }
    }

    // ═══════════════════════════════════════════
    // التأكد من تسجيل الدخول
    // ═══════════════════════════════════════════

    private suspend fun ensureLoggedIn(api: ZteRouterApi, debug: StringBuilder) {
        try {
            val loginfoR = api.getGenericCmd(cmd = "loginfo")
            val loginfoBody = loginfoR.body()?.string() ?: ""
            debug.appendLine("loginfo: ${loginfoBody.take(100)}")

            if (loginfoBody.contains("\"loginfo\":\"ok\"") ||
                loginfoBody.contains("\"loginfo\":1")
            ) {
                debug.appendLine("Already logged in")
                return
            }

            debug.appendLine("Re-logging in...")
            val password = storage.getPassword()
            val routerIp = storage.getRouterIp()

            val result = login(routerIp, storage.getUsername(), password)
            debug.appendLine("Re-login: ${result.isSuccess}")

        } catch (e: Exception) {
            debug.appendLine("ensureLoggedIn error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════
    // جلب الأجهزة المتصلة
    // ═══════════════════════════════════════════

    suspend fun getConnectedDevices(): Result<List<Device>> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val routerIp = try {
                    storage.getRouterIp()
                } catch (_: Exception) { "192.168.0.1" }
                val subnet = routerIp.substringBeforeLast(".")

                debug.appendLine("=== DEVICE SCAN ===")

                flushArpCache(debug)
                forceArpEntries(subnet, debug)

                var devices = readArpFromAllSources(debug)
                debug.appendLine("Found: ${devices.size}")

                if (devices.isEmpty()) {
                    devices = readFromRouterApi(debug)
                }

                if (devices.isNotEmpty()) {
                    lastWorkingCommand = "ARP"
                    lastRawResponse = "${devices.size} devices"
                    allCommandsDebug = debug.toString()
                    return@withContext Result.success(devices)
                }

                allCommandsDebug = debug.toString()
                Result.failure(Exception("لم يتم العثور على أجهزة"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("فشل: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // حظر جهاز
    // ═══════════════════════════════════════════════════════════════

    suspend fun blockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()

                debug.appendLine("=== BLOCK $macUpper ===")

                // 1. تأكد من تسجيل الدخول
                ensureLoggedIn(api, debug)

                // 2. اقرأ القائمة الحالية
                val currentAcl = readCurrentACL(api, debug)
                val currentBlackListRaw = currentAcl["BlackMacList"] ?: ""
                val existingMacs = currentBlackListRaw
                    .split(";")
                    .map { it.trim().uppercase() }
                    .filter {
                        it.isNotEmpty() &&
                        it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
                    }
                    .toMutableList()

                // 3. أضف MAC
                if (macUpper !in existingMacs) existingMacs.add(macUpper)
                val newBlackList = existingMacs.joinToString(";") + ";"

                // 4. احسب AD
                val adValue = computeAdParameter(api, debug)
                debug.appendLine("AD=$adValue")

                // 5. أرسل الحظر
                val body = buildString {
                    append("isTest=false")
                    append("&goformId=setDeviceAccessControlList")
                    append("&AclMode=2")
                    append("&BlackMacList=${encodeParam(newBlackList)}")
                    append("&WhiteMacList=")
                    append("&WhiteNameList=")
                    append("&BlackNameList=")
                    if (adValue.isNotBlank()) {
                        append("&AD=${encodeParam(adValue)}")
                    }
                }

                debug.appendLine("Body: $body")

                try {
                    val r = api.postRaw(
                        body.toRequestBody(
                            "application/x-www-form-urlencoded".toMediaType()
                        )
                    )
                    val responseBody = r.body()?.string() ?: ""
                    debug.appendLine("Response: $responseBody")

                    if (isSuccess(responseBody)) {
                        debug.appendLine("✅ BLOCK SUCCESS!")
                        Thread.sleep(1500)
                        val verify = readCurrentACL(api, debug)
                        val verified = (verify["BlackMacList"] ?: "")
                            .uppercase().contains(macUpper)

                        lastRawResponse = debug.toString()
                        allCommandsDebug = debug.toString()
                        return@withContext Result.success("تم حظر $macUpper")
                    }

                    debug.appendLine("❌ BLOCK FAILED")
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext Result.failure(
                        Exception("فشل: $responseBody")
                    )
                } catch (e: Exception) {
                    debug.appendLine("Error: ${e.message}")
                    lastRawResponse = debug.toString()
                    allCommandsDebug = debug.toString()
                    return@withContext Result.failure(
                        Exception("خطأ: ${e.message}")
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطأ: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // إلغاء حظر
    // ═══════════════════════════════════════════════════════════════

    suspend fun unblockDevice(mac: String, currentBlockedList: List<String>): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                val macUpper = mac.uppercase().trim()

                debug.appendLine("=== UNBLOCK $macUpper ===")
                ensureLoggedIn(api, debug)

                val currentAcl = readCurrentACL(api, debug)
                val currentBlackListRaw = currentAcl["BlackMacList"] ?: ""

                val existingMacs = currentBlackListRaw
                    .split(";")
                    .map { it.trim().uppercase() }
                    .filter {
                        it.isNotEmpty() &&
                        it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
                    }
                    .toMutableList()

                existingMacs.remove(macUpper)
                val newAclMode = if (existingMacs.isEmpty()) "0" else "2"
                val newBlackList = if (existingMacs.isEmpty()) ""
                    else existingMacs.joinToString(";") + ";"

                val adValue = computeAdParameter(api, debug)

                val body = buildString {
                    append("isTest=false")
                    append("&goformId=setDeviceAccessControlList")
                    append("&AclMode=$newAclMode")
                    append("&BlackMacList=${encodeParam(newBlackList)}")
                    append("&WhiteMacList=")
                    append("&WhiteNameList=")
                    append("&BlackNameList=")
                    if (adValue.isNotBlank()) {
                        append("&AD=${encodeParam(adValue)}")
                    }
                }

                val r = api.postRaw(
                    body.toRequestBody(
                        "application/x-www-form-urlencoded".toMediaType()
                    )
                )
                val responseBody = r.body()?.string() ?: ""

                lastRawResponse = debug.toString()
                allCommandsDebug = debug.toString()

                if (isSuccess(responseBody)) {
                    Result.success("تم إلغاء حظر $macUpper")
                } else {
                    Result.failure(Exception("فشل: $responseBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطأ: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // جلب قائمة المحظورين
    // ═══════════════════════════════════════════

    suspend fun getBlockedMacs(): Result<List<String>> {
        return try {
            withContext(Dispatchers.IO) {
                val api = RetrofitClient.getApi()
                val debug = StringBuilder()
                ensureLoggedIn(api, debug)

                val aclData = readCurrentACL(api, debug)
                val blackListRaw = aclData["BlackMacList"] ?: ""

                val macs = blackListRaw
                    .split(";")
                    .map { it.trim().uppercase() }
                    .filter {
                        it.isNotEmpty() &&
                        it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
                    }

                allCommandsDebug = debug.toString()
                Result.success(macs)
            }
        } catch (e: Exception) {
            Result.failure(Exception("فشل: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // قراءة ACL
    // ═══════════════════════════════════════════

    private suspend fun readCurrentACL(
        api: ZteRouterApi,
        debug: StringBuilder
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val r = api.getGenericCmd(cmd = "queryDeviceAccessControlList")
            val body = r.body()?.string() ?: ""
            debug.appendLine("ACL raw: ${body.take(200)}")

            for (key in listOf(
                "AclMode", "BlackMacList", "WhiteMacList",
                "WhiteNameList", "BlackNameList"
            )) {
                Regex(""""$key"\s*:\s*"([^"]*?)"""").find(body)?.let {
                    result[key] = it.groupValues[1]
                }
            }
        } catch (e: Exception) {
            debug.appendLine("ACL error: ${e.message}")
        }
        return result
    }

    // ═══════════════════════════════════════════
    // اختبار الاتصال
    // ═══════════════════════════════════════════

    suspend fun testRouterConnection(): Result<String> {
        return try {
            withContext(Dispatchers.IO) {
                val debug = StringBuilder()
                val api = RetrofitClient.getApi()

                debug.appendLine("=== TEST ===")

                try {
                    val r = api.getGenericCmd(cmd = "Language")
                    debug.appendLine("Language: ${r.body()?.string()}")
                } catch (e: Exception) {
                    debug.appendLine("Error: ${e.message}")
                }

                debug.appendLine("\n=== AD TEST ===")
                val ad = computeAdParameter(api, debug)
                debug.appendLine("AD=$ad")

                debug.appendLine("\n=== ACL TEST ===")
                val acl = readCurrentACL(api, debug)
                debug.appendLine("ACL=$acl")

                Result.success(debug.toString())
            }
        } catch (e: Exception) {
            Result.failure(Exception("Test: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════
    // تسجيل الخروج
    // ═══════════════════════════════════════════

    suspend fun logout() {
        try {
            withContext(Dispatchers.IO) {
                try {
                    RetrofitClient.getApi().logout()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        storage.setLoggedIn(false)
        RetrofitClient.reset()
    }

    // ═══════════════════════════════════════════
    // أدوات ARP
    // ═══════════════════════════════════════════

    private fun flushArpCache(debug: StringBuilder) {
        try {
            Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "ip neigh flush dev wlan0")
            ).waitFor()
        } catch (_: Exception) {}
    }

    private fun forceArpEntries(subnet: String, debug: StringBuilder) {
        try {
            for (i in 1..50) {
                try {
                    val s = java.net.Socket()
                    s.connect(
                        java.net.InetSocketAddress("$subnet.$i", 80), 30
                    )
                    s.close()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private suspend fun readArpFromAllSources(
        debug: StringBuilder
    ): List<Device> {
        var d = readIpNeigh(debug)
        if (d.isNotEmpty()) return d
        d = readArpFromFile()
        if (d.isNotEmpty()) return d
        return readArpFromCommand("cat /proc/net/arp")
    }

    private fun readIpNeigh(debug: StringBuilder): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "ip neigh")
            )
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) {
                if (!line.uppercase().contains("FAILED") &&
                    !line.uppercase().contains("INCOMPLETE")
                ) {
                    parseArpLine(line)?.let { devices.add(it) }
                }
                line = r.readLine()
            }
            p.waitFor()
        } catch (_: Exception) {}
        return devices
    }

    private fun readArpFromFile(): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val f = java.io.File("/proc/net/arp")
            if (!f.exists() || !f.canRead()) return emptyList()
            val r = BufferedReader(InputStreamReader(f.inputStream()))
            r.readLine()
            var line = r.readLine()
            while (line != null) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4 &&
                    parts[3].uppercase() != "00:00:00:00:00:00" &&
                    parts[2] != "0x0"
                ) {
                    devices.add(makeDevice(parts[0], parts[3].uppercase()))
                }
                line = r.readLine()
            }
            r.close()
        } catch (_: Exception) {}
        return devices
    }

    private fun readArpFromCommand(command: String): List<Device> {
        val devices = mutableListOf<Device>()
        try {
            val p = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", command)
            )
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var line = r.readLine()
            while (line != null) {
                parseArpLine(line)?.let { devices.add(it) }
                line = r.readLine()
            }
            p.waitFor()
            p.destroy()
        } catch (_: Exception) {}
        return devices
    }

    private fun parseArpLine(line: String): Device? {
        val mac = Regex(
            "[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-]" +
            "[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}[:\\-]" +
            "[0-9A-Fa-f]{2}[:\\-][0-9A-Fa-f]{2}"
        ).find(line)?.value?.uppercase() ?: return null
        if (mac == "00:00:00:00:00:00") return null
        val ip = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
            .find(line)?.value ?: return null
        return makeDevice(ip, mac)
    }

    private suspend fun readFromRouterApi(
        debug: StringBuilder
    ): List<Device> {
        try {
            val api = RetrofitClient.getApi()
            for (cmd in listOf(
                "station_list", "wifi_station_list", "dhcp_list"
            )) {
                try {
                    val r = api.getGenericCmd(cmd = cmd)
                    val b = r.body()?.string() ?: ""
                    debug.appendLine("  [$cmd]: ${b.take(100)}")
                    if (b.length > 30) {
                        val d = parseDevices(b)
                        if (d.isNotEmpty()) return d
                    }
                } catch (e: Exception) {
                    debug.appendLine("  [$cmd] error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            debug.appendLine("API error: ${e.message}")
        }
        return emptyList()
    }

    private fun makeDevice(ip: String, mac: String): Device {
        val rIp = try {
            storage.getRouterIp()
        } catch (_: Exception) { "" }
        return Device(
            mac = mac,
            ip = ip,
            hostname = nameFor(ip, mac),
            connectionType = if (ip == rIp) "Router" else "WiFi"
        )
    }

    private fun nameFor(ip: String, mac: String): String {
        val v = when {
            mac.startsWith("A4:83") || mac.startsWith("F0:18") -> "Apple"
            mac.startsWith("CC:96") || mac.startsWith("58:48") -> "Huawei"
            mac.startsWith("70:F9") || mac.startsWith("94:B8") -> "Samsung"
            mac.startsWith("6C:B0") || mac.startsWith("54:FA") -> "Xiaomi"
            mac.startsWith("00:21") -> "ZTE"
            else -> ""
        }
        val s = ip.substringAfterLast(".")
        return when {
            v.isNotBlank() -> "$v ($s)"
            s == "1" -> "الراوتر"
            else -> "جهاز .$s"
        }
    }

    private fun parseDevices(raw: String): List<Device> {
        try {
            val macs = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
                .findAll(raw)
                .map { it.value.uppercase() }
                .distinct()
                .toList()
            if (macs.isEmpty()) return emptyList()
            val ips = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
                .findAll(raw)
                .map { it.value }
                .toList()
            return macs.mapIndexed { i, mac ->
                Device(
                    mac = mac,
                    ip = ips.getOrNull(i) ?: "",
                    hostname = "جهاز ${i + 1}",
                    connectionType = "WiFi"
                )
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }
}
