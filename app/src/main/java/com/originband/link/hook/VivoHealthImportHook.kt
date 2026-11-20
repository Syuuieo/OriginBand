// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook

import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import com.originband.link.data.HealthRecord
import com.originband.link.hook.xposed.HookRuntime
import com.originband.link.hook.xposed.XposedHelpers
import com.originband.link.host.vivo.VivoDeviceProfile
import com.originband.link.host.vivo.VivoHealthMapper
import com.originband.link.integration.HealthQueueProvider
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Consumes queued OriginBand measurements in com.vivo.health main process and writes them
 * into vivo's SQLCipher health database, MMKV latest caches, HealthKit, and event bus.
 */
object VivoHealthImportHook {
    private const val MAX_PAGES_PER_DRAIN = 20
    private const val PROCESS_KEY_MAIN = "PROCESS_KEY_MAIN"
    private const val EVENT_ACTIVITY_SYNC = "com.vivo.health.ACTIVITY_DATA_SYNC_SUCCESS"
    private const val EVENT_HEALTH_SYNC = "com.vivo.health.HEALTH_DATA_SYNC_SUCCESS"
    private const val EVENT_POST_HEALTH_MODEL = "com.vivo.health.postHealthModel"

    private const val CLS_ACCOUNT_INIT = "com.vivo.framework.AccountInit"
    private const val CLS_DATA_ITEM = "com.vivo.framework.bean.DataItem"
    private const val CLS_DAILY_REST_RATE = "com.vivo.framework.bean.health.DailyRestRateBean"
    private const val CLS_DAILY_WALK_RATE = "com.vivo.framework.bean.health.DailyWalkRateBean"
    private const val CLS_DATE_STEP = "com.vivo.framework.bean.health.DateStepCountBean"
    private const val CLS_DATE_CALORIE = "com.vivo.framework.bean.health.DateCalorieBean"
    private const val CLS_DATE_DISTANCE = "com.vivo.framework.bean.health.DateDistanceBean"
    private const val CLS_ACT_STEP = "com.vivo.framework.bean.SportTodayActivity_Step"
    private const val CLS_ACT_CALORIE = "com.vivo.framework.bean.SportTodayActivity_CALORIE"
    private const val CLS_ACT_DISTANCE = "com.vivo.framework.bean.SportTodayActivity_DISTANCE"
    private const val CLS_ACT_STAND = "com.vivo.framework.bean.SportTodayActivity_STAND"
    private const val CLS_WATCH_SLEEP = "com.vivo.framework.bean.WatchSleepBean"
    private const val CLS_TIME_REGIN = "com.vivo.framework.bean.TimeRegin"
    private const val CLS_SPORT_RECORD = "com.vivo.framework.bean.sport.SportRecordByWatchBean"
    private const val CLS_WATCH_GPS = "com.vivo.framework.bean.WatchSportGPSBean"
    private const val CLS_HEALTH_DB = "com.vivo.framework.health.HealthDBHelper"
    private const val CLS_OXYGEN_DB = "com.vivo.framework.health.HealthDbOxygenHelper"
    private const val CLS_TODAY_ACT_DB = "com.vivo.framework.sport.SportTodayActivityDBHelper"
    private const val CLS_WATCH_SPORT_DB = "com.vivo.framework.sport.helper.WatchSportRecordDBHelper"
    private const val CLS_SP_UTIL = "com.vivo.framework.utils.SPUtil"
    private const val CLS_BUSINESS_MGR = "com.vivo.health.lib.router.BusinessManager"
    private const val CLS_HEALTHKIT_SYNC = "com.vivo.framework.healthkit.IHealthKitSync"
    private const val CLS_MODULE_PHYSICAL = "com.vivo.health.lib.router.physical.IModulePhysical"
    private const val CLS_ROUTER_HEALTH_POINT = "com.vivo.health.lib.router.physical.HealthPoint"
    private const val CLS_PROCESS_EVENT_MGR =
        "com.vivo.framework.devices.process.basic.event.ProcessEventManager"
    private const val CLS_PARAM_OBJECT = "com.vivo.callee.ParamObject"

