// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.originband.link.data.BandCatalog
import com.originband.link.data.BandStateRepository
import com.originband.link.data.BindingStore
import com.originband.link.data.HealthRecordStore
import com.originband.link.data.LocalPrefs
import com.originband.link.data.SessionLog
import com.originband.link.data.TransportObservation
import com.originband.link.host.HostIdentity
import com.originband.link.integration.BandDetailsActivity
import com.originband.link.integration.CredentialProvider
import com.originband.link.integration.DeviceCardProvider
import com.originband.link.integration.HealthQueueProvider
import com.originband.link.notify.BandNotificationListener
import com.originband.link.notify.SleepMusic
import com.originband.link.protocol.SppDiagnosticClient
import com.originband.link.service.BandLiveService
import com.originband.link.service.OwnershipController
import com.originband.link.ui.CardDivider
import com.originband.link.ui.ConfirmDialog
import com.originband.link.ui.Copy
import com.originband.link.ui.DangerOutlinedButton
import com.originband.link.ui.ExpandableSection
import com.originband.link.ui.InfoDialog
import com.originband.link.ui.NavRow
import com.originband.link.ui.NoticeCard
import com.originband.link.ui.OriginBandScaffold
import com.originband.link.ui.PrimaryButton
import com.originband.link.ui.QuietTextButton
import com.originband.link.ui.SecondaryButton
import com.originband.link.ui.SectionCard
import com.originband.link.ui.SectionHeading
import com.originband.link.ui.SettingSwitchRow
import com.originband.link.ui.SetupProgress
import com.originband.link.ui.StatusBadge
import com.originband.link.ui.StepRow
import com.originband.link.ui.StepState
import com.originband.link.ui.Tone
import com.originband.link.ui.setOriginBandContent
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Home screen. Reads top to bottom: band status with one primary action, first-run checklist,
 * things to fix now, features, help, management, advanced import and debug tools.
 * Device registration stays independent of connection ownership.
 */
class MainActivity : ComponentActivity() {
    enum class Change { ADD, RECONNECT, REMOVE, RESTORE }

    @Immutable
    data class BondedDeviceItem(val mac: String, val name: String)

    @Immutable
    data class BandChoice(val address: String, val label: String)

    sealed interface MainDialog {
        data object None : MainDialog
        data object Guide : MainDialog
        data class HostVersion(val fingerprint: String, val message: String) : MainDialog
        data object ConfirmRemove : MainDialog
        data object ConfirmAdd : MainDialog
        data object ConfirmImport : MainDialog
        data class ChooseDatabaseBand(val choices: List<BandChoice>) : MainDialog
        data object ConfirmRestoreForProfile : MainDialog
        data object ConfirmProfileCapture : MainDialog
        data object ConfirmProtocolCapture : MainDialog
    }

    @Immutable
    data class MainUiState(
        val deviceName: String = "小米手环",
        val statusText: String = "正在读取",
        val statusTone: Tone = Tone.NEUTRAL,
        val connectionHint: String = "",
        val metricsLine: String = "",
        val healthLine: String = "",
        val ownershipStatus: String = "",
        val primaryLabel: String = Copy.setupLabel(SetupProgress.Step.ROOT),
        val primaryEnabled: Boolean = true,
        val showChecklist: Boolean = true,
        val visibleStepIndex: Int = 1,
        val primaryHint: String = "",
        val prereqExpanded: Boolean = false,
        val rootGranted: Boolean = false,
        val probingRoot: Boolean = false,
        val showNearbyNotice: Boolean = false,
        val showNotifyNotice: Boolean = false,
        val showHealthAccessNotice: Boolean = false,
        val sleepStateUnsupported: Boolean = false,
        val sleepPauseEnabled: Boolean = false,
        val showManagement: Boolean = false,
        val showRemove: Boolean = false,
        val showRestore: Boolean = false,
        val changingOwnership: Boolean = false,
        val advancedExpanded: Boolean = false,
        val bondedCaption: String = "",
        val bondedDevices: List<BondedDeviceItem> = emptyList(),
        val selectedAddress: String = "",
        val openWindowEnabled: Boolean = false,
        val importStatus: String = "正在检查小米运动健康…",
        val advancedRestoreEnabled: Boolean = false,
        val activeDialog: MainDialog = MainDialog.None,
    )

    companion object {
        /** Open the profile window when this restore task succeeded and the user came in from the capture entry. */
        @JvmStatic
        fun openProfileAfterRestore(change: Change, restored: Boolean): Boolean {
            return restored && change == Change.RESTORE
        }

        private fun failureCode(failure: Exception): String {
            return if (failure is OwnershipController.Failure) {
                failure.code
            } else {
                failure.message ?: ""
            }
        }

        private fun metricsText(state: LocalPrefs): String {
            val battery = state.getInt("battery", -1)
            val lastSync = state.getLong("lastSyncAtMs", 0)
            val batteryText = if (battery in 0..100) "电量 $battery%" else ""
            val syncText = if (lastSync > 0) {
                "同步于 " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(lastSync))
            } else {
                ""
            }
            if (batteryText.isEmpty()) return syncText
            if (syncText.isEmpty()) return batteryText
            return "$batteryText · $syncText"
        }

