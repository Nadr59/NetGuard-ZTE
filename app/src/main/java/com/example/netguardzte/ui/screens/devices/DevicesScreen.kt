package com.example.netguardzte.ui.screens.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    showUnblockDialog: String?,
    showDebugInfo: Boolean,
    debugInfo: String,
    isTestingRouter: Boolean,
    onRefresh: () -> Unit,
    onBlockClicked: (Device) -> Unit,
    onBlockConfirmed: () -> Unit,
    onBlockCancelled: () -> Unit,
    onUnblockClicked: (String) -> Unit,
    onUnblockConfirmed: () -> Unit,
    onUnblockCancelled: () -> Unit,
    onToggleDebug: () -> Unit,
    onTestRouter: () -> Unit,
    onShowTraffic: () -> Unit,
    onLogout: () -> Unit
) {
    val connectedMacs = devices.map { it.mac.uppercase() }
    val disconnectedBlocked = blockedMacs.filter { mac ->
        mac.uppercase() !in connectedMacs
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "الأجهزة المتصلة",
                fontSize = 22.sp,
                color = Color(0xFFE8C547),
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = Color.White)
                }
                IconButton(onClick = onLogout) {
                    Icon(Icons.Default.ExitToApp, "Logout", tint = Color.White)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = onTestRouter,
                enabled = !isTestingRouter,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                if (isTestingRouter) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text("اختبار", color = Color.White, fontSize = 12.sp)
                }
            }

            Button(
                onClick = onShowTraffic,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text("البيانات", color = Color.White, fontSize = 12.sp)
            }

            Button(
                onClick = onToggleDebug,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text("Debug", color = Color.White, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        error?.let {
            Text(it, color = Color.Red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(4.dp))
        }

        if (showDebugInfo && debugInfo.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(max = 180.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    item {
                        Text(debugInfo, color = Color(0xFF888888), fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Text(
            "${devices.size} جهاز متصل",
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (disconnectedBlocked.isNotEmpty()) {
            Text(
                "${disconnectedBlocked.size} جهاز محظور غير متصل",
                color = Color.Red,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFE8C547))
            }
        } else if (devices.isEmpty() && disconnectedBlocked.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("لا توجد أجهزة متصلة", color = Color.Gray, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8C547))
                    ) {
                        Text("تحديث", color = Color.Black)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(devices) { device ->
                    DeviceCard(
                        device = device,
                        isBlocked = blockedMacs.any { it.uppercase() == device.mac.uppercase() },
                        onBlock = { onBlockClicked(device) },
                        onUnblock = { onUnblockClicked(device.mac) }
                    )
                }

                if (disconnectedBlocked.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "أجهزة محظورة غير متصلة",
                            color = Color(0xFFE8C547),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    items(disconnectedBlocked) { mac ->
                        BlockedDeviceCard(
                            mac = mac,
                            onUnblock = { onUnblockClicked(mac) }
                        )
                    }
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

    // ═══ حوار فك الحظر ═══
    if (showUnblockDialog != null) {
        AlertDialog(
            onDismissRequest = onUnblockCancelled,
            title = { Text("إلغاء الحظر") },
            text = {
                Text("هل تريد إلغاء حظر هذا الجهاز؟\nMAC: $showUnblockDialog")
            },
            confirmButton = {
                TextButton(onClick = onUnblockConfirmed) {
                    Text("إلغاء الحظر", color = Color(0xFF4CAF50))
                }
            },
            dismissButton = {
                TextButton(onClick = onUnblockCancelled) {
                    Text("تراجع")
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
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.hostname,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text("IP: ${device.ip}", color = Color.Gray, fontSize = 12.sp)
                Text("MAC: ${device.mac}", color = Color.Gray, fontSize = 11.sp)
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("إلغاء الحظر", color = Color.White, fontSize = 11.sp)
                    }
                } else {
                    Button(
                        onClick = onBlock,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("حظر", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun BlockedDeviceCard(
    mac: String,
    onUnblock: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A1A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "جهاز محظور",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text("MAC: $mac", color = Color.Gray, fontSize = 11.sp)
                Text(
                    "غير متصل",
                    color = Color.Red,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onUnblock,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("إلغاء الحظر", color = Color.White, fontSize = 11.sp)
            }
        }
    }
}
