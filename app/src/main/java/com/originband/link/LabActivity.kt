// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.originband.link.data.BandStateRepository
import com.originband.link.data.LocalPrefs
import com.originband.link.notify.FindPhone
import com.originband.link.notify.PhoneDnd
import com.originband.link.protocol.BandAlarmCommand
import com.originband.link.protocol.BandDndCommand
import com.originband.link.protocol.BandMusicCommand
import com.originband.link.protocol.BandNotificationCommand
import com.originband.link.service.BandLiveService
import com.originband.link.ui.CardDivider
import com.originband.link.ui.NavRow
import com.originband.link.ui.OriginBandScaffold
import com.originband.link.ui.PrimaryButton
import com.originband.link.ui.SecondaryButton
import com.originband.link.ui.SectionCard
import com.originband.link.ui.setOriginBandContent
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Foreground protocol tests. The log keeps protocol fields; MAC, token, and health values stay in memory. */
class LabActivity : ComponentActivity() {
    @Immutable
    data class LabUiState(
        val sessionText: String = "正在检查连接会话…",
        val resultText: String = "选择下方任一测试开始。",
        val deviceInfoText: String = "正在读取…",
    )

    companion object {
        private val TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

        private fun deviceText(
            state: LocalPrefs,
            registered: Boolean,
            connected: Boolean,
            battery: Int,
        ): String {
            if (!registered) return "回首页导入绑定并添加到健康，这里会显示设备信息。"
            val text = StringBuilder()
            text.append("名称：").append(blank(state.getString("name", ""), "—")).append('\n')
            text.append("型号：").append(blank(state.getString("modelId", ""), "—")).append('\n')
            var firmware = state.getString("verifiedFirmware", "").orEmpty()
            if (firmware.isBlank()) firmware = state.getString("firmware", "").orEmpty()
            text.append("固件：").append(blank(firmware, "待读取")).append('\n')
            text.append("硬件：").append(blank(state.getString("verifiedHardware", ""), "待读取")).append('\n')
            text.append("连接：").append(if (connected) "已连接" else "等待连接").append('\n')
            text.append("电量：").append(if (battery in 0..100) "$battery%" else "待读取")
            if (state.getBoolean("charging", false)) text.append("（充电中）")
            text.append('\n')
            val sync = state.getLong("lastSyncAtMs", 0)
            text.append("上次同步：").append(if (sync > 0) TIME.format(Instant.ofEpochMilli(sync)) else "—").append('\n')
            val deviceId = state.getString("deviceId", "").orEmpty()
            text.append("设备号：").append(
                if (deviceId.length >= 16) deviceId.substring(0, 16) + "…" else blank(deviceId, "—")
            ).append('\n')
            text.append("MAC：").append(tailMac(state.getString("mac", "")))
            return text.toString()
        }

        private fun tailMac(mac: String?): String {
            if (mac == null || mac.length < 5) return "—"
            return "…" + mac.substring(mac.length - 5).replace(":", "")
        }

        private fun blank(value: String?, fallback: String): String {
            return if (value.isNullOrBlank()) fallback else value
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var uiState by mutableStateOf(LabUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setOriginBandContent {
            LabScreen(
                state = uiState,
                onBack = { finish() },
                onSyncNow = ::syncNow,
                onRefreshInfo = ::refresh,
                onSendNotify = {
                    send(
                        "测试通知",
                        BandNotificationCommand.post(
                            packageName,
                            "桥接测试",
                            "lab-notify",
                            1,
                            "测试通知",
                            "这是一条发往手环的测试消息。",
                            Instant.now(),
                            ZoneId.systemDefault(),
                        ),
                        sessionCommand = false,
                    )
                },
                onDismissNotify = {
                    send(
                        "撤回通知",
                        BandNotificationCommand.dismiss(packageName, "lab-notify", 1),
                        sessionCommand = false,
                    )
                },
                onIncomingCall = {
                    send(
                        "测试来电",
                        BandNotificationCommand.incomingCall("测试来电", Instant.now(), ZoneId.systemDefault()),
                        sessionCommand = false,
                    )
                },
                onEndCall = {
                    send("来电结束", BandNotificationCommand.endCall(), sessionCommand = false)
                },
                onFindStart = { FindPhone.start(this) },
                onFindStop = { FindPhone.stop(this) },
                onSendMusic = {
                    send(
                        "测试音乐",
                        BandMusicCommand.playback(50, "测试音乐", "桥接", 12, 180, true),
                        sessionCommand = true,
                    )
                },
                onSyncDnd = {
                    sendAll(
                        "免打扰",
                        BandDndCommand.mirror(
                            PhoneDnd.currentFilter(this),
                            BandLiveService.bandSyncEnabled(this),
                        ),
                    )
                },
                onAlarmRing = {
                    send(
                        "闹钟响铃",
                        BandAlarmCommand.operation(0, 1, (System.currentTimeMillis() / 1000L).toInt(), "测试闹钟"),
                        sessionCommand = true,
                    )
                },
                onAlarmDismiss = {
                    send("关闭闹钟", BandAlarmCommand.operation(1, 1, -1, ""), sessionCommand = true)
                },
                onAlarmSnooze = {
                    send(
                        "稍后响",
                        BandAlarmCommand.operation(2, 1, (System.currentTimeMillis() / 1000L).toInt(), "测试闹钟"),
                        sessionCommand = true,
                    )
                },
                onOpenDiagnostic = { startActivity(Intent(this, DiagnosticActivity::class.java)) },
                onCheckUpdate = ::checkUpdate,
            )
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun syncNow() {
        try {
            BandLiveService.start(this)
            uiState = uiState.copy(resultText = "已请求同步，手环连上后就可以发通知和来电。")
        } catch (failure: RuntimeException) {
            uiState = uiState.copy(
                resultText = "会话启动中断：${failure.javaClass.simpleName}。回首页点「立即同步」再试一次。"
            )
        }
        refresh()
    }

    private fun refresh() {
        if (isDestroyed || worker.isShutdown) return
        worker.execute {
            val state = LocalPrefs.open(this, "band-state")
            val registered = BandStateRepository(this).isRegistered()
            val connected = registered && state.getBoolean("connected", false)
            val ready = BandLiveService.notificationSessionReady(this)
            val battery = state.getInt("battery", -1)
            val devInfo = deviceText(state, registered, connected, battery)
            val sessText = when {
                ready -> "会话就绪，可以发通知、来电和音乐。"
                connected -> "已连接，会话准备中，点「立即同步」。"
                else -> "等待连接，点「立即同步」后开始测试。"
            }
            main.post {
                if (isDestroyed) return@post
                uiState = uiState.copy(
                    deviceInfoText = devInfo,
                    sessionText = sessText,
                )
            }
        }
    }

    private fun send(label: String, command: XiaomiProto.Command, sessionCommand: Boolean) {
        if (!ready()) return
        uiState = uiState.copy(resultText = "正在发送$label…")
        val pending = if (sessionCommand) {
            BandLiveService.sendSessionCommand(command)
        } else {
            BandLiveService.forwardHostNotification(this, command)
        }
        pending.whenComplete { _, error -> main.post { finishSend(label, error) } }
    }

    private fun sendAll(label: String, commands: List<XiaomiProto.Command>) {
        if (!ready()) return
        uiState = uiState.copy(resultText = "正在发送$label…")
        sendAllAt(label, commands, 0)
    }

    private fun sendAllAt(label: String, commands: List<XiaomiProto.Command>, index: Int) {
        if (index >= commands.size) {
            uiState = uiState.copy(resultText = "${label}已确认送达手环。共 ${commands.size} 包。")
            refresh()
            return
        }
        BandLiveService.sendSessionCommand(commands[index]).whenComplete { _, error ->
            main.post {
                if (isDestroyed) return@post
                if (error != null) {
                    finishSend("$label（第 ${index + 1} 包）", error)
                    return@post
                }
                sendAllAt(label, commands, index + 1)
            }
        }
    }

    private fun ready(): Boolean {
        if (BandLiveService.notificationSessionReady(this)) return true
        try {
            BandLiveService.start(this)
        } catch (_: RuntimeException) {
        }
        uiState = uiState.copy(resultText = "会话准备中。点「立即同步」，手环连上后再发送。")
        refresh()
        return false
    }

    private fun finishSend(label: String, error: Throwable?) {
        if (isDestroyed) return
        val detail = if (error == null) {
            "已确认送达手环。"
        } else {
            "发送中断：${error.message ?: error.javaClass.simpleName}。点「立即同步」后再试一次。"
        }
        Log.i("OriginBand", "LAB $label ${if (error == null) "ok" else error.javaClass.simpleName}")
        uiState = uiState.copy(resultText = label + detail)
        refresh()
    }

    private fun checkUpdate() {
        val latest = UpdateChecker.check(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        val notes = latest.notes()
        uiState = uiState.copy(
            resultText = notes.ifBlank { "当前版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）。" }
        )
    }
}

@Composable
private fun LabScreen(
    state: LabActivity.LabUiState,
    onBack: () -> Unit,
    onSyncNow: () -> Unit,
    onRefreshInfo: () -> Unit,
    onSendNotify: () -> Unit,
    onDismissNotify: () -> Unit,
    onIncomingCall: () -> Unit,
    onEndCall: () -> Unit,
    onFindStart: () -> Unit,
    onFindStop: () -> Unit,
    onSendMusic: () -> Unit,
    onSyncDnd: () -> Unit,
    onAlarmRing: () -> Unit,
    onAlarmDismiss: () -> Unit,
    onAlarmSnooze: () -> Unit,
    onOpenDiagnostic: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    OriginBandScaffold(title = "协议测试", onBack = onBack) {
        SectionCard(title = "会话与最近结果") {
            Text(
                text = state.sessionText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = state.resultText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(label = "立即同步", onClick = onSyncNow)
        }

        SectionCard(title = "设备信息") {
            Text(
                text = state.deviceInfoText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SecondaryButton(label = "刷新设备信息", onClick = onRefreshInfo)
        }

        SectionCard(title = "消息通知") {
            Text(
                text = "经当前会话发一条测试通知到这只手环。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(label = "发送测试通知", onClick = onSendNotify)
            SecondaryButton(label = "撤回测试通知", onClick = onDismissNotify)
        }

        SectionCard(title = "来电") {
            Text(
                text = "手环会显示来电界面，点「结束测试来电」即可关掉。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(label = "发送测试来电", onClick = onIncomingCall)
            SecondaryButton(label = "结束测试来电", onClick = onEndCall)
        }

        SectionCard(title = "查找手机") {
            Text(
                text = "调用查找手机响铃。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(label = "测试查找手机", onClick = onFindStart)
            SecondaryButton(label = "停止查找", onClick = onFindStop)
        }

        SectionCard(title = "音乐") {
            Text(
                text = "把手环上的播放信息刷成一条测试曲目。实际播放时自动同步，手环按键控制手机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(label = "发送测试音乐", onClick = onSendMusic)
        }

        SectionCard(title = "免打扰") {
            Text(
                text = "依次发送同步开关和手机当前的免打扰状态。手环侧的同步开关关着时只读回来，开关仍留在手环上。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(label = "同步免打扰", onClick = onSyncDnd)
        }

        SectionCard(title = "闹钟") {
            Text(
                text = "测试响铃、关闭和稍后响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(label = "测试闹钟响铃", onClick = onAlarmRing)
            SecondaryButton(label = "测试关闭闹钟", onClick = onAlarmDismiss)
            SecondaryButton(label = "测试稍后响", onClick = onAlarmSnooze)
        }

        SectionCard(title = "其他") {
            NavRow(title = "独立鉴权与电量", onClick = onOpenDiagnostic)
            CardDivider()
            NavRow(title = "检查更新", onClick = onCheckUpdate)
        }
    }
}
