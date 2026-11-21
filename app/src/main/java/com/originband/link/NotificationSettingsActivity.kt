// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.originband.link.notify.AppLabels
import com.originband.link.notify.BandNotificationListener
import com.originband.link.notify.FindPhone
import com.originband.link.notify.NotificationSettingsPolicy
import com.originband.link.notify.PhoneAlarmNotice
import com.originband.link.notify.SleepMusic
import com.originband.link.service.BandLiveService
import com.originband.link.service.OwnershipController
import com.originband.link.ui.CardDivider
import com.originband.link.ui.NavRow
import com.originband.link.ui.OriginBandScaffold
import com.originband.link.ui.PrimaryButton
import com.originband.link.ui.SecondaryButton
import com.originband.link.ui.SectionCard
import com.originband.link.ui.SettingSwitchRow
import com.originband.link.ui.StatusBadge
import com.originband.link.ui.Tone
import com.originband.link.ui.setOriginBandContent
import java.util.concurrent.Executors

/** Configures notification listener access, app allowlist, calls, music control, and local find-phone. */
class NotificationSettingsActivity : ComponentActivity() {
    @Immutable
    data class AppEntry(
        val packageName: String,
        val label: String,
        val popular: Boolean,
    )

    @Immutable
    data class SettingsUiState(
        val listenerGranted: Boolean = false,
        val listenerConnected: Boolean = false,
        val grantingListener: Boolean = false,
        val listenerFeedback: String = "",
        val notifyEnabled: Boolean = true,
        val showBody: Boolean = true,
        val screenOnPush: Boolean = true,
        val selectedPackages: Set<String> = emptySet(),
        val callsEnabled: Boolean = false,
        val callsHangup: Boolean = true,
        val callsSmsReply: Boolean = false,
        val hasPhoneStatePermission: Boolean = false,
        val hasAnswerCallsPermission: Boolean = false,
        val hasSmsPermission: Boolean = false,
        val musicEnabled: Boolean = true,
        val sleepStateUnsupported: Boolean = false,
        val sleepPauseEnabled: Boolean = false,
        val findPhoneRinging: Boolean = false,
        val allowlistOpen: Boolean = false,
        val allowlistApps: List<AppEntry> = emptyList(),
        val allowlistLoading: Boolean = false,
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: SharedPreferences
    private var uiState by mutableStateOf(SettingsUiState())

    private val findPhoneListener = Runnable {
        if (!isDestroyed) {
            uiState = uiState.copy(findPhoneRinging = FindPhone.isRinging())
        }
    }

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        BandLiveService.refreshNotificationSettings(this)
        refresh()
    }

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        BandLiveService.refreshNotificationSettings(this)
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = getSharedPreferences(BandNotificationListener.SETTINGS, MODE_PRIVATE)
        FindPhone.setStateListener(findPhoneListener)
        setOriginBandContent {
            NotificationSettingsScreen(
                state = uiState,
                onBack = ::finish,
                onGrantListener = ::grantListenerWithRoot,
                onOpenListenerSettings = ::openListenerSettings,
                onRebindListener = ::rebindListener,
                onNotifyEnabledChange = { checked -> updateBoolean("enabled", checked) },
                onShowBodyChange = { checked -> updateBoolean("showBody", checked) },
                onScreenOnPushChange = { checked -> updateBoolean("screenOnPush", checked) },
                onOpenAllowlist = ::openAllowlistDialog,
                onDismissAllowlist = { uiState = uiState.copy(allowlistOpen = false) },
                onTogglePackage = ::toggleAllowedPackage,
                onSelectPopularApps = ::selectPopularApps,
                onClearAllowlist = ::clearAllowlist,
                onCallsEnabledChange = ::setCallsEnabled,
                onCallsHangupChange = ::setCallsHangup,
                onCallsSmsReplyChange = ::setCallsSmsReply,
                onRequestCallPermissions = ::requestCallPermissions,
                onRequestSmsPermission = ::requestSmsPermission,
                onMusicEnabledChange = { checked -> updateBoolean("musicEnabled", checked) },
                onSleepPauseChange = ::applySleepPause,
                onToggleFindPhone = ::toggleFindPhone,
            )
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        FindPhone.setStateListener(null)
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        if (isDestroyed || worker.isShutdown) return
        val listenerGranted = BandNotificationListener.accessGranted(this)
        val listenerConnected = BandNotificationListener.listenerConnected()
        val selected = HashSet(settings.getStringSet("packages", emptySet()) ?: emptySet())
        val notifyEnabled = settings.getBoolean("enabled", true)
        val showBody = settings.getBoolean("showBody", true)
        val screenOnPush = settings.getBoolean("screenOnPush", true)
        val callsEnabled = settings.getBoolean("callsEnabled", false)
        val callsHangup = settings.getBoolean("callsHangup", true)
        val callsSmsReply = settings.getBoolean("callsSmsReply", false)
        val musicEnabled = settings.getBoolean("musicEnabled", true)
        val phoneGranted = hasPermission(Manifest.permission.READ_PHONE_STATE)
        val hangupGranted = hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)
        val smsGranted = hasPermission(Manifest.permission.SEND_SMS)
        val ringing = FindPhone.isRinging()

