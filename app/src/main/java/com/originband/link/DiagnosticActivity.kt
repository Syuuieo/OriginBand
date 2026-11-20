// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.originband.link.data.BandStateRepository
import com.originband.link.data.DeviceImageStore
import com.originband.link.data.LocalPrefs
import com.originband.link.protocol.SppDiagnosticClient
import com.originband.link.service.BandLiveService
import com.originband.link.service.OwnershipController
import com.originband.link.ui.OriginBandScaffold
import com.originband.link.ui.PrimaryButton
import com.originband.link.ui.SecondaryButton
import com.originband.link.ui.SectionCard
import com.originband.link.ui.setOriginBandContent
import java.util.concurrent.Executors

/** Foreground diagnostic page. Official package state stays with {@code OwnershipController}. */
class DiagnosticActivity : ComponentActivity() {
    @Immutable
    data class DiagnosticUiState(
        val status: String = "",
        val bluetoothGranted: Boolean = false,
        val running: Boolean = false,
        val liveReady: Boolean = false,
    )

    private val worker = Executors.newSingleThreadExecutor()
    private var uiState by mutableStateOf(DiagnosticUiState())

    @Volatile
    private var client: SppDiagnosticClient? = null

    private val bluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updatePermission()
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            uiState = uiState.copy(status = "附近设备已授权。确认官方应用已暂停，就可以开始诊断。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stored = LocalPrefs.open(this, "band-state").getInt("battery", -1)
        val initialStatus = if (stored in 0..100) {
            "上次读取电量：$stored%。随时可以开始下一次诊断。"
        } else {
            "准备就绪，点「开始鉴权与电量测试」。"
        }
        uiState = uiState.copy(status = initialStatus)
        setOriginBandContent {
            DiagnosticScreen(
                state = uiState,
                onBack = { finish() },
                onRequestBluetooth = { bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                onBegin = ::begin,
                onStartLive = ::startLive,
            )
        }
        updatePermission()
    }

    override fun onStop() {
        val active = client
        if (active != null) Thread(active::close, "OriginBandDiagnosticCancel").start()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun startLive() {
        worker.execute {
            val message = try {
                BandLiveService.start(this)
                "正在连接，鉴权完成后显示已连接。"
            } catch (_: RuntimeException) {
                "回首页添加设备并授权附近设备，再回来点这里。"
            }
            runOnUiThread { if (!isDestroyed) uiState = uiState.copy(status = message) }
        }
    }

    private fun updatePermission() {
        val granted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        uiState = uiState.copy(bluetoothGranted = granted, liveReady = false)
        worker.execute {
            val ready = BandStateRepository(this).isRegistered() && OwnershipController(this).nativeReady()
            runOnUiThread {
                if (!isDestroyed) uiState = uiState.copy(liveReady = granted && !uiState.running && ready)
            }
        }
    }

    private fun begin() {
        if (uiState.running) return
        uiState = uiState.copy(running = true)
        val active = SppDiagnosticClient(this) { code ->
            Log.i("OriginBand", "DIAG_$code")
            runOnUiThread { if (!isDestroyed) uiState = uiState.copy(status = code) }
        }
        client = active
        worker.execute {
            var message = try {
                BandLiveService.pauseForDiagnostic(this)
                val result = active.run()
                val card = try {
                    BandStateRepository(this).recordVerifiedDevice(
                        result.batteryPercent(),
                        result.batteryState(),
                        false,
                        result.firmware(),
                        result.hardware(),
                        result.sku(),
                    )
                    DeviceImageStore.requestRefresh(this)
                    "设备状态已记录电量，诊断连接已关闭。"
                } catch (failure: Exception) {
                    "设备状态稍后更新：" + failure.message
                }
                getSharedPreferences("diagnostic-result", 0).edit()
                    .putLong("verifiedAtMs", System.currentTimeMillis())
                    .putInt("batteryPercent", result.batteryPercent())
                    .putString("hardware", result.hardware())
                    .putString("firmware", result.firmware())
                    .commit()
                Log.i("OriginBand", "DIAG_SUCCESS battery=${result.batteryPercent()}")
                "诊断完成，连接已关闭。\n鉴权通过。\n电量：${result.batteryPercent()}%" +
                    "\n型号：${result.hardware()}\n固件：${result.firmware()}\n$card"
            } catch (failure: SppDiagnosticClient.Failure) {
                Log.i("OriginBand", "DIAG_FAILED ${failure.code}")
                "诊断已停止，连接已关闭。\n代码：${failure.code}"
            } catch (failure: Exception) {
                val category = failure.javaClass.simpleName
                Log.i("OriginBand", "DIAG_FAILED $category")
                "诊断中断，连接已关闭。\n类型：$category\n绑定和凭据保持原样。"
            }
            try {
                BandLiveService.resumeAfterDiagnostic(this)
            } catch (_: RuntimeException) {
                message += "\n登记已保留，回首页点「立即同步」恢复连接。"
            }
            val finalMessage = message
            runOnUiThread {
                client = null
                if (!isDestroyed) {
                    uiState = uiState.copy(running = false, status = finalMessage)
                    updatePermission()
                }
            }
        }
    }
}

@Composable
private fun DiagnosticScreen(
    state: DiagnosticActivity.DiagnosticUiState,
    onBack: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onBegin: () -> Unit,
    onStartLive: () -> Unit,
) {
    OriginBandScaffold(title = "独立连接诊断", onBack = onBack) {
        SectionCard(title = "诊断结果") {
            Text(
                text = state.status,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        SectionCard(title = "操作") {
            Text(
                text = "开始前，请结束小米运动健康里的表盘安装、OTA 升级和 NFC 操作，并暂停官方应用。" +
                    "诊断会完成鉴权、核对型号、读取电量，30 秒后自动断开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.bluetoothGranted) {
                PrimaryButton(label = "授权附近设备", onClick = onRequestBluetooth)
            }
            PrimaryButton(
                label = "开始鉴权与电量测试",
                enabled = state.bluetoothGranted && !state.running,
                onClick = onBegin,
            )
            SecondaryButton(
                label = "保持连接，实时更新手环电量",
                enabled = state.liveReady,
                onClick = onStartLive,
            )
        }
    }
}
