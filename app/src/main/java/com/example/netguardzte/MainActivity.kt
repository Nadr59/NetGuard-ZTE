package com.example.netguardzte

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.netguardzte.ui.screens.blocked.BlockedScreen
import com.example.netguardzte.ui.screens.devices.DevicesScreen
import com.example.netguardzte.ui.screens.login.LoginScreen
import com.example.netguardzte.ui.screens.traffic.TrafficScreen
import com.example.netguardzte.ui.theme.NetGuardTheme
import com.example.netguardzte.ui.viewmodel.NetGuardViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NetGuardTheme {
                val vm: NetGuardViewModel = viewModel()
                val s by vm.uiState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(s.message) {
                    s.message?.let {
                        snackbarHostState.showSnackbar(it)
                        vm.onMessageShown()
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { padding ->
                    Spacer(Modifier.height(0.dp))

                    when (s.currentScreen) {
                        "login" -> LoginScreen(
                            viewModel = vm,
                            onLoginSuccess = { vm.navigateTo("devices") }
                        )

                        "devices" -> DevicesScreen(
                            devices = s.devices,
                            blockedMacs = s.blockedMacs,
                            isLoading = s.isLoadingDevices,
                            error = s.deviceError,
                            showBlockDialog = s.showBlockDialog,
                            showDebugInfo = s.showDebugInfo,
                            debugInfo = s.debugInfo,
                            isTestingRouter = s.isTestingRouter,
                            onRefresh = { vm.loadDevices() },
                            onBlockClicked = { vm.onBlockClicked(it) },
                            onBlockConfirmed = { vm.onBlockConfirmed() },
                            onBlockCancelled = { vm.onBlockCancelled() },
                            onUnblockClicked = { vm.onUnblockClicked(it) },
                            onToggleDebug = { vm.toggleDebugInfo() },
                            onTestRouter = { vm.testRouter() },
                            onShowTraffic = { vm.navigateTo("traffic") },
                            onLogout = { vm.logout() }
                        )

                        "blocked" -> BlockedScreen(
                            blockedMacs = s.blockedMacs,
                            showUnblockDialog = s.showUnblockDialog,
                            onBack = { vm.navigateTo("devices") },
                            onUnblock = { vm.onUnblockClicked(it) },
                            onUnblockConfirmed = { vm.onUnblockConfirmed() },
                            onUnblockCancelled = { vm.onUnblockCancelled() }
                        )

                        "traffic" -> TrafficScreen(
                            viewModel = vm,
                            onBack = { vm.navigateTo("devices") }
                        )
                    }
                }
            }
        }
    }
}
