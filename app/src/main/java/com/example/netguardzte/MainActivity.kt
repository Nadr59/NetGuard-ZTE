"devices" -> DevicesScreen(
    devices = s.devices,
    isLoading = s.isLoadingDevices,
    error = s.deviceError,
    showBlockDialog = s.showBlockDialog,
    showDebugInfo = s.showDebugInfo,
    debugInfo = s.debugInfo,
    onRefresh = { vm.loadDevices() },
    onBlock = { vm.onBlockClicked(it) },
    onUnblock = { vm.onUnblockClicked(it) },
    onBlockConfirmed = { vm.onBlockConfirmed() },
    onBlockCancelled = { vm.onBlockCancelled() },
    onNavigateToBlocked = { vm.navigateTo("blocked") },
    onNavigateToSettings = { vm.navigateTo("settings") },
    onToggleDebug = { vm.toggleDebugInfo() }
)
