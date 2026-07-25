package com.example.netguardzte.ui.screens.devices

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netguardzte.domain.model.Device
import com.example.netguardzte.ui.components.DeviceCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    devices: List<Device>,
    isLoading: Boolean,
    error: String?,
    showBlockDialog: Device?,
    showDebugInfo: Boolean,
    debugInfo: String,
    onRefresh: () -> Unit,
    onBlock: (Device) -> Unit,
    onUnblock: (String) -> Unit,
    onBlockConfirmed: () -> Unit,
    onBlockCancelled: () -> Unit,
    onNavigateToBlocked: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onToggleDebug: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الأجهزة المتصلة", fontWeight = FontWeight.Bold) },
                actions = {
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
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("جاري البحث عن الأجهزة...")
                    }
                }

                error != null -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("⚠️", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = error,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRefresh) {
                            Text("إعادة المحاولة")
                        }

                        // ═══ عرض معلومات التشخيص عند الخطأ ═══
                        if (debugInfo.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "🔍 Debug Info",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = debugInfo,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }

                devices.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("📡", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "لا توجد أجهزة متصلة",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "اسحب للأسفل للتحديث",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // ═══ عرض معلومات التشaygı عند عدم وجود أجهزة ═══
                        if (showDebugInfo && debugInfo.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "🔍 Debug Info",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = debugInfo,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    )
                                }
                            }
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
                                Spacer(Modifier.weight(1f))
                                if (showDebugInfo) {
                                    Text(
                                        text = "CMD: $debugInfo",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        items(items = devices, key = { it.mac }) { device ->
                            DeviceCard(
                                device = device,
                                onBlock = { onBlock(device) },
                                onUnblock = { onUnblock(device.mac) }
                            )
                        }

                        // ═══ بطاقة التشخيص في أسفل القائمة ═══
                        if (showDebugInfo && debugInfo.isNotBlank()) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            "🔍 Debug Info",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = debugInfo,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }

    // ═══ مربع حوار تأكيد الحظر ═══
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
            dismissButton = {
                TextButton(onClick = onBlockCancelled) {
                    Text("إلغاء")
                }
            }
        )
    }
}
