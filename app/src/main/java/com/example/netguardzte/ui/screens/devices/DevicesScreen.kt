package com.example.netguardzte.ui.screens.devices

import androidx.compose.foundation.background
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
    ) {
        // ═══ العنوان ثابت ═══
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

        // ═══ أزرار ثابتة ═══
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
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
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

        // ═══ الخطأ ═══
        error?.let {
            Text(it, color = Color.Red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(4.dp))
        }

        // ═══ Debug ═══
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

        // ═══ عدد الأجهزة ═══
        Text(
            "${devices.size} جهاز متصل | ${blockedMacs.size} محظور",
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(8.dp))

        // ═══ قائمة الأجهزة قابلة للتمرير ═══
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFE8C547))
            }
        } else if (devices.isEmpty()) {
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
                    Text("محظور", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
}package com.example.netguardzte.ui.screens.devices

import androidx.compose.foundation.background
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
    ) {
        // ═══ العنوان ثابت ═══
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

        // ═══ أزرار ثابتة ═══
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
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
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

        // ═══ الخطأ ═══
        error?.let {
            Text(it, color = Color.Red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(4.dp))
        }

        // ═══ Debug ═══
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

        // ═══ عدد الأجهزة ═══
        Text(
            "${devices.size} جهاز متصل | ${blockedMacs.size} محظور",
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(8.dp))

        // ═══ قائمة الأجهزة قابلة للتمرير ═══
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFE8C547))
            }
        } else if (devices.isEmpty()) {
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
                    Text("محظور", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
