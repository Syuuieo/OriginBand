// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.integration

import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.originband.link.data.BandCatalog
import com.originband.link.data.BandStateRepository
import com.originband.link.data.BindingStore
import com.originband.link.data.HealthRecord
import com.originband.link.data.HealthRecordStore
import com.originband.link.data.LocalPrefs
import com.originband.link.data.SessionLog
import com.originband.link.data.SleepPlan
import com.originband.link.data.StepAggregateAuditStore
import com.originband.link.data.StepAggregateReview
import com.originband.link.host.HostIdentity
import com.originband.link.service.BandLiveService
import com.originband.link.service.OwnershipController
import com.originband.link.ui.CardDivider
import com.originband.link.ui.ConfirmDialog
import com.originband.link.ui.Copy
import com.originband.link.ui.NavRow
import com.originband.link.ui.OriginBandScaffold
import com.originband.link.ui.SecondaryButton
import com.originband.link.ui.SectionCard
import com.originband.link.ui.SectionHeading
import com.originband.link.ui.StatusBadge
import com.originband.link.ui.Tone
import com.originband.link.ui.setOriginBandContent
import org.json.JSONObject
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Registered-device status and account-isolated saved history, including after removal. */
class BandDetailsActivity : ComponentActivity() {
    @Immutable
    data class StepReviewUi(
        val review: StepAggregateReview,
        val dateText: String,
        val detailText: String,
        val approved: Boolean,
    )

    @Immutable
    data class DetailsUiState(
        val deviceName: String = "小米手环",
        val statusText: String = "正在读取",
        val statusTone: Tone = Tone.NEUTRAL,
        val batteryText: String = "电量待读取",
        val updatedText: String = "",
        val historySummary: String = "",
        val sleepFragmentsText: String = "",
        val stepReviews: List<StepReviewUi> = emptyList(),
        val approvingStepAudit: Boolean = false,
        val kindIndex: Int = 0,
        val historyText: String = "正在读取已保存历史…",
        val hasMoreHistory: Boolean = false,
        val historyLoading: Boolean = false,
        val pendingConfirmReview: StepAggregateReview? = null,
    )

