// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import com.originband.link.hook.xposed.HookRuntime
import com.originband.link.hook.xposed.XC_MethodHook
import com.originband.link.hook.xposed.XposedHelpers
import com.originband.link.host.vivo.VivoDeviceProfile
import com.originband.link.integration.DeviceCardProvider
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Registers the Xiaomi wearable as a connected virtual watch inside com.vivo.health.deviceConn
 * (for live DevDeviceManager/VIPC/battery/manual-sync/find-watch) and com.vivo.health main
 * (for MainDeviceManager device list & state cards).
 */
object VivoHealthDeviceHook {
    private const val CLS_DEVICE_INFO_BEAN = "com.vivo.framework.bean.DeviceInfoBean"
    private const val CLS_CONN_STATE = "com.vivo.framework.devices.control.state.ConnState"
    private const val CLS_CONNECT_MODE = "com.vivo.framework.devices.control.bind.ConnectMode"
    private const val CLS_CONN_DEVICE = "com.vivo.framework.devices.control.ConnDevice"
    private const val CLS_DEVICE_FACTORY = "com.vivo.framework.devices.DeviceFactory"
    private const val CLS_I_DEVICE = "com.vivo.framework.devices.control.IDevice"
    private const val CLS_DEV_MGR = "com.vivo.framework.devices.process.dev.DevDeviceManager"
    private const val CLS_MAIN_MGR = "com.vivo.framework.devices.process.main.MainDeviceManager"
    private const val CLS_ONLINE_MGR = "com.vivo.framework.CenterManager.OnlineDeviceManager"
    private const val CLS_BID_CONTROLLER = "com.vivo.framework.CenterManager.BidController"
    private const val CLS_BID_VERSION = "com.vivo.framework.CenterManager.BidVersion"
    private const val CLS_DEV_SERVICE_IMP =
        "com.vivo.health.devices.watch.devicehelper.DevicesServiceManagerImp"
    private const val CLS_HEALTH_DATA_MODULE =
        "com.vivo.health.devices.watch.healthdata.HealthDataModule"
    private const val CLS_SPORT_MODULE = "com.vivo.health.devices.watch.sport.SportModule"
    private const val CLS_FIND_BLE_HELPER = "com.vivo.health.devices.watch.find.ble.FindBleHelper"
    private const val CLS_WATCH_SYNC_LISTENER =
        "com.vivo.health.lib.router.syncdata.WatchSyncListener"
    private const val CLS_RX_SINGLE = "io.reactivex.Single"
    private const val CLS_PRODUCT_7_BIZ =
        "com.vivo.health.devices.watch.common.business.common.WatchProduct7CommonBusiness"
    private const val CLS_PRODUCT_8_BIZ =
        "com.vivo.health.devices.watch.common.business.common.WatchProduct8CommonBusiness"
    private const val CLS_PREVIEW_DECORATOR =
        "com.vivo.health.devices.watch.common.business.common.PreviewDecorator"
    private const val CLS_WATCH_GENERIC_PREVIEW =
        "com.vivo.health.devices.watch.dial.generic.widget.WatchGenericPreview"

    private val lastDispatchedConnState = AtomicInteger(-1)

    @Volatile
    private var cachedImageUri: String = ""

    @Volatile
    private var cachedImageBitmap: Bitmap? = null

