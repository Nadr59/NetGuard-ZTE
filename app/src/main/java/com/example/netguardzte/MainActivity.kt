package com.example.netguardzte

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.netguardzte.ui.screens.blocked.BlockedScreen
import com.example.netguardzte.ui.screens.devices.DevicesScreen
import com.example.netguardzte.ui.screens.login.LoginScreen
import com.example.netguardzte.ui.theme.NetGuardTheme
import com.example.netguardzte.ui.viewmodel.NetGuardViewModel
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

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
                ) {
                    when (s.currentScreen) {
                        "login" -> LoginScreen(
                            routerIp = s.routerIp,
                            username = s.username,
                            password = s.password,
                            isLoggingIn = s.isLoggingIn,
                            error = s.loginError,
                            onRouterIpChanged = { vm.onRouterIpChanged(it) },
                            onUsernameChanged = { vm.onUsernameChanged(it) },
                            onPasswordChanged = { vm.onPasswordChanged(it) },
                            onLogin = { vm.login() }
                        )

                        "devices" -> DevicesScreen(
                            devices = s.devices,
                            isLoading = s.isLoadingDevices,
                            error = s.deviceError,
                            showBlockDialog = s.showBlockDialog,
                            onRefresh = { vm.loadDevices() },
                            onBlock = { vm.onBlockClicked(it) },
                            onUnblock = { vm.onUnblockClicked(it) },
                            onBlockConfirmed = { vm.onBlockConfirmed() },
                            onBlockCancelled = { vm.onBlockCancelled() },
                            onNavigateToBlocked = { vm.navigateTo("blocked") },
                            onNavigateToSettings = { vm.navigateTo("settings") }
                        )

                        "blocked" -> BlockedScreen(
                            blockedMacs = s.blockedMacs,
                            showUnblockDialog = s.showUnblockDialog,
                            onBack = { vm.navigateTo("devices") },
                            onUnblock = { vm.onUnblockClicked(it) },
                            onUnblockConfirmed = { vm.onUnblockConfirmed() },
                            onUnblockCancelled = { vm.onUnblockCancelled() }
                        )
                    }
                }
            }
        }
    }
}
