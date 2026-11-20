// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.originband.link.host.HostIdentity
import com.originband.link.integration.WeatherSnapshotProvider
import com.originband.link.protocol.BandWeatherEncoder
import com.originband.link.service.BandLiveService
import com.originband.link.service.WeatherSync
import com.originband.link.ui.ConfirmDialog
import com.originband.link.ui.Copy
import com.originband.link.ui.OriginBandScaffold
import com.originband.link.ui.PrimaryButton
import com.originband.link.ui.QuietTextButton
import com.originband.link.ui.SecondaryButton
import com.originband.link.ui.SectionCard
import com.originband.link.ui.setOriginBandContent
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Reviews source weather and submits delivery through the shared live service. */
class WeatherActivity : ComponentActivity() {
    @Immutable
    data class CityOption(val code: String, val name: String)

    sealed interface WeatherDialog {
        data object None : WeatherDialog
        data class ChooseCity(
            val sample: BandWeatherEncoder.Sample,
            val options: List<CityOption>,
        ) : WeatherDialog

        data class ConfirmCity(
            val sample: BandWeatherEncoder.Sample,
            val option: CityOption,
        ) : WeatherDialog
    }

    @Immutable
    data class WeatherUiState(
        val status: String = "正在读取天气快照…",
        val delivery: String = "发送后，在手环上看一眼城市、温度和预报。",
        val association: String = "",
        val linked: Boolean = false,
        val readyToSend: Boolean = false,
        val running: Boolean = false,
        val dialog: WeatherDialog = WeatherDialog.None,
    )

    companion object {
        private fun storedWeather(response: Bundle?): String {
            if (response == null) return ""
            val raw = response.getString("snapshot", "")
            if (raw.isNullOrBlank()) return ""
            return try {
                val sample = BandWeatherEncoder.parse(JSONObject(raw))
                "手机已保存天气：${sample.cityName()} / ${sample.locationName()}" +
                    "\n发布时间：${time(sample.publishedAtMs())}" +
                    "\n逐日：${sample.daily().size} 天，逐时：${sample.hourly().size}" +
                    " 小时。点「获取当前位置天气」刷新后即可发送。"
            } catch (_: Exception) {
                "手机保存着一份旧天气，点「获取当前位置天气」刷新后即可发送。"
            }
        }

        private fun failureCode(error: Throwable?): String {
            var current = error ?: return ""
            while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
                current = current.cause!!
            }
            if (current is WeatherSync.Failure) return current.code
            val message = current.message
            return if (message != null && message.matches(Regex("[A-Z_]{1,80}"))) message else "WEATHER_UNAVAILABLE"
        }

        private fun time(value: Long): String = DateFormat.getDateTimeInstance().format(Date(value))

        private fun sourceText(response: Bundle): String {
            return Copy.weatherSource(response.getString("status", ""), response.getBoolean("pending", false))
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var uiState by mutableStateOf(WeatherUiState())

    @Volatile
    private var ready: BandWeatherEncoder.Sample? = null
    private var running = false

    private val updates = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshSnapshot()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshAssociationState()
        setOriginBandContent {
            WeatherScreen(
                state = uiState,
                onBack = { finish() },
                onRequestWeather = ::requestWeather,
                onOpenHealth = ::openHealth,
                onSendWeather = { submitWeather(false) },
                onInspectCities = { submitWeather(true) },
                onClearAssociation = ::clearAssociation,
                onSelectCity = { sample, option ->
                    uiState = uiState.copy(dialog = WeatherDialog.ConfirmCity(sample, option))
                },
                onConfirmCity = ::confirmCityAssociation,
                onDismissDialog = { uiState = uiState.copy(dialog = WeatherDialog.None) },
            )
        }
        contentResolver.registerContentObserver(WeatherSnapshotProvider.UPDATED, false, updates)
        refreshSnapshot()
    }

    override fun onResume() {
        super.onResume()
        refreshSnapshot()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(updates)
        worker.shutdown()
        super.onDestroy()
    }

    private fun requestWeather() {
        if (worker.isShutdown) return
        uiState = uiState.copy(status = "正在获取当前位置天气，上一份有效天气会继续保留。")
        BandLiveService.refreshWeather(this)
        worker.execute {
            try {
                contentResolver.call(WeatherSnapshotProvider.URI, "requestRefresh", null, null)
            } catch (_: RuntimeException) {
                main.post {
                    if (!isDestroyed) {
                        uiState = uiState.copy(status = "打开一次 vivo 健康，再点「获取当前位置天气」。")
                    }
                }
            }
            refreshSnapshot()
        }
    }

    private fun openHealth() {
        val launcher = packageManager.getLaunchIntentForPackage(HostIdentity.HEALTH_PACKAGE)
        if (launcher == null) {
            uiState = uiState.copy(status = "安装 vivo 健康后即可获取天气。")
            return
        }
        try {
            startActivity(launcher)
        } catch (_: RuntimeException) {
            uiState = uiState.copy(status = "从手机桌面打开 vivo 健康即可。")
        }
    }

