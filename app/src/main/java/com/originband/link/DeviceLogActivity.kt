// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import android.content.ClipData
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.originband.link.data.BandCatalog
import com.originband.link.data.BandStateRepository
import com.originband.link.data.DeviceLogPublisher
import com.originband.link.data.LocalPrefs
import com.originband.link.data.SessionLog
import com.originband.link.integration.DeviceCardProvider
import com.originband.link.protocol.XiaomiMassReceive
import com.originband.link.service.BandLiveService
import com.originband.link.ui.Copy
import com.originband.link.ui.OriginBandScaffold
import com.originband.link.ui.PrimaryButton
import com.originband.link.ui.SecondaryButton
import com.originband.link.ui.SectionCard
import com.originband.link.ui.StatusBadge
import com.originband.link.ui.Tone
import com.originband.link.ui.setOriginBandContent
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Pulls the band's complete device-log bundle with a blocking progress modal. */
class DeviceLogActivity : ComponentActivity() {
    @Immutable
    data class DeviceLogUiState(
        val deviceTitle: String = "正在读取手环…",
        val chipText: String = "检查连接",
        val chipTone: Tone = Tone.NEUTRAL,
        val deviceDetail: String = "",
        val pullLabel: String = "开始拉取",
        val pullEnabled: Boolean = false,
        val showReconnect: Boolean = false,
        val showShare: Boolean = false,
        val statusText: String = "有已连接的手环后即可开始。",
        val showProgressModal: Boolean = false,
        val progressPercent: Int? = null,
        val progressStatus: String = "正在请求手环准备完整 log 目录…",
    )