    @JvmStatic
    fun install(context: Context, loader: ClassLoader?) {
        val worker = HookRuntime.worker("OriginBandVivoImport", 0)
        val drainScheduled = AtomicBoolean()
        val lastAdoptedAccount = AtomicReference("")

        val drainTask = Runnable {
            drainScheduled.set(false)
            drainQueue(context, loader, lastAdoptedAccount)
        }
        val scheduleDrain = Runnable {
            if (drainScheduled.compareAndSet(false, true)) {
                worker.execute(drainTask)
            }
        }

        HookRuntime.observe(
            context.contentResolver,
            HealthQueueProvider.URI,
            false,
            object : ContentObserver(HookRuntime.main()) {
                override fun onChange(selfChange: Boolean) {
                    scheduleDrain.run()
                }
            },
        )

        HookRuntime.main().postDelayed(scheduleDrain, 2500L)
        HookRuntime.log("OriginBand: VIVO_IMPORT_HOOK_INSTALLED")
    }

    private fun drainQueue(
        context: Context,
        loader: ClassLoader?,
        lastAdoptedAccount: AtomicReference<String>,
    ) {
        val openId = readOpenId(loader)
        val queueAccount = if (openId.isBlank()) "vivo_local" else "vivo:$openId"
        if (queueAccount != lastAdoptedAccount.get()) {
            val adopt = Bundle()
            adopt.putString("account", queueAccount)
            try {
                context.contentResolver.call(HealthQueueProvider.URI, "adoptAccount", null, adopt)
                lastAdoptedAccount.set(queueAccount)
            } catch (failure: Throwable) {
                HookRuntime.log("VIVO_ADOPT_ACCOUNT_FAILED", failure)
                return
            }
        }

        val snapshot = VivoHealthDeviceHook.readSnapshot(context)
        var wroteActivity = false
        var wroteHealth = false
        var importedCount = 0

        for (page in 0 until MAX_PAGES_PER_DRAIN) {
            val batchRecords = readPendingPage(context, queueAccount)
            if (batchRecords.isEmpty()) break

            val batch = VivoHealthMapper.mapBatch(batchRecords)
            for (r in batch.unsupported) {
                releaseUnsupported(context, queueAccount, r)
            }

            val toAck = ArrayList<VivoHealthMapper.Receipt>()

            if (writeHeartRates(loader, openId, batch.heartRates)) {
                toAck.addAll(batch.receiptsFor(VivoHealthMapper.Category.HEART_RATE))
                if (batch.heartRates.isNotEmpty()) wroteHealth = true
            }
            if (writeDailyRestRates(loader, batch.restingRates)) {
                toAck.addAll(batch.receiptsFor(VivoHealthMapper.Category.RESTING_HR))
                if (batch.restingRates.isNotEmpty()) wroteHealth = true
            }
            if (writeDailyWalkRates(loader, batch.walkingRates)) {
                toAck.addAll(batch.receiptsFor(VivoHealthMapper.Category.WALKING_HR))
                if (batch.walkingRates.isNotEmpty()) wroteHealth = true
            }
            if (writeOxygen(loader, openId, batch.oxygens)) {
                toAck.addAll(batch.receiptsFor(VivoHealthMapper.Category.SPO2))
                if (batch.oxygens.isNotEmpty()) wroteHealth = true
            }
            if (writeStress(loader, openId, batch.stresses)) {
                toAck.addAll(batch.receiptsFor(VivoHealthMapper.Category.STRESS))
                if (batch.stresses.isNotEmpty()) wroteHealth = true
            }
            if (writeStepsIntervals(loader, snapshot, batch.stepSlices, batch.calorieSlices, batch.distanceSlices)) {
                toAck.addAll(batch.receiptsFor(VivoHealthMapper.Category.STEPS_INTERVAL))
                if (batch.stepSlices.isNotEmpty()) wroteActivity = true
            }
            if (writeDailyTotalsAndStands(loader, snapshot, batch.dailyTotals, batch.standSlices)) {
                toAck.addAll(batch.receiptsFor(VivoHealthMapper.Category.STEPS_DAY))
                if (batch.dailyTotals.isNotEmpty() || batch.standSlices.isNotEmpty()) wroteActivity = true
            }
            if (writeSleepNights(loader, openId, snapshot, batch.sleepNights)) {
                toAck.addAll(batch.receiptsFor(VivoHealthMapper.Category.SLEEP))
                if (batch.sleepNights.isNotEmpty()) wroteHealth = true
            }
            if (writeSportSessionsAndTracks(loader, snapshot, batch.sportSessions, batch.gpsPoints)) {
                toAck.addAll(batch.receiptsFor(VivoHealthMapper.Category.SPORT_SESSION))
                toAck.addAll(batch.receiptsFor(VivoHealthMapper.Category.SPORT_TRACK))
            }
            toAck.addAll(batch.receiptsFor(VivoHealthMapper.Category.SLEEP_BREATH))

            for (r in toAck) {
                ackReceipt(context, queueAccount, r)
            }
            importedCount += toAck.size

            if (toAck.isEmpty() && batch.unsupported.isEmpty()) {
                // Stop looping if remaining records could not be imported yet.
                break
            }
        }

        if (wroteActivity || wroteHealth || importedCount > 0) {
            emitRefreshEvents(loader, wroteActivity, wroteHealth)
        }
    }