        uiState = uiState.copy(
            listenerGranted = listenerGranted,
            listenerConnected = listenerConnected,
            notifyEnabled = notifyEnabled,
            showBody = showBody,
            screenOnPush = screenOnPush,
            selectedPackages = selected,
            callsEnabled = callsEnabled,
            callsHangup = callsHangup,
            callsSmsReply = callsSmsReply,
            hasPhoneStatePermission = phoneGranted,
            hasAnswerCallsPermission = hangupGranted,
            hasSmsPermission = smsGranted,
            musicEnabled = musicEnabled,
            findPhoneRinging = ringing,
        )

        worker.execute {
            val sleepUnsupported = BandLiveService.sleepStateUnsupportedForCurrentDevice(this)
            val sleepPause = if (sleepUnsupported) false else SleepMusic.enabled(this)
            main.post {
                if (isDestroyed) return@post
                uiState = uiState.copy(
                    sleepStateUnsupported = sleepUnsupported,
                    sleepPauseEnabled = sleepPause,
                )
            }
        }
    }

    private fun grantListenerWithRoot() {
        if (isDestroyed || worker.isShutdown || uiState.grantingListener) return
        uiState = uiState.copy(grantingListener = true, listenerFeedback = "")
        worker.execute {
            val rootOk = OwnershipController(this).probeRoot()
            val allowOk = rootOk && OwnershipController.allowLocalListener()
            if (allowOk || BandNotificationListener.accessGranted(this)) {
                BandNotificationListener.ensureEnabled(this)
                BandLiveService.refreshNotificationSettings(this)
            }
            main.postDelayed(
                {
                    if (isDestroyed) return@postDelayed
                    val granted = BandNotificationListener.accessGranted(this)
                    val feedback = when {
                        granted -> "通知使用权已开启。"
                        !rootOk -> "Root 授权待确认，可点下方前往系统设置手动开启通知使用权。"
                        else -> "需要打开系统设置页面手动开启通知使用权。"
                    }
                    uiState = uiState.copy(grantingListener = false, listenerFeedback = feedback)
                    refresh()
                },
                350L,
            )
        }
    }

    private fun openListenerSettings() {
        val detail = BandNotificationListener.listenerSettingsIntent(this)
        try {
            startActivity(detail)
            return
        } catch (_: RuntimeException) {
        }
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (_: RuntimeException) {
            uiState = uiState.copy(listenerFeedback = "请在系统设置 → 通知 → 通知使用权中找到 OriginBand。")
        }
    }

    private fun rebindListener() {
        BandNotificationListener.ensureEnabled(this)
        BandLiveService.refreshNotificationSettings(this)
        uiState = uiState.copy(listenerFeedback = "已发送重新绑定请求。")
        main.postDelayed(
            {
                if (!isDestroyed) refresh()
            },
            600L,
        )
    }

    private fun updateBoolean(key: String, checked: Boolean) {
        settings.edit().putBoolean(key, checked).apply()
        BandLiveService.refreshNotificationSettings(this)
        refresh()
    }

    private fun openAllowlistDialog() {
        uiState = uiState.copy(
            allowlistOpen = true,
            allowlistLoading = uiState.allowlistApps.isEmpty(),
        )
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        if (isDestroyed || worker.isShutdown) return
        val selectedSnapshot = HashSet(settings.getStringSet("packages", emptySet()) ?: emptySet())
        val observedSnapshot = HashSet(settings.getStringSet("observedPackages", emptySet()) ?: emptySet())
        worker.execute {
            val pm = packageManager
            val seen = HashMap<String, AppEntry>()
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = try {
                pm.queryIntentActivities(launcherIntent, 0)
            } catch (_: RuntimeException) {
                emptyList()
            }
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == packageName || pkg == PhoneAlarmNotice.CLOCK || seen.containsKey(pkg)) continue
                val label = AppLabels.prefer(pkg, info.loadLabel(pm)).ifBlank { AppLabels.label(this, pkg) }.ifBlank { pkg }
                seen[pkg] = AppEntry(
                    packageName = pkg,
                    label = label,
                    popular = NotificationSettingsPolicy.POPULAR_PACKAGES.contains(pkg),
                )
            }
            for (pkg in selectedSnapshot + observedSnapshot) {
                if (pkg == packageName || pkg == PhoneAlarmNotice.CLOCK || seen.containsKey(pkg)) continue
                try {
                    pm.getApplicationInfo(pkg, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    continue
                }
                val label = AppLabels.label(this, pkg).ifBlank { pkg }
                seen[pkg] = AppEntry(
                    packageName = pkg,
                    label = label,
                    popular = NotificationSettingsPolicy.POPULAR_PACKAGES.contains(pkg),
                )
            }
            val popularOrder = NotificationSettingsPolicy.POPULAR_PACKAGES.withIndex().associate { it.value to it.index }
            val sorted = seen.values.sortedWith(
                compareBy<AppEntry> { !selectedSnapshot.contains(it.packageName) }
                    .thenBy { popularOrder[it.packageName] ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }
            )
            main.post {
                if (isDestroyed) return@post
                uiState = uiState.copy(allowlistApps = sorted, allowlistLoading = false)
            }
        }
    }

    private fun savePackages(packages: Set<String>) {
        settings.edit().putStringSet("packages", HashSet(packages)).apply()
        BandLiveService.refreshNotificationSettings(this)
        uiState = uiState.copy(selectedPackages = HashSet(packages))
    }

    private fun toggleAllowedPackage(packageName: String, selected: Boolean) {
        val next = NotificationSettingsPolicy.togglePackage(uiState.selectedPackages, packageName, selected)
        savePackages(next)
    }

    private fun selectPopularApps() {
        val installed = HashSet<String>(uiState.allowlistApps.size + uiState.selectedPackages.size)
        for (entry in uiState.allowlistApps) installed.add(entry.packageName)
        for (pkg in NotificationSettingsPolicy.POPULAR_PACKAGES) {
            if (installed.contains(pkg)) continue
            try {
                packageManager.getApplicationInfo(pkg, 0)
                installed.add(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        val popular = NotificationSettingsPolicy.popularInstalled(installed)
        savePackages(popular)
    }

    private fun clearAllowlist() {
        savePackages(emptySet())
    }

    private fun setCallsEnabled(checked: Boolean) {
        settings.edit().putBoolean("callsEnabled", checked).apply()
        BandLiveService.refreshNotificationSettings(this)
        refresh()
        if (checked) requestMissingCallPermissions(includePhoneState = true)
    }

    private fun setCallsHangup(checked: Boolean) {
        settings.edit().putBoolean("callsHangup", checked).apply()
        BandLiveService.refreshNotificationSettings(this)
        refresh()
        if (checked) requestMissingCallPermissions(includePhoneState = false)
    }

    private fun setCallsSmsReply(checked: Boolean) {
        settings.edit().putBoolean("callsSmsReply", checked).apply()
        BandLiveService.refreshNotificationSettings(this)
        refresh()
        if (checked && !hasPermission(Manifest.permission.SEND_SMS)) {
            requestSmsPermission()
        }
    }

    private fun requestCallPermissions() {
        requestMissingCallPermissions(includePhoneState = true)
    }

    private fun requestMissingCallPermissions(includePhoneState: Boolean) {
        if (isDestroyed || worker.isShutdown) return
        val needsNow =
            (includePhoneState && !hasPermission(Manifest.permission.READ_PHONE_STATE)) ||
                !hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)
        if (!needsNow) return
        worker.execute {
            OwnershipController.grantCallPermissions()
            main.post {
                if (isDestroyed) return@post
                BandLiveService.refreshNotificationSettings(this)
                refresh()
                val missing = ArrayList<String>(2)
                if (includePhoneState && !hasPermission(Manifest.permission.READ_PHONE_STATE)) {
                    missing.add(Manifest.permission.READ_PHONE_STATE)
                }
                if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
                    missing.add(Manifest.permission.ANSWER_PHONE_CALLS)
                }
                if (missing.isNotEmpty()) {
                    callPermissionLauncher.launch(missing.toTypedArray())
                }
            }
        }
    }

    private fun requestSmsPermission() {
        if (isDestroyed || worker.isShutdown || hasPermission(Manifest.permission.SEND_SMS)) return
        worker.execute {
            OwnershipController.grantSmsPermission()
            main.post {
                if (isDestroyed) return@post
                BandLiveService.refreshNotificationSettings(this)
                refresh()
                if (!hasPermission(Manifest.permission.SEND_SMS)) {
                    smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                }
            }
        }
    }

    private fun applySleepPause(enabled: Boolean) {
        if (SleepMusic.setEnabled(this, enabled)) {
            uiState = uiState.copy(sleepPauseEnabled = enabled)
            BandLiveService.sleepPauseChanged(this)
        }
    }

    private fun toggleFindPhone() {
        if (FindPhone.isRinging()) {
            FindPhone.stopFromPhone(this)
        } else {
            FindPhone.start(this)
        }
        uiState = uiState.copy(findPhoneRinging = FindPhone.isRinging())
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun NotificationSettingsScreen(
    state: NotificationSettingsActivity.SettingsUiState,
    onBack: () -> Unit,
    onGrantListener: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onRebindListener: () -> Unit,
    onNotifyEnabledChange: (Boolean) -> Unit,
    onShowBodyChange: (Boolean) -> Unit,
    onScreenOnPushChange: (Boolean) -> Unit,
    onOpenAllowlist: () -> Unit,
    onDismissAllowlist: () -> Unit,
    onTogglePackage: (String, Boolean) -> Unit,
    onSelectPopularApps: () -> Unit,
    onClearAllowlist: () -> Unit,
    onCallsEnabledChange: (Boolean) -> Unit,
    onCallsHangupChange: (Boolean) -> Unit,
    onCallsSmsReplyChange: (Boolean) -> Unit,
    onRequestCallPermissions: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    onMusicEnabledChange: (Boolean) -> Unit,
    onSleepPauseChange: (Boolean) -> Unit,
    onToggleFindPhone: () -> Unit,
) {
    OriginBandScaffold(title = "通知与联动", onBack = onBack) {
        // Notification listener access
        SectionCard(title = "通知使用权") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "OriginBand 通知监听",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                val (badgeText, badgeTone) = when {
                    state.listenerGranted && state.listenerConnected -> "已连接" to Tone.POSITIVE
                    state.listenerGranted -> "已授权" to Tone.ACTIVE
                    else -> "待授权" to Tone.ATTENTION
                }
                StatusBadge(text = badgeText, tone = badgeTone)
            }
            val desc = when {
                !state.listenerGranted -> "开启通知使用权后，系统消息转发、音乐信息同步与来电姓名读取才能正常工作。"
                !state.listenerConnected -> "通知使用权已就绪，正在等待系统绑定监听服务；如长时待连接可点下方重新绑定。"
                else -> "通知监听服务运行中，支持转发应用通知、同步媒体播放状态与读取来电联系人名称。"
            }
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.listenerFeedback.isNotBlank()) {
                Text(
                    text = state.listenerFeedback,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!state.listenerGranted) {
                PrimaryButton(
                    label = if (state.grantingListener) "正在授予通知使用权…" else "一键授权通知使用权",
                    enabled = !state.grantingListener,
                    onClick = onGrantListener,
                )
            }
            SecondaryButton(
                label = "打开系统通知使用权设置",
                onClick = onOpenListenerSettings,
            )
            if (state.listenerGranted && !state.listenerConnected) {
                SecondaryButton(
                    label = "重新绑定通知服务",
                    onClick = onRebindListener,
                )
            }
        }

        // App notifications
        SectionCard(title = "应用通知转发") {
            SettingSwitchRow(
                title = "应用通知转发",
                subtitle = "将手机收到的通知同步到手环提醒。",
                checked = state.notifyEnabled,
                onCheckedChange = onNotifyEnabledChange,
            )
            SettingSwitchRow(
                title = "显示通知正文",
                subtitle = "关闭后手环仅提醒应用与标题，隐藏消息详情。",
                checked = state.showBody,
                onCheckedChange = onShowBodyChange,
            )
            SettingSwitchRow(
                title = "亮屏时照常推送",
                subtitle = "关闭后仅在息屏或锁屏时向手环推送消息。",
                checked = state.screenOnPush,
                onCheckedChange = onScreenOnPushChange,
            )
            CardDivider()
            NavRow(
                title = "应用白名单",
                subtitle = NotificationSettingsPolicy.allowlistSummary(state.selectedPackages.size),
                onClick = onOpenAllowlist,
            )
        }

        // Calls
        SectionCard(title = "来电提醒") {
            SettingSwitchRow(
                title = "来电提醒",
                subtitle = "手机响铃时在手环显示来电人或号码。",
                checked = state.callsEnabled,
                onCheckedChange = onCallsEnabledChange,
            )
            SettingSwitchRow(
                title = "手环挂断来电",
                subtitle = "允许在手环端直接拒接或挂断当前通话。",
                checked = state.callsHangup,
                onCheckedChange = onCallsHangupChange,
            )
            SettingSwitchRow(
                title = "短信回复挂断",
                subtitle = "手环拒接来电时附带发送快捷短信（需短信权限）。",
                checked = state.callsSmsReply,
                onCheckedChange = onCallsSmsReplyChange,
            )
            if (state.callsEnabled && (!state.hasPhoneStatePermission || !state.hasAnswerCallsPermission)) {
                SecondaryButton(
                    label = "授予电话权限",
                    onClick = onRequestCallPermissions,
                )
            }
            if (state.callsSmsReply && !state.hasSmsPermission) {
                SecondaryButton(
                    label = "授予短信权限",
                    onClick = onRequestSmsPermission,
                )
            }
        }

        // Music
        SectionCard(title = "音乐联动") {
            SettingSwitchRow(
                title = "音乐同步与切歌",
                subtitle = "将正在播放的曲目同步到手环，并支持在手环端控制播放与音量。",
                checked = state.musicEnabled,
                onCheckedChange = onMusicEnabledChange,
            )
            CardDivider()
            if (state.sleepStateUnsupported) {
                Text(
                    text = "当前手环会在下次同步时更新睡眠状态。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SettingSwitchRow(
                    title = "入睡后暂停音乐",
                    subtitle = "手环报告入睡时暂停正在播放的音乐，醒来后保持暂停。",
                    checked = state.sleepPauseEnabled,
                    onCheckedChange = onSleepPauseChange,
                )
            }
        }

        // Find Phone
        SectionCard(title = "查找手机") {
            Text(
                text = "在手环上点按查找手机时，手机会以闹钟音量响铃并震动，点按通知即可停止。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SecondaryButton(
                label = if (state.findPhoneRinging) "停止试铃" else "试听查找手机响铃",
                onClick = onToggleFindPhone,
            )
        }
    }

    if (state.allowlistOpen) {
        AppAllowlistDialog(
            apps = state.allowlistApps,
            selectedPackages = state.selectedPackages,
            loading = state.allowlistLoading,
            onTogglePackage = onTogglePackage,
            onSelectPopular = onSelectPopularApps,
            onClearAll = onClearAllowlist,
            onDismiss = onDismissAllowlist,
        )
    }
}