    companion object {
        private const val REFRESH_MS = 2_000L

        private fun deviceLogFailure(failure: Throwable): String {
            var cause: Throwable = failure
            while (cause.cause != null && cause.cause != cause) cause = cause.cause!!
            val code = cause.message?.let { SessionLog.redact(it) } ?: cause.javaClass.simpleName
            return when (code) {
                "DEVICE_LOG_NO_DATA" -> "手环暂无可导出的日志。稍后再试一次。"
                "DEVICE_LOG_DEVICE_BUSY" -> "手环正在准备日志，请稍后再试。"
                "DEVICE_LOG_DEVICE_REJECTED" -> "手环暂缓了日志导出请求。稍后再试一次。"
                "DEVICE_LOG_DIRECT_UPLOAD_UNSUPPORTED" -> "此固件使用直传模式，可先用「发送调试日志」。"
                "DEVICE_LOG_RECEIVE_FAILED", "MASS_RECEIVE_CANCELLED" -> "接收中途停止。确认手环保持连接后再试一次。"
                "LIVE_SESSION_REQUIRED", "DEVICE_LOG_SESSION_UNAVAILABLE" -> "手环连接正在重建，等状态显示「已连接」后再试。"
                else -> "设备日志拉取中断：$code。确认手环连接后重试。"
            }
        }

        private fun logMime(file: File): String {
            try {
                FileInputStream(file).use { input ->
                    val head = ByteArray(4)
                    val read = input.read(head)
                    if (read >= 2 && (head[0].toInt() and 0xff) == 0x1f && (head[1].toInt() and 0xff) == 0x8b) {
                        return "application/gzip"
                    }
                    if (read >= 2 && head[0].toInt() == 'P'.code && head[1].toInt() == 'K'.code) {
                        return "application/zip"
                    }
                }
            } catch (_: Exception) {
            }
            val name = file.name.lowercase(Locale.ROOT)
            return when {
                name.endsWith(".zip") -> "application/zip"
                name.endsWith(".tar") -> "application/x-tar"
                name.endsWith(".gz") || name.endsWith(".tgz") -> "application/gzip"
                else -> "application/octet-stream"
            }
        }

        private fun safeFailure(failure: Exception): String {
            val message = failure.message
            return if (message.isNullOrBlank()) failure.javaClass.simpleName else SessionLog.redact(message)
        }

        private fun size(bytes: Long): String {
            if (bytes < 1_024) return "$bytes B"
            if (bytes < 1_024 * 1_024) return "${(bytes + 1_023) / 1_024} KB"
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OriginBandDeviceLog").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var uiState by mutableStateOf(DeviceLogUiState())

    private var lastBundle: File? = null
    private var pulling = false
    private var lastProgressPart = -1

    private val refreshTick = Runnable { pollState() }
    private val progressPoll = Runnable { pollTransferProgress() }
    private val stateObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshDevice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setOriginBandContent {
            DeviceLogScreen(
                state = uiState,
                onBack = { finish() },
                onPull = ::pull,
                onReconnect = ::reconnect,
                onShareLast = ::shareLast,
            )
        }
        contentResolver.registerContentObserver(DeviceCardProvider.URI, false, stateObserver)
        refreshDevice()
        scheduleRefresh()
    }

    override fun onResume() {
        super.onResume()
        refreshDevice()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(stateObserver)
        main.removeCallbacks(refreshTick)
        main.removeCallbacks(progressPoll)
        worker.shutdown()
        super.onDestroy()
    }

    private fun refreshDevice() {
        if (isDestroyed || worker.isShutdown) return
        val users = getSystemService(UserManager::class.java)
        if (users != null && !users.isUserUnlocked) {
            uiState = uiState.copy(
                deviceTitle = "解锁后读取手环",
                chipText = "已锁定",
                chipTone = Tone.NEUTRAL,
                deviceDetail = "设备日志需要先解锁手机。",
                pullEnabled = false,
                showReconnect = false,
            )
            return
        }
        worker.execute {
            val registered = try {
                BandStateRepository(this).isRegistered()
            } catch (_: RuntimeException) {
                false
            }
            if (!registered) {
                main.post {
                    if (isDestroyed) return@post
                    uiState = uiState.copy(
                        deviceTitle = "等待已连接的手环",
                        chipText = "等待连接",
                        chipTone = Tone.NEUTRAL,
                        deviceDetail = "先在首页导入绑定并添加到健康，再回来拉取设备日志。",
                        pullEnabled = false,
                        showReconnect = false,
                    )
                }
                return@execute
            }

            val device = LocalPrefs.open(this, "band-state")
            val name = BandCatalog.displayName(device.getString("modelId", ""), device.getString("name", ""))
            val connected = device.getBoolean("connected", false)
            val detail = StringBuilder()
            val model = device.getString("modelId", "")
            val firmware = device.getString("verifiedFirmware", device.getString("firmware", ""))
            if (!model.isNullOrBlank()) detail.append("型号 ").append(model)
            if (!firmware.isNullOrBlank()) {
                if (detail.isNotEmpty()) detail.append("\n")
                detail.append("固件 ").append(firmware)
            }
            val battery = device.getInt("battery", -1)
            if (battery in 0..100) {
                if (detail.isNotEmpty()) detail.append("\n")
                detail.append("电量 ").append(battery).append('%')
            }
            val lastSync = device.getLong("lastSyncAtMs", 0)
            if (lastSync > 0) {
                if (detail.isNotEmpty()) detail.append("\n")
                detail.append("上次同步 ")
                    .append(DateFormat.getMediumDateFormat(this).format(Date(lastSync)))
                    .append(' ')
                    .append(DateFormat.getTimeFormat(this).format(Date(lastSync)))
            }
            val offlineHint = if (!connected) Copy.offlineReason(readLiveStatus()) else ""
            main.post {
                if (isDestroyed) return@post
                var nextStatus = uiState.statusText
                if (!connected && !pulling && lastBundle == null) {
                    nextStatus = offlineHint.ifBlank {
                        "手环连接建立后即可拉取。可点「尝试重新连接」，或在首页点「立即同步」。"
                    }
                }
                uiState = uiState.copy(
                    deviceTitle = if (name.isBlank()) "已登记手环" else name,
                    chipText = if (connected) "已连接" else "等待连接",
                    chipTone = if (connected) Tone.POSITIVE else Tone.NEUTRAL,
                    deviceDetail = if (detail.isEmpty()) "尚无验证过的设备信息。" else detail.toString(),
                    pullEnabled = connected && !pulling,
                    showReconnect = !connected && !pulling,
                    statusText = nextStatus,
                )
            }
        }
    }

    private fun scheduleRefresh() {
        main.removeCallbacks(refreshTick)
        main.postDelayed(refreshTick, REFRESH_MS)
    }

    private fun pollState() {
        if (isDestroyed) return
        refreshDevice()
        scheduleRefresh()
    }

    private fun reconnect() {
        val result = BandLiveService.requestSync(this)
        val message = when (result) {
            "ACCEPTED" -> "正在重新连接手环，连接后即可开始拉取。"
            "DEVICE_NOT_REGISTERED" -> "先连接手环，再回来拉取设备日志。"
            "NATIVE_OWNERSHIP_REQUIRED" -> "当前由官方应用管理。请在首页点「立即同步」重新接管。"
            "OPEN_CONFIG_REQUIRED" -> "请打开 OriginBand 首页完成配置后再试。"
            else -> "回首页查看手环状态，再重新发起连接。"
        }
        uiState = uiState.copy(statusText = message)
        refreshDevice()
    }

    private fun pull() {
        if (pulling) return
        val device = LocalPrefs.open(this, "band-state")
        if (!device.getBoolean("registered", false) || !device.getBoolean("connected", false)) {
            uiState = uiState.copy(statusText = "先让手环连上，再开始拉取。")
            refreshDevice()
            return
        }
        pulling = true
        lastProgressPart = -1
        uiState = uiState.copy(
            pullEnabled = false,
            pullLabel = "正在拉取…",
            showReconnect = false,
            statusText = "正在请求手环准备完整 log 目录…",
            showProgressModal = true,
            progressPercent = null,
            progressStatus = "正在请求手环准备完整 log 目录…",
        )
        SessionLog.line(this, "UI_LOG_MODAL open build=${BuildConfig.VERSION_CODE} compose=true")
        main.removeCallbacks(progressPoll)
        main.post(progressPoll)
        SessionLog.start(this)
        BandLiveService.requestDeviceLogExport(this) { progress ->
            main.post { applyTransferProgress(progress) }
        }.whenComplete { received, requestFailure ->
            if (requestFailure != null) {
                showFailure(requestFailure)
                return@whenComplete
            }
            main.post {
                if (isDestroyed) return@post
                uiState = uiState.copy(
                    progressPercent = 100,
                    progressStatus = "接收完成，正在保存、展开并生成可浏览 ZIP…",
                    statusText = "手环日志接收完成，正在保存到手机下载目录…",
                )
                worker.execute {
                    try {
                        val published = DeviceLogPublisher.publish(this, received.file())
                        main.post {
                            if (isDestroyed) return@post
                            pulling = false
                            main.removeCallbacks(progressPoll)
                            val shared = published.browseFile ?: received.file()
                            lastBundle = shared
                            val unpacked = if (published.extractedFiles == 0) {
                                "压缩包已保存。"
                            } else {
                                "已展开 ${published.extractedFiles} 个文件（${size(published.extractedBytes)}），并生成可浏览 ZIP。"
                            }
                            uiState = uiState.copy(
                                showProgressModal = false,
                                progressPercent = 100,
                                pullLabel = "重新拉取",
                                showShare = true,
                                statusText = "已拉取 ${received.fileName()}（${size(received.bytes())}）。\n保存到 " +
                                    published.relativeDirectory.replaceFirst("^Download/".toRegex(), "下载/") +
                                    "\n$unpacked",
                            )
                            refreshDevice()
                            share(shared)
                        }
                    } catch (failure: Exception) {
                        main.post {
                            if (isDestroyed) return@post
                            pulling = false
                            main.removeCallbacks(progressPoll)
                            lastBundle = received.file()
                            uiState = uiState.copy(
                                showProgressModal = false,
                                pullLabel = "重试拉取",
                                showShare = true,
                                statusText = "手环日志已收到，但保存到下载目录时中断：${safeFailure(failure)}。可先通过下方按钮分享。",
                            )
                            refreshDevice()
                        }
                    }
                }
            }
        }
    }

    private fun showFailure(failure: Throwable) {
        main.post {
            if (isDestroyed) return@post
            pulling = false
            main.removeCallbacks(progressPoll)
            uiState = uiState.copy(
                showProgressModal = false,
                pullLabel = "重试拉取",
                statusText = deviceLogFailure(failure),
            )
            refreshDevice()
        }
    }

    private fun pollTransferProgress() {
        if (isDestroyed || !pulling) return
        applyTransferProgress(BandLiveService.deviceLogProgressSnapshot())
        main.postDelayed(progressPoll, 200)
    }

    private fun applyTransferProgress(progress: XiaomiMassReceive.Progress?) {
        if (isDestroyed || !pulling || progress == null || progress.totalParts() <= 0 || progress.currentPart() <= lastProgressPart) {
            return
        }
        lastProgressPart = progress.currentPart()
        val percent = minOf(100, progress.currentPart() * 100 / progress.totalParts())
        SessionLog.line(
            this,
            "UI_LOG_POLL part=${progress.currentPart()}/${progress.totalParts()} percent=$percent",
        )
        val text = "正在接收 ${progress.fileName()}\n" +
            "第 ${progress.currentPart()} 片（共 ${progress.totalParts()} 片） · $percent% · ${size(progress.bytes())}"
        uiState = uiState.copy(
            progressPercent = percent,
            progressStatus = text,
            statusText = text,
        )
    }

    private fun shareLast() {
        val bundle = lastBundle
        if (bundle != null && bundle.isFile) share(bundle)
    }

    private fun share(bundle: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "com.originband.link.debuglog", bundle)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = logMime(bundle)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OriginBand 设备日志")
                clipData = ClipData.newRawUri("device-log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "分享设备日志"))
        } catch (_: RuntimeException) {
            uiState = uiState.copy(statusText = "日志已保存在应用缓存，可稍后点「再次分享上次日志」。")
        }
    }