    private fun readPendingPage(context: Context, queueAccount: String): List<HealthRecord> {
        val records = ArrayList<HealthRecord>()
        try {
            context.contentResolver.query(
                HealthQueueProvider.URI,
                null,
                "account=?",
                arrayOf(queueAccount),
                null,
            )?.use { cursor ->
                val colRecord = cursor.getColumnIndex("record")
                if (colRecord < 0) return records
                while (cursor.moveToNext()) {
                    val payload = cursor.getString(colRecord)
                    if (payload.isNullOrBlank()) continue
                    try {
                        records.add(HealthRecord.fromJson(JSONObject(payload)))
                    } catch (malformed: Throwable) {
                        HookRuntime.log("VIVO_QUEUE_RECORD_PARSE_FAILED", malformed)
                    }
                }
            }
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_QUEUE_QUERY_FAILED", failure)
        }
        return records
    }

    private fun writeHeartRates(
        loader: ClassLoader?,
        openId: String,
        points: List<VivoHealthMapper.DataPoint>,
    ): Boolean {
        if (points.isEmpty()) return true
        return try {
            val itemCls = XposedHelpers.findClass(CLS_DATA_ITEM, loader)
            val healthDbCls = XposedHelpers.findClass(CLS_HEALTH_DB, loader)
            val items = ArrayList<Any>(points.size)
            var latest: VivoHealthMapper.DataPoint? = null
            for (p in points) {
                val item = XposedHelpers.newInstance(itemCls)
                XposedHelpers.setLongField(item, "timestampS", p.timestampS)
                XposedHelpers.setIntField(item, "value", p.value)
                items.add(item)
                if (latest == null || p.timestampMs >= latest.timestampMs) latest = p
            }
            XposedHelpers.callStaticMethod(healthDbCls, "insertHeartRate2Db", items)
            if (latest != null && openId.isNotEmpty()) {
                spPut(loader, "HEALTH_HEART_VALUE_$openId", latest.value)
                spPut(loader, "HEALTH_HEART_TIME_$openId", latest.timestampMs)
            }
            true
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_WRITE_HR_FAILED", failure)
            false
        }
    }

    private fun writeDailyRestRates(
        loader: ClassLoader?,
        points: List<VivoHealthMapper.DailyHeartRate>,
    ): Boolean {
        if (points.isEmpty()) return true
        return try {
            val beanCls = XposedHelpers.findClass(CLS_DAILY_REST_RATE, loader)
            val healthDbCls = XposedHelpers.findClass(CLS_HEALTH_DB, loader)
            val beans = ArrayList<Any>(points.size)
            for (p in points) {
                beans.add(
                    XposedHelpers.newInstance(
                        beanCls,
                        p.dayStartMs,
                        p.sampleMs,
                        p.bpm,
                        false,
                        "VIVO_WATCH",
                    ),
                )
            }
            XposedHelpers.callStaticMethod(healthDbCls, "insertDailyRestRate", beans)
            true
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_WRITE_REST_HR_FAILED", failure)
            false
        }
    }

    private fun writeDailyWalkRates(
        loader: ClassLoader?,
        points: List<VivoHealthMapper.DailyHeartRate>,
    ): Boolean {
        if (points.isEmpty()) return true
        return try {
            val beanCls = XposedHelpers.findClass(CLS_DAILY_WALK_RATE, loader)
            val healthDbCls = XposedHelpers.findClass(CLS_HEALTH_DB, loader)
            val beans = ArrayList<Any>(points.size)
            for (p in points) {
                beans.add(
                    XposedHelpers.newInstance(
                        beanCls,
                        p.dayStartMs,
                        p.sampleMs,
                        p.bpm,
                        false,
                        "VIVO_WATCH",
                    ),
                )
            }
            XposedHelpers.callStaticMethod(healthDbCls, "insertDailyWalkRate", beans)
            true
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_WRITE_WALK_HR_FAILED", failure)
            false
        }
    }

