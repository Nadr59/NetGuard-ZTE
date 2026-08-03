package com.example.netguardzte.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netguardzte.App
import com.example.netguardzte.data.api.RetrofitClient
import com.example.netguardzte.ui.viewmodel.NetGuardViewModel

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

    val context = LocalContext.current
    val executor = (context.applicationContext as App).commandExecutor

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("NetGuard ZTE", fontSize = 28.sp, color = Color(0xFFE8C547))
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

                RetrofitClient.setRouterAddress(routerIp)

                Button(
    onClick = {
        if (password.isBlank()) {
            statusMessage = "اكتب كلمة المرور"
            return@Button
        }
        isLoading = true
        statusMessage = "جاري تحميل الصفحة..."

        RetrofitClient.setRouterAddress(routerIp)

        // ═══ أعد تهيئة WebView ═══
        executor.init(routerIp) {
            // ═══ انتظر 3 ثواني حتى تُحمَّل كل الملفات ═══
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                statusMessage = "جاري تسجيل الدخول..."

                executor.executeLogin(routerIp, password) { ok, msg ->
                    isLoading = false
                    if (ok) {
                        viewModel.saveCredentials(routerIp, username, password)
                        statusMessage = "تم الاتصال!"
                        onLoginSuccess()
                    } else {
                        statusMessage = "فشل: $msg"
                    }
                }
            }, 3000) // انتظر 3 ثواني
        }
    },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8C547)),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.Black,
                    strokeWidth = 2.dp
                )
            } else {
                Text("تسجيل الدخول", color = Color.Black, fontSize = 18.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (statusMessage.isNotBlank()) {
            Text(statusMessage, color = Color.White, fontSize = 14.sp)
        }
    }
}