    private fun readLiveStatus(): String {
        val file = File(noBackupFilesDir, "live-status.txt")
        if (!file.isFile || file.length() <= 0 || file.length() > 2_048) return ""
        return try {
            String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }
}

@Composable
private fun DeviceLogScreen(
    state: DeviceLogActivity.DeviceLogUiState,
    onBack: () -> Unit,
    onPull: () -> Unit,
    onReconnect: () -> Unit,
    onShareLast: () -> Unit,
) {
    OriginBandScaffold(title = "拉取设备日志", onBack = onBack) {
        SectionCard(title = "设备") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.deviceTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(text = state.chipText, tone = state.chipTone)
            }
            if (state.deviceDetail.isNotBlank()) {
                Text(
                    text = state.deviceDetail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = "日志内容") {
            Text(
                text = "请求手环导出完整 log 目录，经反向 MASS 通道逐片接收，并保存到手机下载目录。" +
                    "zip、tar 和 tar.gz 包会同时展开。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "设备日志由手环生成，可能包含运行记录和健康相关信息；分享前请检查内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "操作") {
            PrimaryButton(
                label = state.pullLabel,
                enabled = state.pullEnabled,
                onClick = onPull,
            )
            if (state.showReconnect) {
                SecondaryButton(label = "尝试重新连接", onClick = onReconnect)
            }
            if (state.showShare) {
                SecondaryButton(label = "再次分享上次日志", onClick = onShareLast)
            }
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    if (state.showProgressModal) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
            title = { Text(text = "正在拉取设备日志") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = state.progressStatus,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val percent = state.progressPercent
                    if (percent == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { (percent.coerceIn(0, 100)) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }
}