    private fun writeOxygen(
        loader: ClassLoader?,
        openId: String,
        points: List<VivoHealthMapper.DataPoint>,
    ): Boolean {
        if (points.isEmpty()) return true
        return try {
            val itemCls = XposedHelpers.findClass(CLS_DATA_ITEM, loader)
            val oxyCls = XposedHelpers.findClass(CLS_OXYGEN_DB, loader)
            val oxyHelper = checkNotNull(XposedHelpers.getStaticObjectField(oxyCls, "a"))
            val items = ArrayList<Any>(points.size)
            var latest: VivoHealthMapper.DataPoint? = null
            for (p in points) {
                val item = XposedHelpers.newInstance(itemCls)
                XposedHelpers.setLongField(item, "timestampS", p.timestampS)
                XposedHelpers.setIntField(item, "value", p.value)
                items.add(item)
                if (latest == null || p.timestampMs >= latest.timestampMs) latest = p
            }
            XposedHelpers.callMethod(oxyHelper, "k", items, false)
            if (latest != null && openId.isNotEmpty()) {
                spPut(loader, "HEALTH_OXYGEN_VALUE_$openId", latest.value)
                spPut(loader, "HEALTH_OXYGEN_TIME_$openId", latest.timestampMs)
            }
            true
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_WRITE_SPO2_FAILED", failure)
            false
        }
    }

    private fun writeStress(
        loader: ClassLoader?,
        openId: String,
        points: List<VivoHealthMapper.DataPoint>,
    ): Boolean {
        if (points.isEmpty()) return true
        return try {
            val itemCls = XposedHelpers.findClass(CLS_DATA_ITEM, loader)
            val healthDbCls = XposedHelpers.findClass(CLS_HEALTH_DB, loader)
            val items = ArrayList<Any>(points.size)
            var latest: VivoHealthMapper.DataPoint? = null
            for (p in points) {
                val item = XposedHelpers.newInstance(itemCls)
                XposedHelpers.setLongField(item, "timestampS", p.timestampS)
                XposedHelpers.setIntField(item, "value", p.value)
                items.add(item)
                if (latest == null || p.timestampMs >= latest.timestampMs) latest = p
            }
            XposedHelpers.callStaticMethod(healthDbCls, "insertPress2Db", items)
            if (latest != null) {
                if (openId.isNotEmpty()) {
                    spPut(loader, "HEALTH_PRESSURE_VALUE_$openId", latest.value)
                    spPut(loader, "HEALTH_PRESSURE_TIME_$openId", latest.timestampMs)
                }
                syncStressHealthKit(loader, latest.value, latest.timestampMs)
            }
            true
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_WRITE_STRESS_FAILED", failure)
            false
        }
    }

    private fun writeStepsIntervals(
        loader: ClassLoader?,
        snapshot: VivoDeviceProfile.Snapshot,
        steps: List<VivoHealthMapper.TodayStepSlice>,
        calories: List<VivoHealthMapper.TodayCalorieSlice>,
        distances: List<VivoHealthMapper.TodayDistanceSlice>,
    ): Boolean {
        if (steps.isEmpty() && calories.isEmpty() && distances.isEmpty()) return true
        return try {
            val dbCls = XposedHelpers.findClass(CLS_TODAY_ACT_DB, loader)
            val devId = nonEmptyDeviceId(snapshot)
            val devName = snapshot.displayName()
            val devModel = snapshot.modelId.ifBlank { "Xiaomi Band" }

            if (steps.isNotEmpty()) {
                val stepCls = XposedHelpers.findClass(CLS_ACT_STEP, loader)
                val list = ArrayList<Any>(steps.size)
                for (s in steps) {
                    val bean = XposedHelpers.newInstance(
                        stepCls,
                        null,
                        s.uuid,
                        s.startMs,
                        s.endMs,
                        s.steps,
                        devId,
                        devName,
                        devModel,
                        "Xiaomi",
                        VivoDeviceProfile.DEVICE_TYPE_WATCH,
                        false,
                    )
                    XposedHelpers.setObjectField(bean, "sourceFrom", "VIVO_WATCH")
                    list.add(bean)
                }
                XposedHelpers.callStaticMethod(dbCls, "insertOrUpdate", list)
            }
            if (calories.isNotEmpty()) {
                val calCls = XposedHelpers.findClass(CLS_ACT_CALORIE, loader)
                val list = ArrayList<Any>(calories.size)
                for (c in calories) {
                    val bean = XposedHelpers.newInstance(
                        calCls,
                        null,
                        c.uuid,
                        c.startMs,
                        c.endMs,
                        c.smallCalories,
                        devId,
                        devName,
                        devModel,
                        "Xiaomi",
                        VivoDeviceProfile.DEVICE_TYPE_WATCH,
                        false,
                    )
                    XposedHelpers.setObjectField(bean, "sourceFrom", "VIVO_WATCH")
                    list.add(bean)
                }
                XposedHelpers.callStaticMethod(dbCls, "insertOrUpdate_calorie", list)
            }
            if (distances.isNotEmpty()) {
                val distCls = XposedHelpers.findClass(CLS_ACT_DISTANCE, loader)
                val list = ArrayList<Any>(distances.size)
                for (d in distances) {
                    val bean = XposedHelpers.newInstance(
                        distCls,
                        null,
                        d.uuid,
                        d.startMs,
                        d.endMs,
                        d.meters,
                        devId,
                        devName,
                        devModel,
                        "Xiaomi",
                        VivoDeviceProfile.DEVICE_TYPE_WATCH,
                        false,
                    )
                    XposedHelpers.setObjectField(bean, "sourceFrom", "VIVO_WATCH")
                    list.add(bean)
                }
                XposedHelpers.callStaticMethod(dbCls, "insertOrUpdate_distanceSport", list)
            }
            true
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_WRITE_ACTIVITY_SLICE_FAILED", failure)
            false
        }
    }

