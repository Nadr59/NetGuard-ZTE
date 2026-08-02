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

import com.example.netguardzte.data.local.TrafficStorage
import com.example.netguardzte.domain.model.DeviceTraffic
import com.example.netguardzte.domain.model.TrafficSnapshot

data class NetGuardUiState(
        // ═══ في أعلى الكلاس ═══
    private val trafficStorage = TrafficStorage(application)

    // ═══ في NetGuardUiState أضف: ═══
    val trafficData: List<DeviceTraffic> = emptyList(),
    val isLoadingTraffic: Boolean = false,
    val totalRx: Long = 0,
    val totalTx: Long = 0,

    // ═══ الدوال الجديدة: ═══
    fun loadTraffic() {
        _uiState.value = _uiState.value.copy(isLoadingTraffic = true)

        viewModelScope.launch(errorHandler) {
            try {
                val trafficResult = repository.getTrafficData()
                val devices = trafficResult.getOrNull() ?: emptyList()

                // حفظ لقطة
                if (devices.isNotEmpty()) {
                    trafficStorage.saveSnapshot(
                        TrafficSnapshot(
                            timestamp = System.currentTimeMillis(),
                            devices = devices,
                            totalRx = _uiState.value.totalRx,
                            totalTx = _uiState.value.totalTx
                        )
                    )
                }

                _uiState.value = _uiState.value.copy(
                    isLoadingTraffic = false,
                    trafficData = devices,
                    deviceError = trafficResult.exceptionOrNull()?.message
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingTraffic = false,
                    deviceError = "Error: ${e.message}"
                )
            }
        }
    }

    fun getDeviceTodayUsage(mac: String): Pair<Long, Long> {
        return trafficStorage.getDeviceTodayUsage(mac)
    }

    fun getDeviceMonthUsage(mac: String): Pair<Long, Long> {
        return trafficStorage.getDeviceMonthUsage(mac)
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
            else -> "$bytes B"
        }
    }
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

    private lateinit var storage: SecureStorage
    lateinit var repository: RouterRepository

    private val _uiState = MutableStateFlow(NetGuardUiState())
    val uiState: StateFlow<NetGuardUiState> = _uiState.asStateFlow()

    private val errorHandler = CoroutineExceptionHandler { _: kotlin.coroutines.CoroutineContext, throwable: Throwable ->
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val errorText = "Coroutine Error: ${throwable.message}\n$sw"
        saveError(errorText)
        _uiState.value = _uiState.value.copy(
            isLoggingIn = false,
            isLoadingDevices = false,
            isTestingRouter = false,
            loginError = throwable.message ?: "خطأ غير معروف",
            debugInfo = errorText,
            showDebugInfo = true
        )
    }

    init {
        try {
                    storage = SecureStorage(application)
        repository = RouterRepository(storage, application)
    } catch (e: Exception) {
        storage = SecureStorage(application)
        repository = RouterRepository(storage, application)
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
                    onSuccess = { msg: String ->
                        _uiState.value = _uiState.value.copy(
                            isLoggingIn = false,
                            currentScreen = "devices",
                            debugInfo = repository.loginDebug,
                            showDebugInfo = true
                        )
                        loadDevices()
                    },
                    onFailure = { e: Throwable ->
                        _uiState.value = _uiState.value.copy(
                            isLoggingIn = false,
                            loginError = e.message ?: "فشل تسجيل الدخول",
                            debugInfo = repository.loginDebug,
                            showDebugInfo = true
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
                    debugInfo = repository.loginDebug.ifBlank { "Error:\n${e.message}\n$sw" },
                    showDebugInfo = true
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
                    Result.success(emptyList<String>())
                }

                val devices = devicesResult.getOrNull() ?: emptyList()
                val blocked = blockedResult.getOrNull() ?: emptyList()

                val withBlock = devices.map { d: Device ->
                    d.copy(isBlocked = blocked.any { b: String ->
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
                    debugInfo = "Error:\n${e.message}\n$sw",
                    showDebugInfo = true
                )
            }
        }
    }

    fun testRouter() {
        _uiState.value = _uiState.value.copy(isTestingRouter = true)

        viewModelScope.launch(errorHandler) {
            try {
                repository.testRouterConnection().fold(
                    onSuccess = { result: String ->
                        _uiState.value = _uiState.value.copy(
                            isTestingRouter = false,
                            showDebugInfo = true,
                            debugInfo = result
                        )
                    },
                    onFailure = { e: Throwable ->
                        _uiState.value = _uiState.value.copy(
                            isTestingRouter = false,
                            showDebugInfo = true,
                            debugInfo = "Error: ${e.message}"
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
                    onSuccess = { msg: String ->
                        _uiState.value = _uiState.value.copy(
                            message = msg,
                            debugInfo = repository.lastRawResponse,
                            showDebugInfo = true
                        )
                        loadDevices()
                    },
                    onFailure = { e: Throwable ->
                        _uiState.value = _uiState.value.copy(
                            message = "فشل الحظر",
                            debugInfo = repository.allCommandsDebug.ifBlank {
                                repository.lastRawResponse.ifBlank {
                                    e.message ?: "unknown"
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
                    onSuccess = { msg: String ->
                        _uiState.value = _uiState.value.copy(message = msg)
                        loadDevices()
                    },
                    onFailure = { e: Throwable ->
                        _uiState.value = _uiState.value.copy(message = "فشل: ${e.message}")
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

    fun saveCredentials(ip: String, username: String, password: String) {
    repository.saveCredentials(ip, username, password)
    }
    fun navigateTo(screen: String) {
        _uiState.value = _uiState.value.copy(currentScreen = screen)
    }

    fun logout() {
        viewModelScope.launch(errorHandler) {
            try {
                repository.logout()
            } catch (_: Exception) {}
        }
        storage.setLoggedIn(false)
        _uiState.value = NetGuardUiState(
            routerIp = storage.getRouterIp(),
            username = storage.getUsername()
        )
    }

    fun onMessageShown() {
        _uiState.value = _uiState.value.copy(message = null)
    }
        fun discoverTraffic() {
        _uiState.value = _uiState.value.copy(isTestingRouter = true)

        viewModelScope.launch(errorHandler) {
            try {
                val result = repository.discoverTrafficCommands()
                _uiState.value = _uiState.value.copy(
                    isTestingRouter = false,
                    showDebugInfo = true,
                    debugInfo = result.getOrNull() ?: "No result"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTestingRouter = false,
                    debugInfo = "Error: ${e.message}"
                )
            }
        }
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
     // في ViewModel:

suspend fun diagnose(): String {
    val result = repository.testRouterConnection()
    return result.getOrNull() ?: "No result"
}
// أو في UI: اجعل زر "اختبار" يستدعي diagnosePost()
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