    @JvmStatic
    fun installInDeviceConn(context: Context, loader: ClassLoader?) {
        val worker = HookRuntime.worker("OriginBandVivoDev", 0)
        HookRuntime.observe(
            context.contentResolver,
            DeviceCardProvider.URI,
            false,
            object : ContentObserver(HookRuntime.main()) {
                override fun onChange(selfChange: Boolean) {
                    worker.execute { syncDevState(context, loader) }
                }
            },
        )

        HookRuntime.tryHook("VIVO_DEV_LIST_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_DEV_MGR,
                loader,
                "getDeviceListFromDb",
                object : XC_MethodHook() {
                    protected override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val snapshot = readSnapshot(context)
                        if (!snapshot.isUsable()) return
                        val virtualBean = buildDeviceInfoBean(loader, snapshot)
                        param.setResult(mergeDeviceInfoList(param.getResult(), virtualBean, snapshot.normalizedMac()))
                    }
                },
            )
        }

        HookRuntime.tryHook("VIVO_DEV_INIT_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_DEV_MGR,
                loader,
                "initDeviceList",
                object : XC_MethodHook() {
                    protected override fun afterHookedMethod(param: MethodHookParam) {
                        worker.execute { syncDevState(context, loader) }
                    }
                },
            )
        }

        HookRuntime.tryHook("VIVO_DEV_CONNECT_MODE_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_DEV_MGR,
                loader,
                "connectAsync",
                CLS_CONNECT_MODE,
                object : XC_MethodHook() {
                    protected override fun beforeHookedMethod(param: MethodHookParam) {
                        val snapshot = readSnapshot(context)
                        val target = param.thisObject ?: return
                        if (!snapshot.isUsable()) return
                        val current = XposedHelpers.callMethod(target, "getNowDevice")
                        val currentMac = if (current == null) {
                            ""
                        } else {
                            VivoDeviceProfile.normalizeMac(XposedHelpers.callMethod(current, "s") as? String)
                        }
                        if (currentMac.isEmpty() || snapshot.normalizedMac() == currentMac) {
                            param.setResult(true)
                            worker.execute { syncDevState(context, loader) }
                        }
                    }
                },
            )
        }

        HookRuntime.tryHook("VIVO_DEV_CONNECT_DEV_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_DEV_MGR,
                loader,
                "connectAsync",
                CLS_CONN_DEVICE,
                CLS_CONNECT_MODE,
                object : XC_MethodHook() {
                    protected override fun beforeHookedMethod(param: MethodHookParam) {
                        val snapshot = readSnapshot(context)
                        val connDevice = param.args[0]
                        if (!snapshot.isUsable() || connDevice == null) return
                        val targetMac = VivoDeviceProfile.normalizeMac(
                            XposedHelpers.callMethod(connDevice, "getMac") as? String,
                        )
                        if (snapshot.normalizedMac() == targetMac) {
                            param.setResult(true)
                            worker.execute { syncDevState(context, loader) }
                        }
                    }
                },
            )
        }

        HookRuntime.tryHook("VIVO_IDEVICE_CONNECT_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_I_DEVICE,
                loader,
                "d",
                CLS_CONNECT_MODE,
                object : XC_MethodHook() {
                    protected override fun beforeHookedMethod(param: MethodHookParam) {
                        val snapshot = readSnapshot(context)
                        val device = param.thisObject ?: return
                        if (!snapshot.isUsable()) return
                        val deviceMac = VivoDeviceProfile.normalizeMac(
                            XposedHelpers.callMethod(device, "s") as? String,
                        )
                        if (snapshot.normalizedMac() == deviceMac) {
                            param.setResult(true)
                        }
                    }
                },
            )
        }

        installManualSyncHooks(context, loader, worker)
        installFindWatchHooks(context, loader, worker)

        HookRuntime.main().postDelayed({ worker.execute { syncDevState(context, loader) } }, 1500L)
        HookRuntime.log("OriginBand: VIVO_DEVICE_CONN_HOOKS_INSTALLED")
    }

    @JvmStatic
    fun installInMain(context: Context, loader: ClassLoader?) {
        val worker = HookRuntime.worker("OriginBandVivoMainDev", 0)
        HookRuntime.observe(
            context.contentResolver,
            DeviceCardProvider.URI,
            false,
            object : ContentObserver(HookRuntime.main()) {
                override fun onChange(selfChange: Boolean) {
                    worker.execute { syncMainState(context, loader) }
                }
            },
        )

        HookRuntime.tryHook("VIVO_MAIN_DEV_LIST_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_MAIN_MGR,
                loader,
                "getDeviceListFromDb",
                object : XC_MethodHook() {
                    protected override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val snapshot = readSnapshot(context)
                        if (!snapshot.isUsable()) return
                        val virtualBean = buildDeviceInfoBean(loader, snapshot)
                        param.setResult(mergeDeviceInfoList(param.getResult(), virtualBean, snapshot.normalizedMac()))
                    }
                },
            )
        }

        HookRuntime.tryHook("VIVO_MAIN_FRESH_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_MAIN_MGR,
                loader,
                "freshDeviceList",
                object : XC_MethodHook() {
                    protected override fun afterHookedMethod(param: MethodHookParam) {
                        worker.execute { syncMainState(context, loader) }
                    }
                },
            )
        }

        installDeviceImageHooks(context, loader, worker)
        HookRuntime.main().postDelayed({ worker.execute { syncMainState(context, loader) } }, 1500L)
        HookRuntime.log("OriginBand: VIVO_MAIN_DEVICE_HOOKS_INSTALLED")
    }

    private fun installDeviceImageHooks(
        context: Context,
        loader: ClassLoader?,
        worker: ThreadPoolExecutor,
    ) {
        val bizClasses = listOf(CLS_PRODUCT_7_BIZ, CLS_PRODUCT_8_BIZ)
        for (clsName in bizClasses) {
            HookRuntime.tryHook("VIVO_PRODUCT_IMAGE_A_FAILED") {
                XposedHelpers.findAndHookMethod(
                    clsName,
                    loader,
                    "A",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    ImageView::class.java,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        protected override fun beforeHookedMethod(param: MethodHookParam) {
                            val deviceId = param.args[1] as? String ?: return
                            val imageView = param.args[2] as? ImageView ?: return
                            if (applyVirtualDeviceImage(context, deviceId, imageView, worker)) {
                                param.result = null
                            }
                        }
                    },
                )
            }
            HookRuntime.tryHook("VIVO_PRODUCT_IMAGE_M_FAILED") {
                XposedHelpers.findAndHookMethod(
                    clsName,
                    loader,
                    "m",
                    CLS_PREVIEW_DECORATOR,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    object : XC_MethodHook() {
                        protected override fun beforeHookedMethod(param: MethodHookParam) {
                            val decorator = param.args[0] ?: return
                            val deviceId = param.args[1] as? String ?: return
                            val targetView = (XposedHelpers.callMethod(decorator, "getSecondImage") as? ImageView)
                                ?: (XposedHelpers.callMethod(decorator, "getFistImage") as? ImageView)
                                ?: return
                            if (applyVirtualDeviceImage(context, deviceId, targetView, worker)) {
                                param.result = null
                            }
                        }
                    },
                )
            }
        }

        HookRuntime.tryHook("VIVO_PREVIEW_DEFAULT_DIAL_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_WATCH_GENERIC_PREVIEW,
                loader,
                "setLocalDefaultDial",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    protected override fun beforeHookedMethod(param: MethodHookParam) {
                        val preview = param.thisObject ?: return
                        val snapshot = readSnapshot(context)
                        if (!matchesVirtualPreview(preview, snapshot)) return
                        applyVirtualPreview(context, preview, snapshot, worker)
                        param.result = null
                    }
                },
            )
        }

        HookRuntime.tryHook("VIVO_PREVIEW_SET_DIAL_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_WATCH_GENERIC_PREVIEW,
                loader,
                "D",
                object : XC_MethodHook() {
                    protected override fun beforeHookedMethod(param: MethodHookParam) {
                        val preview = param.thisObject ?: return
                        val snapshot = readSnapshot(context)
                        if (!matchesVirtualPreview(preview, snapshot)) return
                        applyVirtualPreview(context, preview, snapshot, worker)
                        param.result = null
                    }
                },
            )
        }

        HookRuntime.tryHook("VIVO_PREVIEW_SET_BG_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_WATCH_GENERIC_PREVIEW,
                loader,
                "K",
                object : XC_MethodHook() {
                    protected override fun afterHookedMethod(param: MethodHookParam) {
                        val preview = param.thisObject ?: return
                        val snapshot = readSnapshot(context)
                        if (!matchesVirtualPreview(preview, snapshot)) return
                        applyVirtualPreview(context, preview, snapshot, worker)
                    }
                },
            )
        }
    }

    private fun matchesVirtualPreview(preview: Any, snapshot: VivoDeviceProfile.Snapshot): Boolean {
        if (!snapshot.isUsable() || snapshot.imageUri.isBlank()) return false
        val deviceId = runCatching { XposedHelpers.getObjectField(preview, "mDeviceId") as? String }.getOrNull()
        val productId = runCatching { XposedHelpers.getIntField(preview, "mProductId") }.getOrDefault(0)
        return deviceId == snapshot.stableDeviceId() ||
            (deviceId.isNullOrEmpty() && productId == snapshot.productId())
    }

    private fun applyVirtualPreview(
        context: Context,
        preview: Any,
        snapshot: VivoDeviceProfile.Snapshot,
        worker: ThreadPoolExecutor,
    ) {
        val dialView = runCatching { XposedHelpers.getObjectField(preview, "dialView") as? View }.getOrNull()
        if (dialView != null) {
            dialView.visibility = View.GONE
            if (dialView is ImageView) dialView.setImageDrawable(null)
        }
        val bgView = runCatching { XposedHelpers.getObjectField(preview, "bgView") as? ImageView }.getOrNull()
            ?: return
        applyVirtualDeviceImage(context, snapshot.stableDeviceId(), bgView, worker)
    }

    private fun applyVirtualDeviceImage(
        context: Context,
        deviceId: String,
        imageView: ImageView,
        worker: ThreadPoolExecutor,
    ): Boolean {
        val snapshot = readSnapshot(context)
        if (!snapshot.isUsable() || snapshot.stableDeviceId() != deviceId) return false
        val uri = snapshot.imageUri
        if (uri.isBlank()) return false
        val cached = if (uri == cachedImageUri) cachedImageBitmap else null
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return true
        }
        worker.execute {
            val loaded = try {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (_: Throwable) {
                null
            } ?: return@execute
            cachedImageUri = uri
            cachedImageBitmap = loaded
            HookRuntime.main().post { imageView.setImageBitmap(loaded) }
        }
        return true
    }

    private fun installManualSyncHooks(context: Context, loader: ClassLoader?, worker: ThreadPoolExecutor) {
        HookRuntime.tryHook("VIVO_SYNC_NEWEST_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_DEV_SERVICE_IMP,
                loader,
                "h",
                object : XC_MethodHook() {
                    protected override fun beforeHookedMethod(param: MethodHookParam) {
                        if (readSnapshot(context).isUsable()) {
                            worker.execute { requestBridgeSync(context) }
                        }
                    }
                },
            )
        }

        for (method in arrayOf("i", "k", "l")) {
            HookRuntime.tryHook("VIVO_SYNC_LISTENER_HOOK_$method") {
                XposedHelpers.findAndHookMethod(
                    CLS_DEV_SERVICE_IMP,
                    loader,
                    method,
                    CLS_WATCH_SYNC_LISTENER,
                    object : XC_MethodHook() {
                        protected override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!readSnapshot(context).isUsable()) return
                            worker.execute { requestBridgeSync(context) }
                            completeSyncListener(param.args[0])
                            param.setResult(null)
                        }
                    },
                )
            }
        }

        HookRuntime.tryHook("VIVO_HEALTH_MODULE_SYNC_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_HEALTH_DATA_MODULE,
                loader,
                "lambda\$syncDataAsynchronously\$0",
                Int::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    protected override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!readSnapshot(context).isUsable()) return
                        worker.execute { requestBridgeSync(context) }
                        param.setResult(true)
                    }
                },
            )
        }

        HookRuntime.tryHook("VIVO_SPORT_MODULE_SYNC_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_SPORT_MODULE,
                loader,
                "w9",
                object : XC_MethodHook() {
                    protected override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!readSnapshot(context).isUsable()) return
                        worker.execute { requestBridgeSync(context) }
                        param.setResult(true)
                    }
                },
            )
        }
    }

    private fun installFindWatchHooks(context: Context, loader: ClassLoader?, worker: ThreadPoolExecutor) {
        HookRuntime.tryHook("VIVO_FIND_WATCH_START_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_FIND_BLE_HELPER,
                loader,
                "t5",
                object : XC_MethodHook() {
                    protected override fun beforeHookedMethod(param: MethodHookParam) {
                        val target = param.thisObject ?: return
                        if (!readSnapshot(context).isUsable()) return
                        worker.execute { requestFindWatch(context, true) }
                        XposedHelpers.callMethod(target, "m9", true)
                        HookRuntime.main().post { XposedHelpers.callMethod(target, "s9") }
                        val singleCls = XposedHelpers.findClass(CLS_RX_SINGLE, loader)
                        param.setResult(XposedHelpers.callStaticMethod(singleCls, "just", 0))
                    }
                },
            )
        }

        HookRuntime.tryHook("VIVO_FIND_WATCH_STOP_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                CLS_FIND_BLE_HELPER,
                loader,
                "H0",
                object : XC_MethodHook() {
                    protected override fun beforeHookedMethod(param: MethodHookParam) {
                        val target = param.thisObject ?: return
                        if (!readSnapshot(context).isUsable()) return
                        worker.execute { requestFindWatch(context, false) }
                        XposedHelpers.callMethod(target, "h9")
                        XposedHelpers.callMethod(target, "m9", false)
                        XposedHelpers.callMethod(target, "c9", 0)
                        val singleCls = XposedHelpers.findClass(CLS_RX_SINGLE, loader)
                        param.setResult(XposedHelpers.callStaticMethod(singleCls, "just", true))
                    }
                },
            )
        }
    }

    private fun syncDevState(context: Context, loader: ClassLoader?) {
        try {
            val snapshot = readSnapshot(context)
            if (!snapshot.isUsable()) return
            val devMgrCls = XposedHelpers.findClass(CLS_DEV_MGR, loader)
            val mgr = XposedHelpers.callStaticMethod(devMgrCls, "getInstance") ?: return
            val device = ensureDeviceRegistered(mgr, loader, snapshot) ?: return

            saveSyntheticBidVersions(loader, snapshot.firmwareVersion())

            val stateCode = snapshot.connState()
            val connStateCls = XposedHelpers.findClass(CLS_CONN_STATE, loader)
            val connState = XposedHelpers.newInstance(connStateCls, stateCode)
            XposedHelpers.callMethod(device, "A", connState)
            if (lastDispatchedConnState.getAndSet(stateCode) != stateCode) {
                val connInfo = XposedHelpers.callMethod(device, "o")
                XposedHelpers.callMethod(mgr, "dispatchConnectionStateChange", device, connState, connInfo)
            }

            if (snapshot.connected && snapshot.hasKnownBattery()) {
                val onlineCls = XposedHelpers.findClass(CLS_ONLINE_MGR, loader)
                XposedHelpers.callStaticMethod(
                    onlineCls,
                    "updateStorageAndBattry",
                    snapshot.deviceType(),
                    VivoDeviceProfile.DEFAULT_FREE_STORAGE,
                    snapshot.clampedBattery(),
                    snapshot.rawWatchBatteryState(),
                    VivoDeviceProfile.WEAR_STATE_WEARING,
                    false,
                )
            }
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_SYNC_DEV_STATE_FAILED", failure)
        }
    }

    private fun syncMainState(context: Context, loader: ClassLoader?) {
        try {
            val snapshot = readSnapshot(context)
            if (!snapshot.isUsable()) return
            val mainMgrCls = XposedHelpers.findClass(CLS_MAIN_MGR, loader)
            val mgr = XposedHelpers.callStaticMethod(mainMgrCls, "getInstance") ?: return
            ensureDeviceRegistered(mgr, loader, snapshot)
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_SYNC_MAIN_STATE_FAILED", failure)
        }
    }

    private fun ensureDeviceRegistered(
        mgr: Any,
        loader: ClassLoader?,
        snapshot: VivoDeviceProfile.Snapshot,
    ): Any? {
        val mac = snapshot.normalizedMac()
        val factoryCls = XposedHelpers.findClass(CLS_DEVICE_FACTORY, loader)
        val factory = XposedHelpers.callStaticMethod(factoryCls, "getInstance") ?: return null
        val device = XposedHelpers.callMethod(factory, "b", mac, snapshot.deviceType()) ?: return null

        val bean = buildDeviceInfoBean(loader, snapshot)
        XposedHelpers.callMethod(device, "C", bean)
        val connStateCls = XposedHelpers.findClass(CLS_CONN_STATE, loader)
        XposedHelpers.callMethod(device, "A", XposedHelpers.newInstance(connStateCls, snapshot.connState()))

        val listObj = XposedHelpers.getObjectField(mgr, "mDeviceList")
        if (listObj is MutableList<*>) {
            @Suppress("UNCHECKED_CAST")
            val list = listObj as MutableList<Any?>
            val present = list.any { item ->
                item != null && mac.equals(
                    VivoDeviceProfile.normalizeMac(XposedHelpers.callMethod(item, "s") as? String),
                    ignoreCase = true,
                )
            }
            if (!present) list.add(device)
        }

        val current = XposedHelpers.getObjectField(mgr, "mNowDevice")
        val currentMac = if (current == null) {
            ""
        } else {
            VivoDeviceProfile.normalizeMac(XposedHelpers.callMethod(current, "s") as? String)
        }
        if (current == null || currentMac.isEmpty() || mac.equals(currentMac, ignoreCase = true)) {
            XposedHelpers.setObjectField(mgr, "mNowDevice", device)
        }
        return device
    }

    private fun saveSyntheticBidVersions(loader: ClassLoader?, firmwareVersion: String) {
        try {
            val bidVerCls = XposedHelpers.findClass(CLS_BID_VERSION, loader)
            val bidCtrlCls = XposedHelpers.findClass(CLS_BID_CONTROLLER, loader)
            val versions = ArrayList<Any>(VivoDeviceProfile.SYNTHETIC_BIDS.size)
            for (pair in VivoDeviceProfile.SYNTHETIC_BIDS) {
                versions.add(XposedHelpers.newInstance(bidVerCls, pair[0], pair[1]))
            }
            XposedHelpers.callStaticMethod(bidCtrlCls, "saveBidVersions", firmwareVersion, versions)
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_BID_VERSION_SAVE_FAILED", failure)
        }
    }

    @JvmStatic
    internal fun readSnapshot(context: Context): VivoDeviceProfile.Snapshot {
        return try {
            val display = context.contentResolver.call(DeviceCardProvider.URI, "bandDisplay", null, null)
            VivoDeviceProfile.Snapshot.fromBundle(display)
        } catch (_: Throwable) {
            VivoDeviceProfile.Snapshot.fromBundle(null)
        }
    }

    @JvmStatic
    internal fun buildDeviceInfoBean(loader: ClassLoader?, snapshot: VivoDeviceProfile.Snapshot): Any {
        val beanCls = XposedHelpers.findClass(CLS_DEVICE_INFO_BEAN, loader)
        val bean = XposedHelpers.newInstance(beanCls)
        val mac = snapshot.normalizedMac()
        val batteryState = when (snapshot.rawWatchBatteryState()) {
            VivoDeviceProfile.RAW_BATTERY_CHARGING -> 1
            VivoDeviceProfile.RAW_BATTERY_FULL -> 5
            else -> 2
        }
        val connectTime = if (snapshot.lastUpdateMs > 0) snapshot.lastUpdateMs else System.currentTimeMillis()
        XposedHelpers.callMethod(bean, "setId", 1L)
        XposedHelpers.callMethod(bean, "setDeviceId", snapshot.stableDeviceId())
        XposedHelpers.callMethod(bean, "setMacAddress", mac)
        XposedHelpers.callMethod(bean, "setBtMacAddress", mac)
        XposedHelpers.callMethod(bean, "setDeviceName", snapshot.displayName())
        XposedHelpers.callMethod(bean, "setModel", snapshot.modelId)
        XposedHelpers.callMethod(bean, "setBrand", "Xiaomi")
        XposedHelpers.callMethod(bean, "setDevice_type", snapshot.deviceType())
        XposedHelpers.callMethod(bean, "setProductId", snapshot.productId())
        XposedHelpers.callMethod(bean, "setBindState", VivoDeviceProfile.BIND_STATE_BOUND)
        XposedHelpers.callMethod(bean, "setBattry", snapshot.clampedBattery())
        XposedHelpers.callMethod(bean, "setBatteryState", batteryState)
        XposedHelpers.callMethod(bean, "setVersion", snapshot.firmwareVersion())
        XposedHelpers.callMethod(bean, "setHardVer", snapshot.hardwareVersion())
        XposedHelpers.callMethod(bean, "setLastConnectTime", connectTime)
        XposedHelpers.callMethod(bean, "setWearState", VivoDeviceProfile.WEAR_STATE_WEARING)
        XposedHelpers.callMethod(bean, "setFreeStorage", VivoDeviceProfile.DEFAULT_FREE_STORAGE)
        XposedHelpers.callMethod(bean, "setTotalStorage", VivoDeviceProfile.DEFAULT_TOTAL_STORAGE)
        return bean
    }

    private fun mergeDeviceInfoList(original: Any?, virtualBean: Any, mac: String): List<Any> {
        val merged = ArrayList<Any>()
        if (original is List<*>) {
            for (item in original) {
                if (item == null) continue
                val itemMac = VivoDeviceProfile.normalizeMac(
                    XposedHelpers.callMethod(item, "getMacAddress") as? String,
                )
                if (mac.equals(itemMac, ignoreCase = true)) continue
                merged.add(item)
            }
        }
        merged.add(virtualBean)
        return merged
    }

    private fun completeSyncListener(listener: Any?) {
        if (listener == null) return
        HookRuntime.main().post {
            try {
                XposedHelpers.callMethod(listener, "onSuccess")
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.callMethod(listener, "onComplete")
            } catch (_: Throwable) {
            }
        }
    }

    private fun requestBridgeSync(context: Context) {
        try {
            context.contentResolver.call(DeviceCardProvider.URI, "requestSync", null, null)
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_REQUEST_SYNC_FAILED", failure)
        }
    }

    private fun requestFindWatch(context: Context, start: Boolean) {
        try {
            val extras = Bundle()
            extras.putBoolean("start", start)
            context.contentResolver.call(DeviceCardProvider.URI, "findWatch", null, extras)
        } catch (failure: Throwable) {
            HookRuntime.log("VIVO_FIND_WATCH_FAILED", failure)
        }
    }
}
