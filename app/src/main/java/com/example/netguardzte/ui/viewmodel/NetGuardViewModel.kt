package com.example.netguardzte.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.netguardzte.App
import com.example.netguardzte.data.local.SecureStorage
import com.example.netguardzte.data.repository.RouterRepository
import com.example.netguardzte.domain.model.Device
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

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
    val isTestingRouter: Boolean = false,
    val crashInfo: String = ""
)

class NetGuardViewModel(application: Application) : AndroidViewModel(application) {

    // ═══ lateinit var بدلاً من val ═══
    private lateinit var storage: SecureStorage
    lateinit var repository: RouterRepository

    private val _uiState = MutableStateFlow(NetGuardUiState())
    val uiState: StateFlow<NetGuardUiState> = _uiState.asStateFlow()

    private val errorHandler = CoroutineExceptionHandler { _, throwable ->
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val errorText = "Coroutine Error: ${throwable.message}\n$sw"
        saveError(errorText)
        _uiState.value = _uiState.value.copy(
            isLoggingIn = false,
            isLoadingDevices = false,
            isTestingRouter = false,
            loginError = throwable.message ?: "خطأ غير معروف",
            debugInfo = errorText
        )
    }

    init {
        try {
            storage = SecureStorage(application)
            repository = RouterRepository(storage)
        } catch (e: Exception) {
            storage = SecureStorage(application)
            repository = RouterRepository(storage)
            saveError("Init error: ${e.message}")
        }

        try {
            val ip = storage.getRouterIp()
            val uname = storage.getUsername()
            val loggedIn = try {
                storage.isLoggedIn() && storage.hasCredentials()
            } catch (_: Exception) { false }

            val crash = getStoredCrash()

            _uiState.value = NetGuardUiState(
                routerIp = ip,
                username = uname,
                currentScreen = if (loggedIn) "devices" else "login",
                crashInfo = crash
            )

            if (loggedIn && crash.isBlank()) {
                try { loadDevices() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            saveError("Init state error: ${e.message}")
            _uiState.value = NetGuardUiState(crashInfo = getStoredCrash())
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

        viewModelScope.launch(errorHandler) {
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
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            isLoggingIn = false,
                            loginError = e.message ?: "فشل تسجيل الدخول",
                            debugInfo = repository.loginDebug
                        )
                    }
                )
            } catch (e: Exception) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                saveError("Login crash: ${e.message}\n$sw")
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    loginError = "خطأ: ${e.message}",
                    debugInfo = "Login Error:\n${e.message}\n$sw"
                )
            }
        }
    }

    fun loadDevices() {
        _uiState.value = _uiState.value.copy(isLoadingDevices = true, deviceError = null)

        viewModelScope.launch(errorHandler) {
            try {
                val devicesResult = repository.getConnectedDevices()
                val blockedResult = try {
                    repository.getBlockedMacs()
                } catch (_: Exception) {
                    Result.success(emptyList())
                }

                val devices = devicesResult.getOrNull() ?: emptyList()
                val blocked = blockedResult.getOrNull() ?: emptyList()

                val withBlock = devices.map { d ->
                    d.copy(isBlocked = blocked.any { b ->
                        b.uppercase() == d.mac.uppercase()
                    })
                }

                _uiState.value = _uiState.value.copy(
                    isLoadingDevices = false,
                    devices = withBlock,
                    blockedMacs = blocked,
                    deviceError = devicesResult.exceptionOrNull()?.message,
                    debugInfo = repository.allCommandsDebug
                )
            } catch (e: Exception) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                saveError("LoadDevices crash: ${e.message}\n$sw")
                _uiState.value = _uiState.value.copy(
                    isLoadingDevices = false,
                    deviceError = "خطأ: ${e.message}",
                    debugInfo = "Error:\n${e.message}\n$sw"
                )
            }
        }
    }

    fun testRouter() {
        _uiState.value = _uiState.value.copy(isTestingRouter = true)

        viewModelScope.launch(errorHandler) {
            try {
                repository.testRouterConnection().fold(
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

        viewModelScope.launch(errorHandler) {
            try {
                repository.blockDevice(device.mac, _uiState.value.blockedMacs).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            message = it,
                            debugInfo = repository.lastRawResponse,
                            showDebugInfo = true
                        )
                        loadDevices()
                    },
                    onFailure = {
                        _uiState.value = _uiState.value.copy(
                            message = "فشل الحظر",
                            debugInfo = repository.allCommandsDebug.ifBlank {
                                repository.lastRawResponse.ifBlank {
                                    it.message ?: "unknown"
                                }
                            },
                            showDebugInfo = true
                        )
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

        viewModelScope.launch(errorHandler) {
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
        _uiState.value = _uiState.value.copy(
            showDebugInfo = !_uiState.value.showDebugInfo
        )
    }

    fun navigateTo(screen: String) {
        _uiState.value = _uiState.value.copy(currentScreen = screen)
    }

    fun logout() {
        viewModelScope.launch(errorHandler) {
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

    fun clearCrashLog() {
        try {
            val prefs = getApplication<Application>()
                .getSharedPreferences("crash_log", android.content.Context.MODE_PRIVATE)
            prefs.edit().remove("last_crash").commit()
            App.lastCrashText = ""
            _uiState.value = _uiState.value.copy(crashInfo = "")
        } catch (_: Exception) {}
    }

    private fun saveError(text: String) {
        try {
            val prefs = getApplication<Application>()
                .getSharedPreferences("crash_log", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("last_crash", text).commit()
            App.lastCrashText = text
        } catch (_: Exception) {}
    }

    private fun getStoredCrash(): String {
        if (App.lastCrashText.isNotBlank()) return App.lastCrashText
        return try {
            val prefs = getApplication<Application>()
                .getSharedPreferences("crash_log", android.content.Context.MODE_PRIVATE)
            prefs.getString("last_crash", "") ?: ""
        } catch (_: Exception) { "" }
    }
}
