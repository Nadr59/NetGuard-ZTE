package com.example.netguardzte.ui.screens.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netguardzte.domain.model.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    devices: List<Device>,
    blockedMacs: List<String>,
    isLoading: Boolean,
    error: String?,
    showBlockDialog: Device?,
    showDebugInfo: Boolean,
    debugInfo: String,
    isTestingRouter: Boolean,
    onRefresh: () -> Unit,
    onBlockClicked: (Device) -> Unit,
    onBlockConfirmed: () -> Unit,
    onBlockCancelled: () -> Unit,
    onUnblockClicked: (String) -> Unit,
    onToggleDebug: () -> Unit,
    onTestRouter: () -> Unit,
    onShowTraffic: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(16.dp)
    ) {
        // ═══ العنوان ═══
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "الأجهزة المتصلة",
                fontSize = 24.sp,
                color = Color(0xFFE8C547),
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = Color.White
                    )
                }
                IconButton(onClick = onLogout) {
                    Icon(
                        Icons.Default.ExitToApp,
                        contentDescription = "Logout",
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ═══ أزرار ═══
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onTestRouter,
                enabled = !isTestingRouter,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF333333)
                ),
                modifier = Modifier.weight(1f)
            ) {
                if (isTestingRouter) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text("اختبار", color = Color.White)
                }
            }

            
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ═══ الخطأ ═══
        error?.let {
            Text(
                it,
                color = Color.Red,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        // ═══ Debug ═══
        if (showDebugInfo && debugInfo.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A1A)
                )
            ) {
                LazyColumn(
                    modifier = Modifier.padding(8.dp)
                ) {
                    item {
                        Text(
                            debugInfo,
                            color = Color(0xFF888888),
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ═══ قائمة الأجهزة ═══
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFFE8C547))
            }
        } else if (devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "لا توجد أجهزة متصلة",
                    color = Color.Gray,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { device ->
                    DeviceCard(
                        device = device,
                        isBlocked = blockedMacs.any {
                            it.uppercase() == device.mac.uppercase()
                        },
                        onBlock = { onBlockClicked(device) },
                        onUnblock = { onUnblockClicked(device.mac) }
                    )
                }
            }
        }
    }

    // ═══ حوار الحظر ═══
    if (showBlockDialog != null) {
        AlertDialog(
            onDismissRequest = onBlockCancelled,
            title = { Text("حظر الجهاز") },
            text = {
                Text("هل تريد حظر ${showBlockDialog.hostname}؟\nMAC: ${showBlockDialog.mac}")
            },
            confirmButton = {
                TextButton(onClick = onBlockConfirmed) {
                    Text("حظر", color = Color.Red)
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

@Composable
fun DeviceCard(
    device: Device,
    isBlocked: Boolean,
    onBlock: () -> Unit,
    onUnblock: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isBlocked) Color(0xFF2A1A1A) else Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.hostname,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "IP: ${device.ip}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Text(
                    "MAC: ${device.mac}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                if (isBlocked) {
                    Text(
                        "محظور",
                        color = Color.Red,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (device.connectionType != "Router") {
                if (isBlocked) {
                    Button(
                        onClick = onUnblock,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("إلغاء الحظر", color = Color.White, fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onBlock,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC62828)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("حظر", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