    private fun writeDailyTotalsAndStands(
        loader: ClassLoader?,
        snapshot: VivoDeviceProfile.Snapshot,
        totals: List<VivoHealthMapper.DailyTotals>,
        stands: List<VivoHealthMapper.TodayStandSlice>,
    ): Boolean {
        if (totals.isEmpty() && stands.isEmpty()) return true
        return try {
            val healthDbCls = XposedHelpers.findClass(CLS_HEALTH_DB, loader)
            if (totals.isNotEmpty()) {
                val stepCls = XposedHelpers.findClass(CLS_DATE_STEP, loader)
                val calCls = XposedHelpers.findClass(CLS_DATE_CALORIE, loader)
                val distCls = XposedHelpers.findClass(CLS_DATE_DISTANCE, loader)
                val stepBeans = ArrayList<Any>(totals.size)
                val calBeans = ArrayList<Any>()
                val distBeans = ArrayList<Any>()
                for (t in totals) {
                    stepBeans.add(XposedHelpers.newInstance(stepCls, t.dayStartMs, t.steps))
                    t.caloriesKcal?.let { calBeans.add(XposedHelpers.newInstance(calCls, t.dayStartMs, it)) }
                    t.distanceMeters?.let { distBeans.add(XposedHelpers.newInstance(distCls, t.dayStartMs, it)) }
                }
                XposedHelpers.callStaticMethod(healthDbCls, "insertDateStepCount", stepBeans)
                if (calBeans.isNotEmpty()) XposedHelpers.callStaticMethod(healthDbCls, "insertDateCalorie", calBeans)
                if (distBeans.isNotEmpty()) XposedHelpers.callStaticMethod(healthDbCls, "insertDateDistance", distBeans)
            }
            if (stands.isNotEmpty()) {
                val dbCls = XposedHelpers.findClass(CLS_TODAY_ACT_DB, loader)
                val standCls = XposedHelpers.findClass(CLS_ACT_STAND, loader)
                val devId = nonEmptyDeviceId(snapshot)
                val devName = snapshot.displayName()
                val devModel = snapshot.modelId.ifBlank { "Xiaomi Band" }
                val list = ArrayList<Any>(stands.size)
                for (st in stands) {
                    list.add(
                        XposedHelpers.newInstance(
                            standCls,
                            null,
                            st.uuid,
                            st.hourStartMs,
                            st.hourEndMs,
                            st.value,
                            devId,
                            devName,
                            devModel,
                            "Xiaomi",
                            VivoDeviceProfile.DEVICE_TYPE_WATCH,
                            false,
                            "VIVO_WATCH",
                        ),
                    )
                }
                XposedHelpers.callStaticMethod(dbCls, "insertOrUpdate_stand", list)
            }
            true
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_WRITE_DAILY_TOTALS_FAILED", failure)
            false
        }
    }