@Composable
private fun AppAllowlistDialog(
    apps: List<NotificationSettingsActivity.AppEntry>,
    selectedPackages: Set<String>,
    loading: Boolean,
    onTogglePackage: (String, Boolean) -> Unit,
    onSelectPopular: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val cleanQuery = query.trim().lowercase()
    val filtered = remember(apps, cleanQuery) {
        if (cleanQuery.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.lowercase().contains(cleanQuery) ||
                    it.packageName.lowercase().contains(cleanQuery)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "选择允许转发的应用") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(text = "搜索应用名称或包名") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onSelectPopular) {
                        Text(text = "仅常用通信")
                    }
                    TextButton(onClick = onClearAll) {
                        Text(text = "恢复全部应用")
                    }
                }
                Text(
                    text = "${NotificationSettingsPolicy.allowlistSummary(selectedPackages.size)} · " +
                        "已保留全部应用时所有正常通知均会转发，勾选特定应用后将只转发已选应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loading) {
                    Text(
                        text = "正在加载已安装应用…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { it.packageName }) { entry ->
                            val selected = selectedPackages.contains(entry.packageName)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onTogglePackage(entry.packageName, !selected) },
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { checked ->
                                            onTogglePackage(entry.packageName, checked)
                                        },
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = entry.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "完成")
            }
        },
    )
}