    private fun refreshSnapshot() {
        if (worker.isShutdown) return
        worker.execute {
            var valid: BandWeatherEncoder.Sample? = null
            var message: String
            var response: Bundle? = null
            try {
                response = contentResolver.call(WeatherSnapshotProvider.URI, "getSnapshot", null, null)
                    ?: throw IllegalStateException("WEATHER_SOURCE_UNAVAILABLE")
                val source = sourceText(response)
                val fresh = WeatherSync.freshSample(response)
                valid = fresh
                message = "城市：${fresh.cityName()} / ${fresh.locationName()}" +
                    "\n来源更新时间：${time(response.getLong("updatedAtMs", 0))}" +
                    "\n天气发布时间：${time(fresh.publishedAtMs())}" +
                    "\n逐日：${fresh.daily().size} 天，逐时：${fresh.hourly().size} 小时，可以发送。" +
                    if (source.isEmpty()) "" else "\n$source；以上快照仍可发送。"
            } catch (_: Exception) {
                valid = null
                val stored = storedWeather(response)
                val source = if (response == null) sourceStatus() else sourceText(response)
                message = if (stored.isEmpty()) {
                    "点「获取当前位置天气」取一份天气。" + if (source.isEmpty()) "" else "\n$source。"
                } else {
                    stored + if (source.isEmpty()) "" else "\n$source。"
                }
            }
            val sync = getSharedPreferences("weather-sync", 0)
            val deliveryCode = sync.getString("status", "")
            val deliveryText = when {
                "WEATHER_TRANSPORT_CONFIRMED" == deliveryCode ->
                    "上次送达：${time(sync.getLong("confirmedAtMs", 0))}\n在手环上看一眼城市、温度和预报。"
                !deliveryCode.isNullOrEmpty() &&
                    "WEATHER_CITIES_READY" != deliveryCode &&
                    "WEATHER_READING_CITIES" != deliveryCode ->
                    Copy.weatherError(deliveryCode)
                else -> "发送后，在手环上看一眼城市、温度和预报。"
            }
            val captured = valid
            val result = message
            main.post {
                if (isDestroyed) return@post
                ready = captured
                uiState = uiState.copy(
                    readyToSend = captured != null,
                    status = if (running) uiState.status else result,
                    delivery = if (running) uiState.delivery else deliveryText,
                )
                refreshAssociationState()
            }
        }
    }

    private fun submitWeather(inspect: Boolean) {
        val sample = ready
        if (sample == null || running) return
        running = true
        uiState = uiState.copy(
            running = true,
            delivery = if (inspect) "正在读取手环城市…" else "正在通过现有连接发送天气…",
        )
        BandLiveService.requestWeather(this, sample, inspect).whenComplete { _, error ->
            main.post {
                running = false
                if (isDestroyed) return@post
                val code = failureCode(error)
                val nextDelivery = if (error == null) {
                    if (inspect) reviewText(sample)
                    else "天气已送达手环。在手环上看一眼城市、温度和预报，预报以上方的发布时间为准。"
                } else {
                    Copy.weatherError(code)
                }
                uiState = uiState.copy(
                    running = false,
                    readyToSend = ready != null,
                    delivery = nextDelivery,
                )
                refreshAssociationState()
                if ((error == null && inspect) || "WEATHER_CITY_CONFIRMATION_REQUIRED" == code) {
                    showCityChoices(sample)
                }
            }
        }
    }

    private fun sourceStatus(): String {
        return try {
            val response = contentResolver.call(WeatherSnapshotProvider.URI, "getSnapshot", null, null)
            if (response == null) Copy.weatherSource("UNAVAILABLE", false) else sourceText(response)
        } catch (_: RuntimeException) {
            Copy.weatherSource("UNAVAILABLE", false)
        }
    }

    private fun showCityChoices(sample: BandWeatherEncoder.Sample) {
        val prefs = getSharedPreferences("weather-city-review", 0)
        if (sample.locationKey() != prefs.getString("sourceKey", "") ||
            sample.cityName() != prefs.getString("sourceCity", "") ||
            sample.locationName() != prefs.getString("sourcePlace", "")
        ) {
            uiState = uiState.copy(delivery = "健康侧的城市有变化，点「核对手环城市」重新关联。")
            return
        }
        val count = prefs.getInt("bandCount", 0)
        if (count == 0) return
        val options = (0 until count).map { i ->
            CityOption(
                code = prefs.getString("bandCode$i", "").orEmpty(),
                name = prefs.getString("bandName$i", "").orEmpty(),
            )
        }
        uiState = uiState.copy(dialog = WeatherDialog.ChooseCity(sample, options))
    }

