package com.example.netguardzte.ui.screens.traffic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netguardzte.domain.model.DeviceTraffic
import com.example.netguardzte.ui.viewmodel.NetGuardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficScreen(
    viewModel: NetGuardViewModel,
    onBack: () -> Unit
) {
    val s by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadTraffic()
    }

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
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                "استهلاك البيانات",
                fontSize = 24.sp,
                color = Color(0xFFE8C547),
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { viewModel.loadTraffic() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (s.isLoadingTraffic) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFFE8C547))
            }
        } else if (s.trafficData.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "لا توجد بيانات",
                    color = Color.Gray,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(s.trafficData) { device ->
                    TrafficCard(
                        device = device,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
fun TrafficCard(
    device: DeviceTraffic,
    viewModel: NetGuardViewModel
) {
    val todayUsage = viewModel.getDeviceTodayUsage(device.mac)
    val monthUsage = viewModel.getDeviceMonthUsage(device.mac)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // ═══ اسم الجهاز ═══
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    device.hostname,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    device.ssidIndex,
                    color = Color(0xFFE8C547),
                    fontSize = 12.sp
                )
            }

            Text(
                "IP: ${device.ip} | MAC: ${device.mac}",
                color = Color.Gray,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ═══ السرعة الحالية ═══
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("التحميل", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        viewModel.formatBytes(device.rxSpeed) + "/s",
                        color = Color(0xFF4CAF50),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("الرفع", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        viewModel.formatBytes(device.txSpeed) + "/s",
                        color = Color(0xFF2196F3),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ═══ إجمالي الاستهلاك ═══
            Divider(color = Color(0xFF333333))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("إجمالي التحميل", color = Color.Gray, fontSize = 11.sp)
                    Text(
                        viewModel.formatBytes(device.rxTotal),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("إجمالي الرفع", color = Color.Gray, fontSize = 11.sp)
                    Text(
                        viewModel.formatBytes(device.txTotal),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("الإشارة", color = Color.Gray, fontSize = 11.sp)
                    Text(
                        "${device.rssi} dBm",
                        color = when {
                            device.rssi > -50 -> Color(0xFF4CAF50)
                            device.rssi > -70 -> Color(0xFFFFC107)
                            else -> Color(0xFFF44336)
                        },
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ═══ اليوم والشهر ═══
            if (todayUsage.first > 0 || todayUsage.second > 0 ||
                monthUsage.first > 0 || monthUsage.second > 0
            ) {
                Divider(color = Color(0xFF333333))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("اليوم", color = Color(0xFFE8C547), fontSize = 12.sp)
                        Text(
                            "↓${viewModel.formatBytes(todayUsage.first)} ↑${viewModel.formatBytes(todayUsage.second)}",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("الشهر", color = Color(0xFFE8C547), fontSize = 12.sp)
                        Text(
                            "↓${viewModel.formatBytes(monthUsage.first)} ↑${viewModel.formatBytes(monthUsage.second)}",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