    private fun writeSleepNights(
        loader: ClassLoader?,
        openId: String,
        snapshot: VivoDeviceProfile.Snapshot,
        nights: List<VivoHealthMapper.SleepNight>,
    ): Boolean {
        if (nights.isEmpty()) return true
        return try {
            val sleepCls = XposedHelpers.findClass(CLS_WATCH_SLEEP, loader)
            val reginCls = XposedHelpers.findClass(CLS_TIME_REGIN, loader)
            val healthDbCls = XposedHelpers.findClass(CLS_HEALTH_DB, loader)
            val beans = ArrayList<Any>(nights.size)
            var latest: VivoHealthMapper.SleepNight? = null
            for (n in nights) {
                val bean = XposedHelpers.newInstance(sleepCls)
                XposedHelpers.callMethod(bean, "setUuid", n.uuid)
                XposedHelpers.callMethod(bean, "setDeviceId", nonEmptyDeviceId(snapshot))
                XposedHelpers.callMethod(bean, "setProductId", snapshot.productId())
                XposedHelpers.callMethod(bean, "setWatchModel", snapshot.modelId)
                XposedHelpers.callMethod(bean, "setWatchOTAVersion", snapshot.firmwareVersion())
                XposedHelpers.callMethod(bean, "setZoneOffset", n.zoneOffsetMin)
                XposedHelpers.callMethod(bean, "setEnterTime", n.enterTimeMs)
                XposedHelpers.callMethod(bean, "setExitTime", n.exitTimeMs)
                XposedHelpers.callMethod(bean, "setDeepSleepList", toTimeRegins(reginCls, n.deep))
                XposedHelpers.callMethod(bean, "setLightSleepList", toTimeRegins(reginCls, n.light))
                XposedHelpers.callMethod(bean, "setRemSleepList", toTimeRegins(reginCls, n.rem))
                XposedHelpers.callMethod(bean, "setAwakeSleepList", toTimeRegins(reginCls, n.awake))
                XposedHelpers.callMethod(bean, "setDeepSleepTime", n.deepMin)
                XposedHelpers.callMethod(bean, "setLightSleepTime", n.lightMin)
                XposedHelpers.callMethod(bean, "setRemSleepTime", n.remMin)
                XposedHelpers.callMethod(bean, "setAwakeSleepTime", n.awakeMin)
                XposedHelpers.callMethod(bean, "setSourceFrom", "VIVO_WATCH")
                XposedHelpers.callMethod(bean, "setIsUploaded", false)
                beans.add(bean)
                if (latest == null || n.exitTimeMs >= latest.exitTimeMs) latest = n
            }
            XposedHelpers.callStaticMethod(healthDbCls, "insertSleepDataFromAppleWatch", beans)
            if (latest != null && openId.isNotEmpty()) {
                val asleepMin = latest.deepMin + latest.lightMin + latest.remMin
                updateLatestSleepCache(loader, openId, latest.exitTimeMs, asleepMin)
            }
            true
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_WRITE_SLEEP_FAILED", failure)
            false
        }
    }