        private fun safeIdentity(value: String?, fallback: String): String {
            val clean = value?.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), "")?.trim().orEmpty()
            return if (clean.isEmpty() || clean.length > 80) fallback else clean
        }

        private fun sortedBonded(devices: Set<BluetoothDevice>): List<BluetoothDevice> {
            return devices.sortedWith(
                compareBy<BluetoothDevice> { !BandCatalog.looksLikeBand(it.name) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { BandCatalog.displayName(null, it.name) }
            )
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var uiState by mutableStateOf(MainUiState())
    private var selectedAddress = ""
    private var databaseImportNote = ""
    private var hostSupported = false
    private var callPermissionsAsked = false
    private var changingOwnership = false
    private var versionPromptShown = false
    private var launchHostAfterOpen = false
    private var profileAfterRestore = false
    private var rootGranted = false
    private var probingRoot = false
    private var pendingPermission: Change? = null

    @Volatile
    private var currentStep = SetupProgress.Step.ROOT
    private var deviceRefreshGen = 0

    private val bluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val requested = pendingPermission
        pendingPermission = null
        refreshNotices()
        refreshDevice()
        refreshBonded()
        if (hasBluetoothPermission()) {
            if (requested != null) requestNative(requested)
            else refreshOwnership()
        } else {
            uiState = uiState.copy(ownershipStatus = "授权附近设备后即可添加和连接，已有登记保持原样。")
        }
    }

    private val callPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        BandLiveService.refreshNotificationSettings(this)
    }

    private val deviceObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshDevice()
    }

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            refresh()
            refreshDevice()
        }
    }

    private val expireRefresh = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preselected = intent.getStringExtra("deviceAddress")
        if (preselected != null && preselected.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}"))) {
            selectedAddress = preselected
        }
        uiState = uiState.copy(selectedAddress = selectedAddress)
        setOriginBandContent {
            MainScreen(
                state = uiState,
                onPrimaryAction = ::runHomePrimary,
                onOpenKernelSu = ::openKernelSu,
                onTogglePrereq = { uiState = uiState.copy(prereqExpanded = !uiState.prereqExpanded) },
                onRequestBluetooth = {
                    pendingPermission = null
                    bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                },
                onOpenAppNotifications = ::openAppNotificationSettings,
                onOpenHealthListener = ::openHealthListenerSettings,
                onOpenDetails = { startActivity(Intent(this, BandDetailsActivity::class.java)) },
                onOpenWeather = { startActivity(Intent(this, WeatherActivity::class.java)) },
                onSleepPauseToggle = ::applySleepPause,
                onShowGuide = { uiState = uiState.copy(activeDialog = MainDialog.Guide) },
                onOpenSystemPermissions = {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                },
                onShareDebugLog = ::shareDebugLog,
                onRestoreOfficial = { changeOwnership(Change.RESTORE) },
                onRequestRemove = { uiState = uiState.copy(activeDialog = MainDialog.ConfirmRemove) },
                onToggleAdvanced = {
                    val next = !uiState.advancedExpanded
                    uiState = uiState.copy(advancedExpanded = next)
                    if (next) refreshBonded()
                },
                onSelectBonded = { mac ->
                    selectedAddress = mac
                    uiState = uiState.copy(selectedAddress = mac)
                    refreshBonded()
                },
                onOpenImportWindow = {
                    if (hostSupported) {
                        if (selectedAddress.isBlank()) {
                            uiState = uiState.copy(importStatus = "先在上方选择一只已配对设备。")
                        } else {
                            val selected = Bundle().apply { putString("address", selectedAddress) }
                            action("openWindow", selected)
                        }
                    }
                },
                onCloseWindows = { action("closeWindow", null) },
                onLaunchMiHealth = ::launchMiHealth,
                onOpenDeviceLog = { startActivity(Intent(this, DeviceLogActivity::class.java)) },
                onOpenLab = { startActivity(Intent(this, LabActivity::class.java)) },
                onOpenDiagnostic = { startActivity(Intent(this, DiagnosticActivity::class.java)) },
                onStartProfileCapture = ::startProfileCapture,
                onRequestProtocolCapture = { uiState = uiState.copy(activeDialog = MainDialog.ConfirmProtocolCapture) },
                onDismissDialog = ::dismissActiveDialog,
                onConfirmHostVersion = { fingerprint ->
                    getSharedPreferences("host-version-notice", MODE_PRIVATE)
                        .edit().putString("dismissed", fingerprint).apply()
                    uiState = uiState.copy(activeDialog = MainDialog.None)
                },
                onConfirmRemove = {
                    uiState = uiState.copy(activeDialog = MainDialog.None)
                    changeOwnership(Change.REMOVE)
                },
                onConfirmAdd = {
                    uiState = uiState.copy(activeDialog = MainDialog.None)
                    changeOwnership(Change.ADD)
                },
                onConfirmImport = {
                    uiState = uiState.copy(activeDialog = MainDialog.None)
                    importFromDatabase(selectedAddress)
                },
                onChooseDatabaseBand = { address ->
                    uiState = uiState.copy(activeDialog = MainDialog.None)
                    importFromDatabase(address)
                },
                onConfirmRestoreForProfile = {
                    uiState = uiState.copy(activeDialog = MainDialog.None)
                    profileAfterRestore = true
                    changeOwnership(Change.RESTORE)
                },
                onConfirmProfileCapture = {
                    uiState = uiState.copy(activeDialog = MainDialog.None)
                    startProfileWindow()
                },
                onConfirmProtocolCapture = {
                    uiState = uiState.copy(activeDialog = MainDialog.None)
                    action("openCapture", null)
                },
            )
        }
        contentResolver.registerContentObserver(CredentialProvider.URI, false, observer)
        contentResolver.registerContentObserver(DeviceCardProvider.URI, false, deviceObserver)
        contentResolver.registerContentObserver(HealthQueueProvider.URI, false, deviceObserver)
        refreshDevice()
        requestRoot()
    }

    override fun onResume() {
        super.onResume()
        refreshDevice()
        promptHostVersions()
        if (!rootGranted) requestRoot()
        else continueInit()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        contentResolver.unregisterContentObserver(deviceObserver)
        main.removeCallbacks(expireRefresh)
        worker.shutdown()
        super.onDestroy()
    }

    private fun dismissActiveDialog() {
        val current = uiState.activeDialog
        if (current is MainDialog.HostVersion) {
            getSharedPreferences("host-version-notice", MODE_PRIVATE)
                .edit().putString("dismissed", current.fingerprint).apply()
        }
        uiState = uiState.copy(activeDialog = MainDialog.None)
    }

    private fun applySleepPause(enabled: Boolean) {
        if (SleepMusic.setEnabled(this, enabled)) {
            uiState = uiState.copy(sleepPauseEnabled = enabled)
            BandLiveService.sleepPauseChanged(this)
            return
        }
        uiState = uiState.copy(
            sleepPauseEnabled = !enabled,
            ownershipStatus = "开关状态保存中断，再拨一次即可。",
        )
    }

    private fun continueInit() {
        refreshOwnership()
        refreshDevice()
        refreshBonded()
        if (hostSupported) {
            refresh()
        } else {
            worker.execute {
                val supported = HostIdentity.installed(this, HostIdentity.MI_PACKAGE)
                main.post {
                    if (isDestroyed) return@post
                    hostSupported = supported
                    uiState = uiState.copy(
                        openWindowEnabled = supported && rootGranted,
                        importStatus = if (supported) uiState.importStatus else "安装小米运动健康并配对手环后，即可使用导入窗口。",
                    )
                    if (supported) refresh()
                }
            }
        }
        ensureCallPermissions()
    }

    private fun refreshNotices() {
        val prefs = LocalPrefs.open(this, BandNotificationListener.SETTINGS)
        val healthAccess = HostIdentity.hasHealthNotificationListener()
                && prefs.getBoolean("healthSecure", false)
                && !prefs.getBoolean("healthApproved", true)
                && prefs.getBoolean("healthGrantFailed", false)
        val notificationsEnabled = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        uiState = uiState.copy(
            showNearbyNotice = !hasBluetoothPermission(),
            showNotifyNotice = !notificationsEnabled,
            showHealthAccessNotice = healthAccess,
        )
    }

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun openHealthListenerSettings() {
        if (HostIdentity.hasHealthNotificationListener()) {
            val detail = Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
            detail.putExtra(
                "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                HostIdentity.HEALTH_PACKAGE + "/" + HostIdentity.HEALTH_NOTIFICATION_LISTENER,
            )
            try {
                startActivity(detail)
                return
            } catch (_: RuntimeException) {
            }
        }
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (_: RuntimeException) {
            uiState = uiState.copy(ownershipStatus = "在系统设置 → 通知 → 通知使用权中找到 vivo 健康或 OriginBand。")
        }
    }

    private fun shareDebugLog() {
        worker.execute {
            try {
                val file = SessionLog.export(this)
                val uri = FileProvider.getUriForFile(this, "com.originband.link.debuglog", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "OriginBand 调试日志")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val qq = Intent(send).setPackage("com.tencent.mobileqq")
                main.post {
                    if (isDestroyed) return@post
                    if (qq.resolveActivity(packageManager) != null) startActivity(qq)
                    else startActivity(Intent.createChooser(send, "发送调试日志"))
                }
            } catch (_: Exception) {
                main.post {
                    if (!isDestroyed) {
                        Toast.makeText(this, "调试日志生成中断，稍后再试一次", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun promptHostVersions() {
        if (isDestroyed || versionPromptShown) return
        val installed = ArrayList<HostVersionNotice.Installed>()
        for (expect in HostVersionNotice.EXPECTATIONS) {
            try {
                val info = packageManager.getPackageInfo(expect.packageName(), 0)
                installed.add(
                    HostVersionNotice.Installed(
                        expect.packageName(), info.versionName, info.longVersionCode, true
                    )
                )
            } catch (_: PackageManager.NameNotFoundException) {
                installed.add(HostVersionNotice.Installed(expect.packageName(), "", 0, false))
            }
        }
        val finger = HostVersionNotice.fingerprint(installed)
        if (finger.isEmpty()) return
        val prefs = getSharedPreferences("host-version-notice", MODE_PRIVATE)
        if (finger == prefs.getString("dismissed", "")) return
        versionPromptShown = true
        uiState = uiState.copy(activeDialog = MainDialog.HostVersion(finger, HostVersionNotice.message(installed)))
    }

    private fun requestRoot() {
        if (worker.isShutdown || probingRoot || changingOwnership) return
        probingRoot = true
        uiState = uiState.copy(
            probingRoot = true,
            ownershipStatus = "正在检查 Root 授权…",
        )
        worker.execute {
            val granted = OwnershipController(this).probeRoot()
            main.post {
                if (isDestroyed) return@post
                probingRoot = false
                rootGranted = granted
                uiState = uiState.copy(
                    probingRoot = false,
                    rootGranted = granted,
                    ownershipStatus = if (granted) "Root 权限已授权。" else Copy.setupHint(SetupProgress.Step.ROOT),
                    openWindowEnabled = hostSupported && granted,
                )
                updateControls()
                refreshDevice()
                if (granted) continueInit()
            }
        }
    }

    private fun openKernelSu() {
        val launch = packageManager.getLaunchIntentForPackage("me.weishu.kernelsu")
        if (launch == null) {
            uiState = uiState.copy(ownershipStatus = "安装 Root 管理器后，为本应用打开超级用户。")
            return
        }
        startActivity(launch)
    }

    private fun refreshOwnership() {
        if (worker.isShutdown || changingOwnership || !rootGranted) return
        worker.execute {
            if (!isUnlocked()) return@execute
            val owner = OwnershipController(this)
            val repository = BandStateRepository(this)
            var message: String
            try {
                val registered = repository.isRegistered()
                val ready = owner.nativeReady()
                if (!registered) {
                    BandLiveService.stop(this)
                    if (!SppDiagnosticClient.stopAllAndWait()) {
                        throw OwnershipController.Failure("DIAGNOSTIC_SOCKET_STILL_ACTIVE")
                    }
                    repository.disconnected()
                }
                if (registered && ready && hasBluetoothPermission()) {
                    BandLiveService.start(this)
                }
                message = if (!registered) {
                    if (needsOfficialRestore()) {
                        "小米运动健康仍在暂停。可以添加设备，或在「管理」中恢复小米运动健康。"
                    } else {
                        ""
                    }
                } else if ("NATIVE" == owner.mode()) {
                    "由桥接管理，连接状态以手环实际响应为准。"
                } else {
                    "当前由小米运动健康管理，点「立即同步」重新接管。"
                }
            } catch (_: Exception) {
                message = if (repository.isRegistered()) {
                    "设备登记已保留。点「立即同步」再连接一次，或恢复官方管理。"
                } else {
                    "点「恢复小米运动健康」再试一次。"
                }
            }
            val idle = message
            main.post {
                if (isDestroyed) return@post
                uiState = uiState.copy(
                    ownershipStatus = if (databaseImportNote.isEmpty()) idle else databaseImportNote
                )
                updateControls()
            }
        }
    }

    private fun changeOwnership(change: Change) {
        if (worker.isShutdown || changingOwnership) return
        if (!rootGranted) {
            requestRoot()
            return
        }
        if (!isUnlocked()) {
            uiState = uiState.copy(ownershipStatus = "解锁手机后再管理设备。")
            return
        }
        changingOwnership = true
        uiState = uiState.copy(
            changingOwnership = true,
            ownershipStatus = "正在处理，请稍候…",
        )
        updateControls()
        worker.execute {
            val owner = OwnershipController(this)
            val repository = BandStateRepository(this)
            var message: String
            var registrationSaved = false
            var removalSaved = false
            var officialRestored = false
            try {
                if (change == Change.ADD || change == Change.RECONNECT) {
                    if (!isUnlocked()) throw IllegalStateException("USER_LOCKED")
                    if (!owner.probeRoot()) throw OwnershipController.Failure("ROOT_REQUIRED")
                    if (!hasBluetoothPermission()) throw IllegalStateException("NEARBY_PERMISSION_REQUIRED")
                    if (change == Change.ADD) {
                        repository.registerDevice()
                        registrationSaved = true
                    }
                    if (!repository.isRegistered()) throw IllegalStateException("DEVICE_NOT_REGISTERED")
                    if (!owner.nativeReady()) owner.takeOver()
                    if (!repository.isRegistered() || !owner.nativeReady()) {
                        throw IllegalStateException("NATIVE_OWNERSHIP_REQUIRED")
                    }
                    BandLiveService.start(this)
                    BandLiveService.requestSync(this)
                    message = "设备已登记，正在连接。"
                } else {
                    if (change == Change.REMOVE) {
                        repository.unregisterDevice()
                        removalSaved = true
                        main.post { refreshDevice() }
                    }
                    BandLiveService.stop(this)
                    if (!SppDiagnosticClient.stopAllAndWait()) {
                        throw OwnershipController.Failure("DIAGNOSTIC_SOCKET_STILL_ACTIVE")
                    }
                    repository.disconnected()
                    owner.restoreOfficial()
                    officialRestored = change == Change.RESTORE
                    message = if (change == Change.REMOVE) {
                        "已移除，小米运动健康已恢复。绑定和健康历史都已保留。"
                    } else {
                        "小米运动健康已恢复。绑定和健康历史都已保留。"
                    }
                }
            } catch (failure: Exception) {
                val hint = Copy.ownershipFailure(failureCode(failure))
                message = if (removalSaved || (change == Change.RESTORE && !repository.isRegistered())) {
                    "已移除，小米运动健康待恢复。$hint"
                } else if (change == Change.REMOVE) {
                    "设备仍在登记中，再点一次「从健康移除」。"
                } else if (registrationSaved || (change == Change.RECONNECT && repository.isRegistered())) {
                    "设备登记已保留，连接稍后继续。$hint"
                } else {
                    hint
                }
            }
            val visible = message
            val followProfile = openProfileAfterRestore(change, officialRestored)
            main.post {
                if (isDestroyed) return@post
                changingOwnership = false
                uiState = uiState.copy(
                    changingOwnership = false,
                    ownershipStatus = visible,
                )
                updateControls()
                refreshDevice()
                if (profileAfterRestore && followProfile) {
                    profileAfterRestore = false
                    startProfileWindow()
                }
            }
        }
    }

    private fun requestNative(change: Change) {
        if (changingOwnership) return
        if (!rootGranted) {
            requestRoot()
            return
        }
        if (!isUnlocked()) {
            uiState = uiState.copy(ownershipStatus = "解锁手机后再管理设备。")
            return
        }
        if (!hasBluetoothPermission()) {
            pendingPermission = change
            bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (change == Change.RECONNECT) {
            changeOwnership(change)
            return
        }
        uiState = uiState.copy(activeDialog = MainDialog.ConfirmAdd)
    }

    private fun ensureCallPermissions() {
        if (callPermissionsAsked || !isUnlocked() || !BandStateRepository(this).isRegistered()) return
        val phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hangup = checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        if (phone && hangup) return
        callPermissionsAsked = true
        when {
            !phone && !hangup -> callPermissionsLauncher.launch(
                arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
            )
            !phone -> callPermissionsLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
            else -> callPermissionsLauncher.launch(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS))
        }
    }

    private fun isUnlocked(): Boolean {
        return getSystemService(UserManager::class.java).isUserUnlocked
                && !getSystemService(KeyguardManager::class.java).isDeviceLocked
    }

    private fun hasBluetoothPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun needsOfficialRestore(): Boolean {
        val ownership = LocalPrefs.open(this, "ownership")
        return ownership.getBoolean("ownsDisable", false) || ownership.getBoolean("transitionPending", false)
    }

    private fun updateControls() {
        refreshDevice()
    }

    private fun readProgress(): SetupProgress {
        val registered = BandStateRepository(this).isRegistered()
        var hasBinding = false
        var model = ""
        try {
            val binding = BindingStore(this).read()
            hasBinding = binding != null
            if (binding != null) model = binding.optString("model", "")
        } catch (_: Exception) {
        }
        val hasProfile = hasBinding && TransportObservation.supportsLive(
            TransportObservation.liveProfile(this, model), model
        )
        var nativeOwned = false
        try {
            nativeOwned = OwnershipController(this).nativeReady()
        } catch (_: RuntimeException) {
        }
        var accountConfirmed = false
        try {
            HealthRecordStore(this).use { store ->
                accountConfirmed = store.confirmedAccountHash() != null
            }
        } catch (_: Exception) {
        }
        return SetupProgress(rootGranted, hasBinding, hasProfile, registered, nativeOwned, accountConfirmed).also {
            currentStep = it.current()
        }
    }

    private fun runHomePrimary() {
        if (changingOwnership || probingRoot) return
        when (currentStep) {
            SetupProgress.Step.ROOT -> requestRoot()
            SetupProgress.Step.IMPORT -> startBindingImport()
            SetupProgress.Step.PROFILE -> startProfileCapture()
            SetupProgress.Step.ADD -> requestNative(Change.ADD)
            SetupProgress.Step.DONE -> requestNative(Change.RECONNECT)
        }
    }

    private fun refreshDevice() {
        if (isDestroyed || worker.isShutdown) return
        val gen = ++deviceRefreshGen
        val unlocked = isUnlocked()
        val bluetoothOk = hasBluetoothPermission()
        val notificationsOk = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        if (!unlocked) {
            uiState = uiState.copy(
                deviceName = "小米手环",
                statusText = "解锁后显示",
                statusTone = Tone.NEUTRAL,
                connectionHint = "解锁手机后显示手环信息。",
                metricsLine = "",
                healthLine = "",
                showNearbyNotice = !bluetoothOk,
                showNotifyNotice = !notificationsOk,
                primaryEnabled = !changingOwnership && !probingRoot,
            )
            return
        }
        worker.execute {
            val progress = readProgress()
            val registered = BandStateRepository(this).isRegistered()
            val officialPaused = needsOfficialRestore()
            val sleepUnsupported = BandLiveService.sleepStateUnsupportedForCurrentDevice(this)
            val sleepPause = if (sleepUnsupported) false else SleepMusic.enabled(this)
            val notifyPrefs = LocalPrefs.open(this, BandNotificationListener.SETTINGS)
            val healthAccess = HostIdentity.hasHealthNotificationListener()
                && notifyPrefs.getBoolean("healthSecure", false)
                && !notifyPrefs.getBoolean("healthApproved", true)
                && notifyPrefs.getBoolean("healthGrantFailed", false)

            val devName: String
            val statText: String
            val statTone: Tone
            val connHint: String
            val metrics: String
            val health: String
            if (registered) {
                val state = LocalPrefs.open(this, "band-state")
                val connected = state.getBoolean("connected", false)
                devName = safeIdentity(
                    BandCatalog.displayName(state.getString("modelId", ""), state.getString("name", "")),
                    "已登记手环",
                )
                statText = if (connected) "已连接" else "等待连接"
                statTone = if (connected) Tone.POSITIVE else Tone.NEUTRAL
                connHint = if (connected) "" else Copy.offlineReason(liveStatus())
                metrics = metricsText(state)
                health = healthExtras()
            } else {
                devName = "小米手环"
                statText = "待添加"
                statTone = Tone.NEUTRAL
                connHint = ""
                metrics = try {
                    val binding = BindingStore(this).read()
                    if (binding == null) {
                        "导入绑定后即可添加到健康。"
                    } else {
                        "绑定已导入，点「添加到健康」即可连接。"
                    }
                } catch (_: Exception) {
                    "解锁手机后显示绑定信息，已有数据都在。"
                }
                health = ""
            }
            main.post {
                if (isDestroyed || gen != deviceRefreshGen) return@post
                val manage = rootGranted && (registered || officialPaused)
                uiState = uiState.copy(
                    deviceName = devName,
                    statusText = statText,
                    statusTone = statTone,
                    connectionHint = connHint,
                    metricsLine = metrics,
                    healthLine = health,
                    showChecklist = progress.showChecklist(),
                    visibleStepIndex = progress.visibleIndex(),
                    primaryHint = if (progress.showChecklist()) progress.primaryHint() else "",
                    primaryLabel = progress.primaryLabel(),
                    primaryEnabled = !changingOwnership && !probingRoot,
                    rootGranted = rootGranted,
                    probingRoot = probingRoot,
                    showManagement = manage,
                    showRemove = registered,
                    showRestore = officialPaused,
                    changingOwnership = changingOwnership,
                    openWindowEnabled = hostSupported && rootGranted,
                    advancedRestoreEnabled = !changingOwnership && rootGranted,
                    sleepStateUnsupported = sleepUnsupported,
                    sleepPauseEnabled = sleepPause,
                    showNearbyNotice = !hasBluetoothPermission(),
                    showNotifyNotice = !getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
                    showHealthAccessNotice = healthAccess,
                )
            }
        }
    }

    private fun liveStatus(): String {
        val file = java.io.File(noBackupFilesDir, "live-status.txt")
        if (file.isFile && file.length() > 0 && file.length() < 256) {
            try {
                java.io.FileInputStream(file).use { input ->
                    val bytes = ByteArray(file.length().toInt())
                    val n = input.read(bytes)
                    if (n > 0) return String(bytes, 0, n, Charsets.UTF_8).trim()
                }
            } catch (_: Exception) {
            }
        }
        return ""
    }

    private fun healthExtras(): String {
        val live = getSharedPreferences("live-service", MODE_PRIVATE)
        val host = Copy.healthHost(live.getString("healthHostStatus", ""))
        val collection = Copy.healthCollection(live.getString("healthCollectionStatus", ""))
        if (collection.isEmpty()) return host
        return if (host.isEmpty()) collection else "$host\n$collection"
    }

    private fun action(method: String, arguments: Bundle?) {
        if (isDestroyed || worker.isShutdown) return
        worker.execute {
            try {
                val response = contentResolver.call(CredentialProvider.URI, method, null, arguments)
                main.post {
                    if (isDestroyed) return@post
                    show(response)
                    if (method == "openWindow" || method == "openDiagnostics" || method == "openCapture") {
                        main.removeCallbacks(expireRefresh)
                        main.postDelayed(expireRefresh, 120_100)
                    }
                }
            } catch (_: RuntimeException) {
                main.post {
                    if (!isDestroyed) {
                        uiState = uiState.copy(importStatus = "解锁手机、核对所选设备后再点一次。")
                    }
                }
            }
        }
    }

    private fun refresh() = action("status", null)

    private fun show(response: Bundle?): String {
        if (response == null) {
            val text = "导入服务正在启动，稍后再点一次。"
            uiState = uiState.copy(importStatus = text)
            return text
        }
        val code = response.getString("status", "CREDENTIAL_STORAGE_FAILED")
        var text = Copy.importResult(
            code,
            response.getString("model", ""),
            response.getString("productId", ""),
            response.getString("firmware", ""),
        )
        val observation = response.getString("transportObservation", "")
        if (!observation.isNullOrBlank()) text += "\n连接参数（仅观察）：\n$observation"
        if (response.getBoolean("diagnosticOpen", false)) text += "\n连接参数采集窗口开启中。"
        if (response.getBoolean("captureOpen", false)) text += "\n协议消息采集窗口开启中。"
        val captureFile = response.getString("captureFile", "")
        if (!captureFile.isNullOrBlank()) {
            text += "\n采集记录：" + response.getInt("captureRecords") +
                "，字节：" + response.getLong("captureBytes") + "\n文件：" + captureFile
        }
        val captureError = response.getString("captureError", "")
        if (!captureError.isNullOrBlank()) text += "\n采集中断：$captureError"
        val updateOwnership = code == "IMPORT_WINDOW_OPEN" || code == "DIAGNOSTIC_WINDOW_OPEN"
        uiState = uiState.copy(
            importStatus = text,
            ownershipStatus = if (updateOwnership) text else uiState.ownershipStatus,
        )
        if (updateOwnership && launchHostAfterOpen) {
            launchHostAfterOpen = false
            if (hostSupported) launchMiHealth()
        }
        return text
    }

    private fun launchMiHealth() {
        val launch = packageManager.getLaunchIntentForPackage(HostIdentity.MI_PACKAGE)
        if (launch == null) {
            val text = "在桌面打开小米运动健康，确认它已安装并启用。"
            uiState = uiState.copy(importStatus = text, ownershipStatus = text)
            return
        }
        startActivity(launch)
    }

    private fun refreshBonded() {
        if (!hasBluetoothPermission()) {
            uiState = uiState.copy(
                bondedCaption = "授权附近设备后，这里会列出已配对设备。",
                bondedDevices = emptyList(),
            )
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null) {
            uiState = uiState.copy(
                bondedCaption = "这台手机需要蓝牙才能连接手环。",
                bondedDevices = emptyList(),
            )
            return
        }
        val devices: Set<BluetoothDevice>? = try {
            adapter.bondedDevices
        } catch (_: SecurityException) {
            uiState = uiState.copy(
                bondedCaption = "授权附近设备后，这里会列出已配对设备。",
                bondedDevices = emptyList(),
            )
            return
        }
        if (devices.isNullOrEmpty()) {
            uiState = uiState.copy(
                bondedCaption = "在系统蓝牙设置中配对手环后，它会出现在这里。",
                bondedDevices = emptyList(),
            )
            return
        }
        val listed = sortedBonded(devices)
        preferXiaomiBand(listed)
        val items = listed.map { device ->
            val mac = device.address?.uppercase(Locale.ROOT).orEmpty()
            val name = BandCatalog.displayName(null, device.name)
            BondedDeviceItem(mac = mac, name = name)
        }
        uiState = uiState.copy(
            bondedCaption = "选择已配对设备",
            bondedDevices = items,
            selectedAddress = selectedAddress,
        )
    }

    private fun preferXiaomiBand(devices: List<BluetoothDevice>) {
        if (selectedAddress.isNotBlank()) return
        var onlyBand: String? = null
        var bands = 0
        for (device in devices) {
            if (!BandCatalog.looksLikeBand(device.name)) continue
            val mac = device.address
            if (mac.isNullOrBlank()) continue
            bands++
            onlyBand = mac.uppercase(Locale.ROOT)
        }
        if (bands == 1 && onlyBand != null) {
            selectedAddress = onlyBand
            return
        }
        if (devices.size != 1) return
        val only = devices[0].address
        if (only != null) selectedAddress = only.uppercase(Locale.ROOT)
    }

    private fun startBindingImport() {
        if (changingOwnership) return
        if (!rootGranted) {
            requestRoot()
            return
        }
        if (!isUnlocked()) {
            uiState = uiState.copy(ownershipStatus = "解锁手机后再导入绑定。")
            return
        }
        uiState = uiState.copy(activeDialog = MainDialog.ConfirmImport)
    }

    private fun importFromDatabase(address: String) {
        if (worker.isShutdown) return
        databaseImportNote = ""
        uiState = uiState.copy(ownershipStatus = "正在读取本地数据库…")
        worker.execute {
            val selected = Bundle()
            if (address.isNotBlank()) selected.putString("address", address)
            val shown = try {
                contentResolver.call(CredentialProvider.URI, "importFromDatabase", null, selected)
            } catch (_: RuntimeException) {
                null
            }
            main.post {
                if (isDestroyed) return@post
                if (shown != null && "MULTIPLE_BANDS" == shown.getString("status")) {
                    chooseDatabaseBand(shown.getStringArray("choices"))
                    return@post
                }
                val resultText = show(shown)
                databaseImportNote = resultText
                uiState = uiState.copy(ownershipStatus = resultText)
                updateControls()
                refreshDevice()
            }
        }
    }

    private fun chooseDatabaseBand(choices: Array<String>?) {
        if (choices.isNullOrEmpty()) {
            uiState = uiState.copy(ownershipStatus = Copy.importResult("NO_BAND_KEY", "", "", ""))
            return
        }
        val items = choices.map { raw ->
            val parts = raw.split("\t", limit = 3)
            val address = if (parts.isNotEmpty()) parts[0] else ""
            val model = if (parts.size > 1) parts[1] else ""
            val name = if (parts.size > 2) parts[2] else ""
            BandChoice(address = address, label = BandCatalog.displayName(model, name))
        }
        uiState = uiState.copy(activeDialog = MainDialog.ChooseDatabaseBand(items))
    }

    private fun startProfileCapture() {
        if (changingOwnership) return
        if (!rootGranted) {
            requestRoot()
            return
        }
        if (!isUnlocked()) {
            uiState = uiState.copy(ownershipStatus = "解锁手机后再采集连接参数。")
            return
        }
        if (!hostSupported) {
            uiState = uiState.copy(ownershipStatus = "安装小米运动健康并配对手环后，即可采集连接参数。")
            return
        }
        if (needsOfficialRestore()) {
            uiState = uiState.copy(activeDialog = MainDialog.ConfirmRestoreForProfile)
            return
        }
        uiState = uiState.copy(activeDialog = MainDialog.ConfirmProfileCapture)
    }

    private fun startProfileWindow() {
        launchHostAfterOpen = true
        action("openDiagnostics", null)
    }
}

@Composable
private fun MainScreen(
    state: MainActivity.MainUiState,
    onPrimaryAction: () -> Unit,
    onOpenKernelSu: () -> Unit,
    onTogglePrereq: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onOpenAppNotifications: () -> Unit,
    onOpenHealthListener: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenWeather: () -> Unit,
    onSleepPauseToggle: (Boolean) -> Unit,
    onShowGuide: () -> Unit,
    onOpenSystemPermissions: () -> Unit,
    onShareDebugLog: () -> Unit,
    onRestoreOfficial: () -> Unit,
    onRequestRemove: () -> Unit,
    onToggleAdvanced: () -> Unit,
    onSelectBonded: (String) -> Unit,
    onOpenImportWindow: () -> Unit,
    onCloseWindows: () -> Unit,
    onLaunchMiHealth: () -> Unit,
    onOpenDeviceLog: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenDiagnostic: () -> Unit,
    onStartProfileCapture: () -> Unit,
    onRequestProtocolCapture: () -> Unit,
    onDismissDialog: () -> Unit,
    onConfirmHostVersion: (String) -> Unit,
    onConfirmRemove: () -> Unit,
    onConfirmAdd: () -> Unit,
    onConfirmImport: () -> Unit,
    onChooseDatabaseBand: (String) -> Unit,
    onConfirmRestoreForProfile: () -> Unit,
    onConfirmProfileCapture: () -> Unit,
    onConfirmProtocolCapture: () -> Unit,
) {
    OriginBandScaffold(
        title = "OriginBand",
        largeTopBar = true,
    ) {
        // Status card
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.deviceName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(text = state.statusText, tone = state.statusTone)
            }
            if (state.connectionHint.isNotBlank()) {
                Text(
                    text = state.connectionHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.metricsLine.isNotBlank()) {
                Text(
                    text = state.metricsLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.healthLine.isNotBlank()) {
                Text(
                    text = state.healthLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.ownershipStatus.isNotBlank()) {
                Text(
                    text = state.ownershipStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            PrimaryButton(
                label = state.primaryLabel,
                enabled = state.primaryEnabled,
                onClick = onPrimaryAction,
            )
        }

        // First-run checklist
        if (state.showChecklist) {
            SectionCard(title = "首次设置 · 第 ${state.visibleStepIndex} 步（共 3 步）") {
                StepRow(1, "授权 Root", stepStateCompose(1, state.visibleStepIndex))
                StepRow(2, "导入绑定", stepStateCompose(2, state.visibleStepIndex))
                StepRow(3, "添加到健康", stepStateCompose(3, state.visibleStepIndex))
                if (state.primaryHint.isNotBlank()) {
                    Text(
                        text = state.primaryHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!state.rootGranted) {
                    SecondaryButton(
                        label = "打开 Root 管理器",
                        enabled = !state.probingRoot,
                        onClick = onOpenKernelSu,
                    )
                }
                ExpandableSection(
                    title = "开始前准备",
                    expanded = state.prereqExpanded,
                    onToggle = onTogglePrereq,
                ) {
                    Text(
                        text = Copy.SETUP_PREREQ,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Actionable notices
        if (state.showNearbyNotice) {
            NoticeCard(
                tone = Tone.ATTENTION,
                message = "授权附近设备后即可连接手环。",
                actionLabel = "授权附近设备",
                filled = true,
                onAction = onRequestBluetooth,
            )
        }
        if (state.showNotifyNotice) {
            NoticeCard(
                tone = Tone.NEUTRAL,
                message = "开启通知后，可在通知栏看到手环连接状态。",
                actionLabel = "开启通知",
                onAction = onOpenAppNotifications,
            )
        }
        if (state.showHealthAccessNotice) {
            NoticeCard(
                tone = Tone.ACTIVE,
                message = "在系统设置里重新开启 vivo 健康的通知使用权，手机通知就能转到手环。",
                actionLabel = "打开通知使用权",
                onAction = onOpenHealthListener,
            )
        }

        // Features
        SectionHeading(text = "功能")
        SectionCard {
            NavRow(title = "设备详情", onClick = onOpenDetails)
            CardDivider()
            NavRow(title = "天气同步", onClick = onOpenWeather)
            CardDivider()
            Text(
                text = "在控制中心添加「手环」磁贴，下拉或点按即可连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard {
            if (state.sleepStateUnsupported) {
                Text(
                    text = "当前手环会在下次同步时更新睡眠状态。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                SettingSwitchRow(
                    title = "入睡后暂停音乐",
                    subtitle = "手环报告入睡时暂停正在播放的音乐，醒来后保持暂停。",
                    checked = state.sleepPauseEnabled,
                    onCheckedChange = onSleepPauseToggle,
                )
            }
        }

        // Help
        SectionHeading(text = "帮助")
        SectionCard {
            NavRow(title = "使用说明", onClick = onShowGuide)
            CardDivider()
            NavRow(title = "系统权限设置", onClick = onOpenSystemPermissions)
            CardDivider()
            NavRow(title = "发送调试日志", onClick = onShareDebugLog)
        }

        // Management
        if (state.showManagement) {
            SectionHeading(text = "管理")
            SectionCard {
                if (state.showRestore) {
                    SecondaryButton(
                        label = "恢复小米运动健康",
                        enabled = !state.changingOwnership,
                        onClick = onRestoreOfficial,
                    )
                }
                if (state.showRemove) {
                    DangerOutlinedButton(
                        label = "从健康移除",
                        enabled = !state.changingOwnership,
                        onClick = onRequestRemove,
                    )
                }
                Text(
                    text = "绑定和健康历史始终保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Advanced & Debug
        ExpandableSection(
            title = "高级设置",
            expanded = state.advancedExpanded,
            onToggle = onToggleAdvanced,
        ) {
            SectionCard(title = "绑定导入") {
                Text(
                    text = "首页的「导入绑定」直接读取小米运动健康的本地数据库。" +
                        "这里保留旧式导入窗口：选择已配对设备，在 120 秒内由官方应用重新读取绑定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.bondedCaption.isNotBlank()) {
                    Text(
                        text = state.bondedCaption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.bondedDevices.forEach { device ->
                    val selected = device.mac.isNotBlank() && device.mac == state.selectedAddress
                    BondedDeviceChoiceRow(
                        name = device.name,
                        selected = selected,
                        onClick = { onSelectBonded(device.mac) },
                    )
                }
                PrimaryButton(
                    label = "开启导入窗口（120 秒）",
                    enabled = state.openWindowEnabled,
                    onClick = onOpenImportWindow,
                )
                SecondaryButton(label = "关闭导入和采集窗口", onClick = onCloseWindows)
                SecondaryButton(label = "打开小米运动健康", onClick = onLaunchMiHealth)
                SecondaryButton(
                    label = "恢复官方管理",
                    enabled = state.advancedRestoreEnabled,
                    onClick = onRestoreOfficial,
                )
                if (state.importStatus.isNotBlank()) {
                    Text(
                        text = state.importStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            SectionCard(title = "调试") {
                NavRow(title = "拉取设备日志", onClick = onOpenDeviceLog)
                CardDivider()
                NavRow(title = "协议测试", onClick = onOpenLab)
                if (BuildConfig.DEBUG) {
                    CardDivider()
                    NavRow(title = "独立连接诊断", onClick = onOpenDiagnostic)
                    SecondaryButton(
                        label = "采集连接参数（120 秒）",
                        onClick = onStartProfileCapture,
                    )
                    SecondaryButton(
                        label = "采集协议消息（120 秒）",
                        onClick = onRequestProtocolCapture,
                    )
                }
            }
        }
    }

    when (val dialog = state.activeDialog) {
        MainActivity.MainDialog.None -> Unit
        MainActivity.MainDialog.Guide -> InfoDialog(
            title = "使用说明",
            message = Copy.GUIDE,
            onDismiss = onDismissDialog,
        )
        is MainActivity.MainDialog.HostVersion -> InfoDialog(
            title = "建议使用实测版本",
            message = dialog.message,
            onDismiss = { onConfirmHostVersion(dialog.fingerprint) },
        )
        MainActivity.MainDialog.ConfirmRemove -> ConfirmDialog(
            title = "从健康移除这只手环？",
            message = "桥接会断开连接，把手环交还小米运动健康。健康历史和加密绑定都会保留，之后可随时重新添加。",
            confirmLabel = "移除",
            destructive = true,
            onConfirm = onConfirmRemove,
            onDismiss = onDismissDialog,
        )
        MainActivity.MainDialog.ConfirmAdd -> ConfirmDialog(
            title = "添加到健康并接管？",
            message = "小米运动健康会暂停，由桥接接管手环。开始前请结束表盘安装、OTA 升级和 NFC 操作。绑定和健康历史都会保留。",
            confirmLabel = "添加并接管",
            onConfirm = onConfirmAdd,
            onDismiss = onDismissDialog,
        )
        MainActivity.MainDialog.ConfirmImport -> ConfirmDialog(
            title = "导入绑定？",
            message = "用 root 读取小米运动健康本地数据库中的手环密钥。全程在本机完成，密钥保存在本应用的私有目录里。",
            confirmLabel = "读取",
            onConfirm = onConfirmImport,
            onDismiss = onDismissDialog,
        )
        is MainActivity.MainDialog.ChooseDatabaseBand -> ChooseBandDialog(
            choices = dialog.choices,
            onChoose = onChooseDatabaseBand,
            onDismiss = onDismissDialog,
        )
        MainActivity.MainDialog.ConfirmRestoreForProfile -> ConfirmDialog(
            title = "先恢复官方管理",
            message = "采集连接参数时要用到小米运动健康。先恢复官方管理，随后会打开采集窗口。",
            confirmLabel = "恢复",
            onConfirm = onConfirmRestoreForProfile,
            onDismiss = onDismissDialog,
        )
        MainActivity.MainDialog.ConfirmProfileCapture -> ConfirmDialog(
            title = "采集连接参数？",
            message = "将开启 120 秒采集窗口，随后打开小米运动健康。点开这只手环，让它重新连上。",
            confirmLabel = "开始采集",
            onConfirm = onConfirmProfileCapture,
            onDismiss = onDismissDialog,
        )
        MainActivity.MainDialog.ConfirmProtocolCapture -> ConfirmDialog(
            title = "采集所选手环的协议消息",
            message = "消息可能包含通知和健康数据。原始内容保存在本机私有目录，用于本次协议分析。",
            confirmLabel = "开始采集",
            onConfirm = onConfirmProtocolCapture,
            onDismiss = onDismissDialog,
        )
    }
}

private fun stepStateCompose(index: Int, current: Int): StepState {
    if (index < current) return StepState.DONE
    return if (index == current) StepState.CURRENT else StepState.LATER
}

@Composable
private fun BondedDeviceChoiceRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !selected, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) colors.primary else colors.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            )
        }
    }
}

@Composable
private fun ChooseBandDialog(
    choices: List<MainActivity.BandChoice>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "选择要导入的手环") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                choices.forEach { choice ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onChoose(choice.address) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Text(
                            text = choice.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        },
    )
}
