package com.example.netguardzte.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.data.repository.RouterRepository
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NetGuardUiState(
    val currentScreen: String = "login",
    val routerIp: String = "192.168.0.1",
    val username: String = "admin",
    val password: String = "",
    val isLoggingIn: Boolean = false,
    val loginError: String? = null,
    val devices: List<Device> = emptyList(),
    val blockedMacs: List<String> = emptyList(),
    val isLoadingDevices: Boolean = false,
    val deviceError: String? = null,
    val message: String? = null,
    val showBlockDialog: Device? = null,
    val showUnblockDialog: String? = null,
    val showDebugInfo: Boolean = false,
    val debugInfo: String = "",
    val isTestingRouter: Boolean = false
)

class NetGuardViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = SecureStorage(application)
    private val repository = RouterRepository(storage)

    private val _uiState = MutableStateFlow(NetGuardUiState())
    val uiState: StateFlow<NetGuardUiState> = _uiState.asStateFlow()

    init {
        try {
            val ip = storage.getRouterIp()
            val uname = storage.getUsername()
            val loggedIn = storage.isLoggedIn() && storage.hasCredentials()

            _uiState.value = NetGuardUiState(
                routerIp = ip,
                username = uname,
                currentScreen = if (loggedIn) "devices" else "login"
            )

            if (loggedIn) {
                loadDevices()
            }
        } catch (e: Exception) {
            _uiState.value = NetGuardUiState()
        }
    }

    fun onRouterIpChanged(ip: String) {
        _uiState.value = _uiState.value.copy(routerIp = ip, loginError = null)
    }

    fun onUsernameChanged(u: String) {
        _uiState.value = _uiState.value.copy(username = u, loginError = null)
    }

    fun onPasswordChanged(p: String) {
        _uiState.value = _uiState.value.copy(password = p, loginError = null)
    }

    fun login() {
        val s = _uiState.value
        if (s.password.isBlank()) {
            _uiState.value = s.copy(loginError = "أدخل كلمة المرور")
            return
        }
        _uiState.value = s.copy(isLoggingIn = true, loginError = null)

        viewModelScope.launch {
            try {
                val result = repository.login(s.routerIp, s.username, s.password)
                result.fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            isLoggingIn = false,
                            currentScreen = "devices",
                            debugInfo = repository.loginDebug
                        )
                        loadDevices()
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoggingIn = false,
                            loginError = error.message,
                            debugInfo = repository.loginDebug
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    loginError = "خطأ: ${e.message}"
                )
            }
        }
    }

    fun loadDevices() {
        _uiState.value = _uiState.value.copy(isLoadingDevices = true, deviceError = null)

        viewModelScope.launch {
            try {
                val devicesResult = repository.getConnectedDevices()
                val blockedResult = try { repository.getBlockedMacs() } catch (_: Exception) { Result.success(emptyList()) }

                val devices = devicesResult.getOrNull() ?: emptyList()
                val blocked = blockedResult.getOrNull() ?: emptyList()

                val withBlockStatus = devices.map { device ->
                    device.copy(
                        isBlocked = blocked.any { b -> b.uppercase() == device.mac.uppercase() }
                    )
                }

                _uiState.value = _uiState.value.copy(
                    isLoadingDevices = false,
                    devices = withBlockStatus,
                    blockedMacs = blocked,
                    deviceError = devicesResult.exceptionOrNull()?.message,
                    debugInfo = repository.allCommandsDebug
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingDevices = false,
                    deviceError = "خطأ: ${e.message}",
                    debugInfo = e.stackTraceToString().take(500)
                )
            }
        }
    }

    fun testRouter() {
        _uiState.value = _uiState.value.copy(isTestingRouter = true)

        viewModelScope.launch {
            try {
                val result = repository.testRouterConnection()
                result.fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            isTestingRouter = false,
                            showDebugInfo = true,
                            debugInfo = it
                        )
                    },
                    onFailure = {
                        _uiState.value = _uiState.value.copy(
                            isTestingRouter = false,
                            showDebugInfo = true,
                            debugInfo = "Error: ${it.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTestingRouter = false,
                    showDebugInfo = true,
                    debugInfo = "Crash: ${e.message}"
                )
            }
        }
    }

    fun onBlockClicked(device: Device) {
        _uiState.value = _uiState.value.copy(showBlockDialog = device)
    }

    fun onBlockConfirmed() {
        val device = _uiState.value.showBlockDialog ?: return
        _uiState.value = _uiState.value.copy(showBlockDialog = null)

        viewModelScope.launch {
            try {
                repository.blockDevice(device.mac, _uiState.value.blockedMacs).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(message = it)
                        loadDevices()
                    },
                    onFailure = {
                        _uiState.value = _uiState.value.copy(message = "فشل: ${it.message}")
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(message = "خطأ: ${e.message}")
            }
        }
    }

    fun onBlockCancelled() {
        _uiState.value = _uiState.value.copy(showBlockDialog = null)
    }

    fun onUnblockClicked(mac: String) {
        _uiState.value = _uiState.value.copy(showUnblockDialog = mac)
    }

    fun onUnblockConfirmed() {
        val mac = _uiState.value.showUnblockDialog ?: return
        _uiState.value = _uiState.value.copy(showUnblockDialog = null)

        viewModelScope.launch {
            try {
                repository.unblockDevice(mac, _uiState.value.blockedMacs).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(message = it)
                        loadDevices()
                    },
                    onFailure = {
                        _uiState.value = _uiState.value.copy(message = "فشل: ${it.message}")
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(message = "خطأ: ${e.message}")
            }
        }
    }

    fun onUnblockCancelled() {
        _uiState.value = _uiState.value.copy(showUnblockDialog = null)
    }

    fun toggleDebugInfo() {
        _uiState.value = _uiState.value.copy(showDebugInfo = !_uiState.value.showDebugInfo)
    }

    fun navigateTo(screen: String) {
        _uiState.value = _uiState.value.copy(currentScreen = screen)
    }

    fun logout() {
        viewModelScope.launch {
            try { repository.logout() } catch (_: Exception) {}
        }
        _uiState.value = NetGuardUiState(
            routerIp = storage.getRouterIp(),
            username = storage.getUsername()
        )
    }

    fun onMessageShown() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