    private fun writeSportSessionsAndTracks(
        loader: ClassLoader?,
        snapshot: VivoDeviceProfile.Snapshot,
        sessions: List<VivoHealthMapper.SportSession>,
        gpsPoints: List<VivoHealthMapper.GpsPoint>,
    ): Boolean {
        if (sessions.isEmpty() && gpsPoints.isEmpty()) return true
        return try {
            val sportDbCls = XposedHelpers.findClass(CLS_WATCH_SPORT_DB, loader)
            val recordCls = XposedHelpers.findClass(CLS_SPORT_RECORD, loader)
            val gpsCls = XposedHelpers.findClass(CLS_WATCH_GPS, loader)

            val remainingGps = ArrayList(gpsPoints)
            for (s in sessions) {
                val existing = XposedHelpers.callStaticMethod(sportDbCls, "querySportRecordByEid", s.eid)
                if (existing == null) {
                    val bean = XposedHelpers.newInstance(recordCls)
                    XposedHelpers.callMethod(bean, "setEid", s.eid)
                    XposedHelpers.callMethod(bean, "setTag", s.tag)
                    XposedHelpers.callMethod(bean, "setType", s.vivoSportType)
                    XposedHelpers.callMethod(bean, "setRtcStartTime", s.rtcStartTimeMs)
                    XposedHelpers.callMethod(bean, "setRtcEndTime", s.rtcEndTimeMs)
                    XposedHelpers.callMethod(bean, "setTime", s.durationMs)
                    XposedHelpers.callMethod(bean, "setActiveDuration", s.activeDurationSec)
                    XposedHelpers.callMethod(bean, "setCalorie", s.calorieKcal)
                    XposedHelpers.callMethod(bean, "setDistance", s.distanceMeters)
                    XposedHelpers.callMethod(bean, "setSteps", s.steps)
                    if (s.avgHeartRate > 0) {
                        XposedHelpers.callMethod(bean, "setAverageRate", s.avgHeartRate)
                        XposedHelpers.callMethod(bean, "setMaxRate", s.avgHeartRate)
                    }
                    if (s.averagePaceSecPerKm > 0) {
                        XposedHelpers.callMethod(bean, "setAveragePace", s.averagePaceSecPerKm)
                        XposedHelpers.callMethod(bean, "setAveragePaceKm", s.averagePaceSecPerKm)
                    }
                    if (s.averageSpeedMps > 0f) {
                        XposedHelpers.callMethod(bean, "setAverageSpeedV1", s.averageSpeedMps)
                    }
                    XposedHelpers.callMethod(bean, "setIsComplete", true)
                    XposedHelpers.callMethod(bean, "setLocal", true)
                    XposedHelpers.callMethod(bean, "setSourceFrom", "VIVO_WATCH")
                    XposedHelpers.callMethod(bean, "setWatchModel", snapshot.modelId)
                    XposedHelpers.callMethod(bean, "setWatchOTAVersion", snapshot.firmwareVersion())
                    XposedHelpers.callMethod(bean, "setPid", snapshot.productId())
                    XposedHelpers.callMethod(bean, "setWatchShape", if (snapshot.isRoundWatch()) 0 else 1)

                    val matchingGps = popGpsForTag(gpsCls, remainingGps, s.tag)
                    if (matchingGps.isNotEmpty()) {
                        XposedHelpers.callStaticMethod(sportDbCls, "insertGPSDataList", matchingGps)
                    }
                    val result = XposedHelpers.callStaticMethod(sportDbCls, "insertSportRecord", bean)
                    if (result != null && XposedHelpers.callMethod(result, "e") == true) {
                        val recordRowId = XposedHelpers.callMethod(result, "b") as? Long
                        if (recordRowId != null && recordRowId > 0L) {
                            XposedHelpers.callStaticMethod(sportDbCls, "notifyRecordEvent", recordRowId)
                        }
                    }
                }
            }

            if (remainingGps.isNotEmpty()) {
                val leftover = ArrayList<Any>(remainingGps.size)
                remainingGps.sortBy { it.rtcTimeMs }
                for (gp in remainingGps) {
                    leftover.add(toWatchGpsBean(gpsCls, gp))
                }
                XposedHelpers.callStaticMethod(sportDbCls, "insertGPSDataList", leftover)
            }
            true
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_WRITE_SPORT_FAILED", failure)
            false
        }
    }

    private fun popGpsForTag(
        gpsCls: Class<*>,
        pool: MutableList<VivoHealthMapper.GpsPoint>,
        tag: Int,
    ): List<Any> {
        val matched = ArrayList<Any>()
        for (i in pool.indices.reversed()) {
            val p = pool[i]
            if (p.tag == tag) {
                matched.add(toWatchGpsBean(gpsCls, p))
                pool.removeAt(i)
            }
        }
        matched.reverse()
        return matched
    }

    private fun toWatchGpsBean(gpsCls: Class<*>, gp: VivoHealthMapper.GpsPoint): Any {
        val bean = XposedHelpers.newInstance(gpsCls)
        XposedHelpers.callMethod(bean, "setTag", gp.tag)
        XposedHelpers.callMethod(bean, "setStatus", 0)
        XposedHelpers.callMethod(bean, "setRtcTime", gp.rtcTimeMs)
        XposedHelpers.callMethod(bean, "setLatitude", gp.latitude)
        XposedHelpers.callMethod(bean, "setLongitude", gp.longitude)
        XposedHelpers.callMethod(bean, "setSpeed", gp.speedMps)
        XposedHelpers.callMethod(bean, "setIs_gps_valid", 1)
        XposedHelpers.callMethod(bean, "setGps_source", 0)
        XposedHelpers.callMethod(bean, "setVersion", 6)
        return bean
    }

    private fun toTimeRegins(reginCls: Class<*>, ranges: List<VivoHealthMapper.SleepRange>): List<Any> {
        val list = ArrayList<Any>(ranges.size)
        for (r in ranges) {
            list.add(XposedHelpers.newInstance(reginCls, r.enterMs, r.exitMs))
        }
        return list
    }