    companion object {
        private const val KERNELSU = "me.weishu.kernelsu"
        private val KINDS = listOf(
            "sleep_interval",
            "sleep_stage",
            "heart_rate",
            "spo2",
            "stress",
            "steps_day",
            "steps_interval",
        )
        private val KIND_LABELS = listOf("睡眠区间", "睡眠分期", "心率", "血氧", "压力", "每日步数", "步数区间")

        private fun stepDate(date: Int): String {
            return String.format(Locale.ROOT, "%04d-%02d-%02d", date / 10_000, date / 100 % 100, date % 100)
        }

        private fun appendRecord(text: StringBuilder, record: HealthRecord) {
            val zone = if (record.timezone == null) ZoneId.systemDefault() else ZoneId.of(record.timezone)
            val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
            text.append(date.format(Instant.ofEpochMilli(record.startMs)))
            if (record.kind.startsWith("sleep_")) {
                text.append("–").append(date.format(Instant.ofEpochMilli(record.endMs)))
                text.append("\n区间 ").append((record.endMs - record.startMs) / 60_000).append(" 分钟")
                if (record.stage != null) {
                    text.append(" · ").append(Copy.stageName(record.stage))
                } else {
                    text.append(if (record.complete) " · 完整区间" else " · 部分区间")
                    val total = record.value
                    text.append(
                        if (total == null) "\n总睡眠时长待同步"
                        else "\n已知总睡眠 " + total.toLong() / 60_000 + " 分钟"
                    )
                }
            } else {
                text.append("\n").append(record.value)
                text.append(
                    when (record.kind) {
                        "heart_rate" -> " 次/分"
                        "spo2" -> "%"
                        "stress" -> " 压力值"
                        else -> " 步"
                    }
                )
                if ("manual" == record.measurementMode) text.append(" · 手动测量")
                if ("sleep" == record.measurementMode) text.append(" · 睡眠期间")
                if ("resting" == record.measurementMode) text.append(" · 当日静息")
                if ("walking" == record.measurementMode) text.append(" · 当日步行平均")
            }
            if (record.timezone != null) text.append("\n").append(record.timezone)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor()

    private var uiState by mutableStateOf(DetailsUiState())
    private var initialized = false
    private var approvingStepAudit = false
    private var reviewGeneration = 0
    private var historyGeneration = 0
    private var sleepFragmentGeneration = 0
    private var kindIndex = 0
    private var after: String? = null

    private val stepReviewObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = loadStepReviews()
    }
    private val historyObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            loadHistory(true)
            loadSleepFragments()
        }
    }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedId = intent.getStringExtra("device_id")
        if (requestedId != null && requestedId != LocalPrefs.open(this, "band-state").getString("deviceId", "")) {
            finish()
            return
        }
        val requested = intent?.getIntExtra("historyKind", 0) ?: 0
        kindIndex = if (requested in KINDS.indices) requested else 0
        uiState = uiState.copy(kindIndex = kindIndex)
        initialized = true
        setOriginBandContent {
            BandDetailsScreen(
                state = uiState,
                kindLabels = KIND_LABELS,
                onBack = { finish() },
                onOpenHealth = { openPackage(HostIdentity.HEALTH_PACKAGE) },
                onOpenKernelSu = { openPackage(KERNELSU) },
                onSelectKind = { index ->
                    if (index != kindIndex) {
                        kindIndex = index
                        uiState = uiState.copy(kindIndex = index)
                        loadHistory(true)
                    }
                },
                onLoadMore = { loadHistory(false) },
                onReviewStep = { review ->
                    if (!approvingStepAudit) {
                        uiState = uiState.copy(pendingConfirmReview = review)
                    }
                },
                onConfirmStepReview = { review ->
                    uiState = uiState.copy(pendingConfirmReview = null)
                    approveStepReview(review)
                },
                onDismissStepReview = { uiState = uiState.copy(pendingConfirmReview = null) },
            )
        }
        contentResolver.registerContentObserver(DeviceCardProvider.URI, false, observer)
        contentResolver.registerContentObserver(HealthQueueProvider.RECORDS_URI, true, historyObserver)
        contentResolver.registerContentObserver(HealthQueueProvider.STEP_REVIEWS_URI, false, stepReviewObserver)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!initialized) return
        refresh()
        reader.execute {
            BandStateRepository.refreshStoredSteps(this)
            main.post { if (!isFinishing && !isDestroyed) refresh() }
        }
        loadHistory(true)
        loadSleepFragments()
        loadStepReviews()
        Thread(::ensureLink, "OriginBandDetailsStart").apply {
            isDaemon = true
            start()
        }
    }

    override fun onDestroy() {
        if (initialized) {
            contentResolver.unregisterContentObserver(observer)
            contentResolver.unregisterContentObserver(historyObserver)
            contentResolver.unregisterContentObserver(stepReviewObserver)
        }
        reviewGeneration++
        historyGeneration++
        sleepFragmentGeneration++
        reader.shutdownNow()
        super.onDestroy()
    }

    private fun ensureLink() {
        try {
            if (BandStateRepository(this).isRegistered() && OwnershipController(this).nativeReady()) {
                BandLiveService.start(this)
            }
        } catch (_: RuntimeException) {
        }
    }

    private fun refresh() {
        if (!initialized || reader.isShutdown || isFinishing || isDestroyed) return
        reader.execute {
            val state = LocalPrefs.open(this, "band-state")
            val id = state.getString("deviceId", "")
            val deviceName = BandCatalog.displayName(state.getString("modelId", ""), state.getString("name", ""))
            if (id.isNullOrBlank()) {
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    uiState = uiState.copy(
                        deviceName = deviceName,
                        statusText = "待添加",
                        statusTone = Tone.NEUTRAL,
                        batteryText = "添加手环后显示电量和同步信息。",
                        updatedText = "",
                        historySummary = "",
                        sleepFragmentsText = "",
                    )
                }
                return@execute
            }
            val connected = state.getBoolean("connected", false)
            val level = state.getInt("battery", -1)
            val batteryText = if (level in 0..100) {
                "电量 $level%" + if (state.getBoolean("charging", false)) " · 充电中" else ""
            } else {
                "电量待读取"
            }
            val lastRead = state.getLong("lastUpdateMs", 0)
            val updatedText = if (lastRead > 0 && lastRead <= System.currentTimeMillis()) {
                "读取于 " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastRead))
            } else {
                "连上后显示读取时间"
            }
            val sync = state.getLong("lastSyncAtMs", 0)
            val steps = state.getLong("stepsToday", -1)
            val heart = state.getInt("heartRate", -1)
            val historySummary = (if (steps >= 0) "今日 $steps 步" else "今日步数等待同步") +
                (if (heart in 1..250) " · 最近心率 $heart 次/分" else "") +
                (if (sync > 0) "\n同步于 " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(sync)) else "")
            main.post {
                if (isFinishing || isDestroyed) return@post
                uiState = uiState.copy(
                    deviceName = deviceName,
                    statusText = if (connected) "已连接" else "等待连接",
                    statusTone = if (connected) Tone.POSITIVE else Tone.NEUTRAL,
                    batteryText = batteryText,
                    updatedText = updatedText,
                    historySummary = historySummary,
                )
            }
        }
    }

    private fun loadSleepFragments() {
        if (!initialized || reader.isShutdown || isFinishing || isDestroyed) return
        val generation = ++sleepFragmentGeneration
        reader.execute {
            var nights: List<SleepPlan.Night> = emptyList()
            try {
                HealthRecordStore(this).use { records ->
                    val device = LocalPrefs.open(this, "band-state").getString("deviceId", "")
                    if (!device.isNullOrBlank() && BandStateRepository(this).isRegistered()) {
                        val end = System.currentTimeMillis() + 86_400_000L
                        val start = end - 4L * 86_400_000L
                        val intervals = records.storedRecords(device, "sleep_interval", start, end)
                        nights = SleepPlan.nights(intervals, ZoneId.systemDefault())
                    }
                }
            } catch (unavailable: Exception) {
                SessionLog.line(this, "SLEEP_FRAGMENTS unavailable=" + unavailable.javaClass.simpleName)
            }
            val loaded = nights
            main.post {
                if (generation != sleepFragmentGeneration || isFinishing || isDestroyed) return@post
                val latest = loaded.maxByOrNull { it.wakeMs() }
                uiState = uiState.copy(
                    sleepFragmentsText = when {
                        latest == null -> ""
                        latest.snapshots() <= 1 -> "本夜同步稳定，检测到 0 次打断"
                        else -> "本夜被同步打断 ${latest.snapshots()} 次（手环固件行为）"
                    }
                )
            }
        }
    }

    private fun loadStepReviews() {
        if (!initialized || reader.isShutdown || isFinishing || isDestroyed) return
        val generation = ++reviewGeneration
        reader.execute {
            var entries: List<StepAggregateAuditStore.Entry> = emptyList()
            try {
                HealthRecordStore(this).use { records ->
                    val account = records.confirmedAccountHash()
                    val device = LocalPrefs.open(this, "band-state").getString("deviceId", "")
                    if (account != null && BandStateRepository(this).isRegistered()) {
                        entries = StepAggregateAuditStore(this)
                            .entries(account, device)
                            .filter { it.review() != null }
                    }
                }
            } catch (_: Exception) {
            }
            val items = entries.map { entry ->
                val review = entry.review()!!
                val date = stepDate(review.date())
                StepReviewUi(
                    review = review,
                    dateText = date,
                    detailText = "$date · 分钟和设备记录 ${review.expectedSteps()} 步\n" +
                        "健康合并 ${review.combinedSteps()} 步 · 手机汇总 ${review.phoneSteps()} 步",
                    approved = entry.approved(),
                )
            }
            main.post {
                if (generation != reviewGeneration || isFinishing || isDestroyed) return@post
                uiState = uiState.copy(
                    stepReviews = items,
                    approvingStepAudit = approvingStepAudit,
                )
            }
        }
    }

    private fun approveStepReview(review: StepAggregateReview) {
        if (approvingStepAudit || reader.isShutdown) return
        approvingStepAudit = true
        uiState = uiState.copy(approvingStepAudit = true)
        loadStepReviews()
        reader.execute {
            val message = try {
                val extras = Bundle().apply { putString("fingerprint", review.fingerprint()) }
                val result = contentResolver.call(HealthQueueProvider.URI, "approveStepAudit", null, extras)
                if (result == null || "STEP_AUDIT_APPROVED" != result.getString("status")) {
                    throw IllegalStateException("STEP_AUDIT_APPROVAL_PENDING")
                }
                "已确认，健康完成校正后会刷新核对记录。重新打开健康可查看日汇总。"
            } catch (_: Exception) {
                "核对记录已更新，查看最新记录后再试。"
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                approvingStepAudit = false
                uiState = uiState.copy(approvingStepAudit = false)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                loadStepReviews()
            }
        }
    }

    private fun loadHistory(reset: Boolean) {
        if (!initialized || reader.isShutdown || isFinishing || isDestroyed) return
        if (reset) after = null
        val cursor = after
        val kind = KINDS[kindIndex]
        val generation = ++historyGeneration
        uiState = uiState.copy(
            historyLoading = true,
            hasMoreHistory = false,
            historyText = "正在读取已保存历史…",
        )
        reader.execute {
            var message: String
            var nextCursor = cursor
            var more = false
            try {
                HealthRecordStore(this).use { store ->
                    val binding = BindingStore(this).read() ?: throw IllegalStateException("BINDING_REQUIRED")
                    val device = BandStateRepository.deviceId(binding)
                    val account = store.confirmedAccountHash()
                    if (account == null) {
                        message = "健康记录写入当前 vivo 健康账号后会显示在这里。"
                    } else {
                        val page = ArrayList<HealthRecord>()
                        store.localMeasurements(device, kind, 0, Long.MAX_VALUE, cursor).use { rows ->
                            while (rows.moveToNext()) {
                                val record = HealthRecord.fromJson(JSONObject(rows.getString(2)))
                                if (device != record.deviceId || kind != record.kind) {
                                    throw IllegalStateException("HISTORY_IDENTITY_MISMATCH")
                                }
                                page.add(record)
                                nextCursor = rows.getString(0)
                            }
                        }
                        if (account != store.confirmedAccountHash()) {
                            throw IllegalStateException("ACCOUNT_CHANGED")
                        }
                        more = page.size == HealthRecordStore.PAGE_SIZE
                        val text = StringBuilder()
                        for (record in page) {
                            if (text.isNotEmpty()) text.append("\n\n")
                            appendRecord(text, record)
                        }
                        message = if (page.isEmpty()) {
                            if (cursor == null) "这一类还在等待记录，手环同步后会出现在这里。" else "已显示全部记录。"
                        } else {
                            text.toString()
                        }
                    }
                }
            } catch (_: Exception) {
                message = "解锁手机并导入绑定后即可查看历史。"
            }
            val result = message
            val resolvedCursor = nextCursor
            val hasMore = more
            main.post {
                if (generation != historyGeneration || isFinishing || isDestroyed) return@post
                after = resolvedCursor
                uiState = uiState.copy(
                    historyLoading = false,
                    historyText = result,
                    hasMoreHistory = hasMore,
                )
            }
        }
    }

    private fun openPackage(target: String) {
        val launch = packageManager.getLaunchIntentForPackage(target)
        if (launch == null) {
            Toast.makeText(this, "安装对应应用后即可打开", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(launch)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "从手机桌面打开对应应用即可", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun BandDetailsScreen(
    state: BandDetailsActivity.DetailsUiState,
    kindLabels: List<String>,
    onBack: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenKernelSu: () -> Unit,
    onSelectKind: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onReviewStep: (StepAggregateReview) -> Unit,
    onConfirmStepReview: (StepAggregateReview) -> Unit,
    onDismissStepReview: () -> Unit,
) {
    OriginBandScaffold(title = "设备详情", onBack = onBack) {
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
            Text(
                text = state.batteryText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.updatedText.isNotBlank()) {
                Text(
                    text = state.updatedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.historySummary.isNotBlank()) {
                Text(
                    text = state.historySummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.sleepFragmentsText.isNotBlank()) {
                Text(
                    text = state.sleepFragmentsText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard {
            NavRow(title = "打开 vivo 健康", onClick = onOpenHealth)
            CardDivider()
            NavRow(title = "打开 KernelSU", onClick = onOpenKernelSu)
        }

        if (state.stepReviews.isNotEmpty()) {
            SectionCard(title = "日汇总核对") {
                Text(
                    text = "分钟记录与健康日汇总存在差异。确认当天实际步数后，按日期校正汇总。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.stepReviews.forEachIndexed { idx, entry ->
                    if (idx > 0) CardDivider()
                    Text(
                        text = entry.detailText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    SecondaryButton(
                        label = if (entry.approved) "已确认，等待健康校正" else "核对并校正",
                        enabled = !entry.approved && !state.approvingStepAudit,
                        onClick = { onReviewStep(entry.review) },
                    )
                }
            }
        }

        SectionHeading(text = "已保存历史")
        Text(
            text = "当前健康账号下这只手环的记录。移除设备后记录照样保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(kindLabels) { index, label ->
                FilterChip(
                    selected = index == state.kindIndex,
                    onClick = { onSelectKind(index) },
                    label = { Text(text = label) },
                )
            }
        }

        SectionCard {
            Text(
                text = state.historyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SecondaryButton(
                label = if (state.hasMoreHistory) "加载更多" else "已显示全部记录",
                enabled = state.hasMoreHistory && !state.historyLoading,
                onClick = onLoadMore,
            )
        }
    }

    val confirmReview = state.pendingConfirmReview
    if (confirmReview != null) {
        val dateText = String.format(
            Locale.ROOT,
            "%04d-%02d-%02d",
            confirmReview.date() / 10_000,
            confirmReview.date() / 100 % 100,
            confirmReview.date() % 100,
        )
        ConfirmDialog(
            title = "校正 $dateText 的日汇总",
            message = "分钟记录和设备日总均为 ${confirmReview.expectedSteps()} 步。" +
                "本次会把该日偏大的健康合并日总和手机日总校正为这个值。" +
                "确认当天实际步数后继续；另有真实手机计步时可选择保留现状。" +
                "分钟记录、热量、距离及其他日期沿用原值。",
            confirmLabel = "确认并校正",
            dismissLabel = "保留现状",
            onConfirm = { onConfirmStepReview(confirmReview) },
            onDismiss = onDismissStepReview,
        )
    }
}
