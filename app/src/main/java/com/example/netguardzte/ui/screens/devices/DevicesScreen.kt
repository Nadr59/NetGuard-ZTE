package com.example.netguardzte.ui.screens.devices

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netguardzte.domain.model.Device

import com.example.netguardzte.ui.components.DeviceCard
import androidx.compose.animation.core.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    devices: List<Device>,
    isLoading: Boolean,
    error: String?,
    showBlockDialog: Device?,
    showDebugInfo: Boolean,
    debugInfo: String,
    isTestingRouter: Boolean,
    onRefresh: () -> Unit,
    onBlock: (Device) -> Unit,
    onUnblock: (String) -> Unit,
    onBlockConfirmed: () -> Unit,
    onBlockCancelled: () -> Unit,
    onNavigateToBlocked: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onToggleDebug: () -> Unit,
    onTestRouter: () -> Unit
) {
    val context = LocalContext.current
    var copyMessage by remember { mutableStateOf<String?>(null) }

    fun copyDebug() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Debug Info", debugInfo)
            clipboard.setPrimaryClip(clip)
            copyMessage = "تم النسخ"
        } catch (e: Exception) {
            copyMessage = "فشل النسخ: ${e.message}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الأجهزة المتصلة", fontWeight = FontWeight.Bold) },
                actions = {
                    
                    IconButton(onClick = onTestRouter) {
                        Text("🧪", fontSize = 18.sp)
                    }
                    IconButton(onClick = onToggleDebug) {
                        Text("🔍", fontSize = 18.sp)
                    }
                    IconButton(onClick = onNavigateToBlocked) {
                        Icon(Icons.Default.Block, "المحظورون")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, "تحديث")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // بدلاً من CircularProgressIndicator()
var dots by remember { mutableStateOf("") }
LaunchedEffect(Unit) {
    while (true) {
        dots = when (dots.length) {
            3 -> ""
            else -> "$dots."
        }
        kotlinx.coroutines.delay(500)
    }
}
Text("⏳ جاري التحميل$dots")
                    }
                }

                error != null -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(32.dp))
                        Text("⚠️", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = error,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRefresh) {
                            Text("إعادة المحاولة")
                        }
                        if (isTestingRouter) {
                            Spacer(Modifier.height(16.dp))
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("جاري اختبار الراوتر...")
                        }
                        if (showDebugInfo && debugInfo.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            DebugCard(debugInfo, onCopy = { copyDebug() }, copyMessage = copyMessage)
                        }
                    }
                }

                devices.isEmpty() -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(48.dp))
                        Text("📡", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "لا توجد أجهزة متصلة",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRefresh) {
                            Text("تحديث")
                        }
                        if (isTestingRouter) {
                            Spacer(Modifier.height(16.dp))
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("جاري اختبار الراوتر...")
                        }
                        if (showDebugInfo && debugInfo.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            DebugCard(debugInfo, onCopy = { copyDebug() }, copyMessage = copyMessage)
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${devices.size} جهاز متصل",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        items(items = devices, key = { it.mac }) { device ->
                            DeviceCard(
                                device = device,
                                onBlock = { onBlock(device) },
                                onUnblock = { onUnblock(device.mac) }
                            )
                        }

                        if (isTestingRouter) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("جاري اختبار الراوتر...")
                                }
                            }
                        }

                        if (showDebugInfo && debugInfo.isNotBlank()) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                DebugCard(debugInfo, onCopy = { copyDebug() }, copyMessage = copyMessage)
                            }
                        }

                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }

    showBlockDialog?.let { device ->
        AlertDialog(
            onDismissRequest = onBlockCancelled,
            icon = {
                Icon(
                    Icons.Default.Block, null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("حظر الجهاز؟", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("هل تريد حظر هذا الجهاز من الشبكة؟")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${device.hostname}\nIP: ${device.ip}\nMAC: ${device.mac}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onBlockConfirmed) {
                    Text("حظر", color = MaterialTheme.colorScheme.error)
                }
            },
            // ابحث عن الأزرار الموجودة وأضف:
Button(onClick = { viewModel.discoverTraffic() }) {
    Text("اكتشاف أوامر البيانات")
},
            dismissButton = {
                TextButton(onClick = onBlockCancelled) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun DebugCard(debugInfo: String, onCopy: () -> Unit, copyMessage: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🔍 Debug Info",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                TextButton(onClick = onCopy) {
                    Text("📋 نسخ الكل", fontSize = 13.sp)
                }
            }

            copyMessage?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Spacer(Modifier.height(4.dp))

            SelectionContainer {
                Text(
                    text = debugInfo,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