    private fun readOpenId(loader: ClassLoader?): String {
        return try {
            val acctCls = XposedHelpers.findClass(CLS_ACCOUNT_INIT, loader)
            val instance = XposedHelpers.getStaticObjectField(acctCls, "a") ?: return ""
            (XposedHelpers.callMethod(instance, "e") as? String)?.trim() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun spPut(loader: ClassLoader?, key: String, valObj: Any) {
        try {
            val spCls = XposedHelpers.findClass(CLS_SP_UTIL, loader)
            XposedHelpers.callStaticMethod(spCls, "put", key, valObj)
        } catch (_: Throwable) {
        }
    }

    private fun syncStressHealthKit(loader: ClassLoader?, stress: Int, timeMs: Long) {
        try {
            val bizCls = XposedHelpers.findClass(CLS_BUSINESS_MGR, loader)
            val hkCls = XposedHelpers.findClass(CLS_HEALTHKIT_SYNC, loader)
            val svc = XposedHelpers.callStaticMethod(bizCls, "getService", hkCls)
            if (svc != null) XposedHelpers.callMethod(svc, "d4", stress, timeMs)
        } catch (_: Throwable) {
        }
    }

    private fun updateLatestSleepCache(
        loader: ClassLoader?,
        openId: String,
        exitMs: Long,
        asleepMin: Int,
    ) {
        try {
            val bizCls = XposedHelpers.findClass(CLS_BUSINESS_MGR, loader)
            val physCls = XposedHelpers.findClass(CLS_MODULE_PHYSICAL, loader)
            val svc = XposedHelpers.callStaticMethod(bizCls, "getService", physCls) ?: return
            val hpCls = XposedHelpers.findClass(CLS_ROUTER_HEALTH_POINT, loader)
            val hp = XposedHelpers.newInstance(hpCls)
            XposedHelpers.callMethod(hp, "setTimestamp", exitMs)
            XposedHelpers.callMethod(hp, "setValue", asleepMin)
            XposedHelpers.callMethod(svc, "A6", openId, hp)
        } catch (_: Throwable) {
        }
    }

    private fun emitRefreshEvents(loader: ClassLoader?, wroteActivity: Boolean, wroteHealth: Boolean) {
        try {
            val eventMgrCls = XposedHelpers.findClass(CLS_PROCESS_EVENT_MGR, loader)
            val paramObjCls = XposedHelpers.findClass(CLS_PARAM_OBJECT, loader)
            val mgr = XposedHelpers.callStaticMethod(eventMgrCls, "getDefault") ?: return
            if (wroteActivity) {
                XposedHelpers.callMethod(mgr, "post", PROCESS_KEY_MAIN, EVENT_ACTIVITY_SYNC, null)
            }
            if (wroteHealth || wroteActivity) {
                XposedHelpers.callMethod(mgr, "post", PROCESS_KEY_MAIN, EVENT_HEALTH_SYNC, null)
            }
            val trueParam = XposedHelpers.callStaticMethod(
                paramObjCls,
                "buildParamObject",
                Boolean::class.javaObjectType,
                true,
            )
            XposedHelpers.callMethod(mgr, "post", PROCESS_KEY_MAIN, EVENT_POST_HEALTH_MODEL, trueParam)
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_POST_EVENT_FAILED", failure)
        }
    }

    private fun ackReceipt(context: Context, queueAccount: String, r: VivoHealthMapper.Receipt) {
        try {
            val b = Bundle()
            b.putString("account", queueAccount)
            b.putString("recordId", r.recordId)
            b.putInt("revision", r.revision)
            context.contentResolver.call(HealthQueueProvider.URI, "ack", null, b)
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_ACK_FAILED", failure)
        }
    }

    private fun releaseUnsupported(context: Context, queueAccount: String, r: VivoHealthMapper.Receipt) {
        try {
            val b = Bundle()
            b.putString("account", queueAccount)
            b.putString("recordId", r.recordId)
            b.putInt("revision", r.revision)
            context.contentResolver.call(HealthQueueProvider.URI, "releaseUnsupported", null, b)
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_RELEASE_UNSUPPORTED_FAILED", failure)
        }
    }

    private fun nonEmptyDeviceId(snapshot: VivoDeviceProfile.Snapshot): String {
        val id = snapshot.stableDeviceId()
        return id.ifEmpty { "mi_watch" }
    }
}