    private fun confirmCityAssociation(sample: BandWeatherEncoder.Sample, option: CityOption) {
        val prefs = getSharedPreferences("weather-city-review", 0)
        val saved = prefs.edit()
            .putString("confirmedSourceKey", sample.locationKey())
            .putString("confirmedSourceCity", sample.cityName())
            .putString("confirmedSourcePlace", sample.locationName())
            .putString("confirmedBandCode", option.code)
            .putString("confirmedBandName", option.name)
            .commit()
        uiState = uiState.copy(
            dialog = WeatherDialog.None,
            delivery = if (saved) "城市关联已确认，可以发送天气。每次发送都会再核对两端城市。" else "城市关联保存中断，再确认一次即可。",
        )
        refreshAssociationState()
    }

    private fun reviewText(sample: BandWeatherEncoder.Sample): String {
        val prefs = getSharedPreferences("weather-city-review", 0)
        val text = StringBuilder("vivo 健康：${sample.cityName()} / ${sample.locationName()}\n编号：${sample.locationKey()}")
        for (i in 0 until prefs.getInt("bandCount", 0)) {
            text.append("\n手环城市：").append(prefs.getString("bandName$i", ""))
                .append("\n编号：").append(prefs.getString("bandCode$i", ""))
        }
        return text.toString()
    }

    private fun clearAssociation() {
        getSharedPreferences("weather-city-review", 0).edit()
            .remove("confirmedSourceKey")
            .remove("confirmedSourceCity")
            .remove("confirmedSourcePlace")
            .remove("confirmedBandCode")
            .remove("confirmedBandName")
            .commit()
        uiState = uiState.copy(delivery = "城市关联已解除。下次发送时会重新核对城市。")
        refreshAssociationState()
    }

    private fun refreshAssociationState() {
        val prefs = getSharedPreferences("weather-city-review", 0)
        val bandName = prefs.getString("confirmedBandName", "").orEmpty()
        val sourceCity = prefs.getString("confirmedSourceCity", "").orEmpty()
        val linked = bandName.isNotEmpty()
        uiState = uiState.copy(
            linked = linked,
            association = if (linked) {
                "已关联：vivo 健康 $sourceCity → 手环 $bandName"
            } else {
                "发送时会自动核对两端城市。手环有多个城市时，点「核对手环城市」选一个关联。"
            },
        )
    }
}

@Composable
private fun WeatherScreen(
    state: WeatherActivity.WeatherUiState,
    onBack: () -> Unit,
    onRequestWeather: () -> Unit,
    onOpenHealth: () -> Unit,
    onSendWeather: () -> Unit,
    onInspectCities: () -> Unit,
    onClearAssociation: () -> Unit,
    onSelectCity: (BandWeatherEncoder.Sample, WeatherActivity.CityOption) -> Unit,
    onConfirmCity: (BandWeatherEncoder.Sample, WeatherActivity.CityOption) -> Unit,
    onDismissDialog: () -> Unit,
) {
    OriginBandScaffold(title = "天气同步", onBack = onBack) {
        SectionCard(title = "vivo 健康天气") {
            Text(
                text = state.status,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "使用 vivo 健康天气和定位快照，天气和地点有效后即可发送到手环。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(label = "获取当前位置天气", onClick = onRequestWeather)
            QuietTextButton(label = "打开 vivo 健康", onClick = onOpenHealth)
        }

        SectionCard(title = "手环") {
            Text(
                text = state.delivery,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PrimaryButton(
                label = "发送到手环",
                enabled = state.readyToSend && !state.running,
                onClick = onSendWeather,
            )
            SecondaryButton(
                label = "核对手环城市",
                enabled = state.readyToSend && !state.running,
                onClick = onInspectCities,
            )
        }

        SectionCard(title = "城市关联") {
            Text(
                text = state.association,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.linked) {
                QuietTextButton(
                    label = "解除城市关联",
                    enabled = !state.running,
                    onClick = onClearAssociation,
                )
            }
        }
    }

    when (val dialog = state.dialog) {
        WeatherActivity.WeatherDialog.None -> Unit
        is WeatherActivity.WeatherDialog.ChooseCity -> {
            AlertDialog(
                onDismissRequest = onDismissDialog,
                title = { Text(text = "选择手环城市") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        dialog.options.forEach { option ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectCity(dialog.sample, option) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Text(
                                    text = "${option.name}\n${option.code}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismissDialog) { Text(text = "取消") }
                },
            )
        }
        is WeatherActivity.WeatherDialog.ConfirmCity -> {
            val unitSymbol = if ("f" == dialog.sample.unit()) "℉" else "℃"
            ConfirmDialog(
                title = "确认城市关联？",
                message = "vivo 健康：${dialog.sample.cityName()} / ${dialog.sample.locationName()}" +
                    "\n编号：${dialog.sample.locationKey()}" +
                    "\n手环城市：${dialog.option.name}" +
                    "\n编号：${dialog.option.code}" +
                    "\n温度：${dialog.sample.temperature()}$unitSymbol" +
                    "\n时区：${dialog.sample.timezone()}" +
                    "\n逐日：${dialog.sample.daily().size} 天，逐时：${dialog.sample.hourly().size} 小时",
                confirmLabel = "确认关联",
                onConfirm = { onConfirmCity(dialog.sample, dialog.option) },
                onDismiss = onDismissDialog,
            )
        }
    }
}
