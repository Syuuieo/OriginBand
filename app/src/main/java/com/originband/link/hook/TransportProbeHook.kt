// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Bundle
import com.originband.link.data.AuthToken
import com.originband.link.hook.xposed.HookRuntime
import com.originband.link.hook.xposed.XC_MethodHook
import com.originband.link.hook.xposed.XposedBridge
import com.originband.link.hook.xposed.XposedHelpers
import com.originband.link.integration.CredentialProvider
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ThreadPoolExecutor

/** Records SPP metadata during the setup import and profile window. Keys and payload bytes stay out of the record. */
object TransportProbeHook {
    @JvmStatic
    @Throws(Exception::class)
    fun install(context: Context, loader: ClassLoader?) {
        val writer = HookRuntime.worker("OplusBandTransportProbe", 16)
        val apiClass = Class.forName("com.xiaomi.wearable.wear.api.WearApiCall", false, loader)
        val infoClass = Class.forName("com.xiaomi.wearable.core.DeviceInfo", false, loader)
        val observeApi = object : XC_MethodHook() {
            protected override fun afterHookedMethod(param: MethodHookParam) {
                if (!param.hasThrowable()) observe(context, writer, param.thisObject, null)
            }
        }
        XposedBridge.hookMethod(apiClass.getDeclaredMethod("onConnected"), observeApi)
        XposedBridge.hookMethod(apiClass.getDeclaredMethod("onConnectSuccessInternal"), observeApi)
        XposedBridge.hookMethod(apiClass.getDeclaredMethod("onUpdate", infoClass), observeApi)
        val ble = Class.forName("com.xiaomi.wearable.connection.BleConnection", false, loader)
        XposedBridge.hookMethod(
            ble.getDeclaredMethod("onConnectSuccess"),
            object : XC_MethodHook() {
                protected override fun afterHookedMethod(param: MethodHookParam) {
                    if (!param.hasThrowable()) observeConnection(context, writer, param.thisObject, "getBleApiCall")
                }
            },
        )
        val spp = Class.forName("com.xiaomi.wearable.connection.SppConnection", false, loader)
        XposedBridge.hookMethod(
            spp.getDeclaredMethod("onConnectStatusChanged", Boolean::class.java),
            object : XC_MethodHook() {
                protected override fun afterHookedMethod(param: MethodHookParam) {
                    if (!param.hasThrowable() && param.args[0] == true) {
                        observeConnection(context, writer, param.thisObject, "getSppApiCall")
                    }
                }
            },
        )
        val auth = Class.forName("com.xiaomi.wearable.wear.api.WearAuthV2", false, loader)
        XposedBridge.hookMethod(
            auth.getDeclaredConstructor(
                String::class.java,
                ByteArray::class.java,
                String::class.java,
                String::class.java,
                Boolean::class.java,
                apiClass,
                Int::class.java,
            ),
            object : XC_MethodHook() {
                protected override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    val metadata = Bundle()
                    metadata.putString("authImplementation", "WearAuthV2")
                    metadata.putBoolean("authFlagObserved", param.args[4] as Boolean)
                    metadata.putInt("authCtorVersion", param.args[6] as Int)
                    // Record whether optional credentials are present. Their values and the binding key stay out of the record.
                    metadata.putBoolean("authAppDeviceIdPresent", !(param.args[2] as? String).isNullOrEmpty())
                    metadata.putBoolean("authOobPresent", !(param.args[3] as? String).isNullOrEmpty())
                    observe(context, writer, param.args[5], metadata)
                    val key = param.args[1] as? ByteArray
                    if (key != null) supplementToken(context, writer, param.args[5], key)
                }
            },
        )
        installSocketProbe(context, writer)
    }

    @Throws(Exception::class)
    private fun installSocketProbe(context: Context, writer: ThreadPoolExecutor) {
        val pendingSockets = Collections.synchronizedMap(WeakHashMap<Any, Bundle>())
        for (method in arrayOf("createRfcommSocketToServiceRecord", "createInsecureRfcommSocketToServiceRecord")) {
            XposedBridge.hookMethod(
                BluetoothDevice::class.java.getDeclaredMethod(method, UUID::class.java),
                object : XC_MethodHook() {
                    protected override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.getResult()
                        if (param.hasThrowable() || result == null) return
                        try {
                            val request = context.contentResolver.call(
                                CredentialProvider.URI,
                                "getDiagnosticRequest",
                                null,
                                null,
                            )
                            if (request == null || "DIAGNOSTIC_WINDOW_OPEN" != request.getString("status")) return
                            val address = (param.thisObject as BluetoothDevice).address
                            if (address == null || address.uppercase(Locale.ROOT) != request.getString("address")) return
                            val metadata = Bundle()
                            metadata.putString("address", request.getString("address"))
                            metadata.putString("nonce", request.getString("nonce"))
                            metadata.putString("rfcommUuid", (param.args[0] as UUID).toString())
                            metadata.putBoolean("rfcommSecure", method == "createRfcommSocketToServiceRecord")
                            pendingSockets[result] = metadata
                        } catch (_: Throwable) {
                            HookRuntime.log("RFCOMM_OBSERVATION_FAILED")
                        }
                    }
                },
            )
        }
        XposedBridge.hookMethod(
            BluetoothSocket::class.java.getDeclaredMethod("connect"),
            object : XC_MethodHook() {
                protected override fun afterHookedMethod(param: MethodHookParam) {
                    val metadata = pendingSockets.remove(param.thisObject) ?: return
                    metadata.putBoolean("rfcommSocketConnected", !param.hasThrowable())
                    try {
                        writer.execute {
                            try {
                                context.contentResolver.call(CredentialProvider.URI, "observeTransport", null, metadata)
                            } catch (_: RuntimeException) {
                                HookRuntime.log("RFCOMM_OBSERVATION_REJECTED")
                            } finally {
                                metadata.clear()
                            }
                        }
                    } catch (_: RuntimeException) {
                        HookRuntime.log("RFCOMM_OBSERVATION_QUEUE_FULL")
                    }
                }
            },
        )
    }

    private fun observeConnection(context: Context, writer: ThreadPoolExecutor, connection: Any?, getter: String) {
        try {
            val target = checkNotNull(connection)
            observe(context, writer, XposedHelpers.callMethod(target, getter), null)
        } catch (_: Throwable) {
            HookRuntime.log("TRANSPORT_OBSERVATION_FAILED")
        }
    }

    private fun observe(context: Context, writer: ThreadPoolExecutor, api: Any?, auth: Bundle?) {
        try {
            val request = context.contentResolver.call(CredentialProvider.URI, "getDiagnosticRequest", null, null)
            if (request == null || "DIAGNOSTIC_WINDOW_OPEN" != request.getString("status")) return
            val apiObj = checkNotNull(api)
            val info = checkNotNull(XposedHelpers.callMethod(apiObj, "getDeviceInfo"))
            val address = XposedHelpers.callMethod(info, "getAddress") as? String
            if (address == null || address.uppercase(Locale.ROOT) != request.getString("address")) return
            val metadata = if (auth == null) Bundle() else Bundle(auth)
            metadata.putString("address", request.getString("address"))
            metadata.putString("nonce", request.getString("nonce"))
            val connection = XposedHelpers.callMethod(apiObj, "getConnection")
            if (connection != null) {
                val name = connection.javaClass.name
                metadata.putString("connectionClass", name)
                if (name == "com.xiaomi.wearable.connection.BleConnection") metadata.putString("transport", "GATT")
                if (name == "com.xiaomi.wearable.connection.SppConnection") metadata.putString("transport", "SPP")
                metadata.putBoolean("officialAuthConnected", XposedHelpers.callMethod(apiObj, "isAuthConnected") as Boolean)
            }
            val queue = XposedHelpers.getObjectField(apiObj, "mQueue")
            if (queue != null) metadata.putString("queueClass", queue.javaClass.name)
            metadata.putInt("apiVersion", XposedHelpers.callMethod(apiObj, "getVersion") as Int)
            metadata.putInt("appCapability", XposedHelpers.callMethod(apiObj, "getAppCapability") as Int)
            metadata.putInt("deviceType", XposedHelpers.callMethod(info, "getType") as Int)
            metadata.putInt("accessType", XposedHelpers.callMethod(info, "getAccessType") as Int)
            metadata.putString("model", XposedHelpers.callMethod(info, "getModel") as? String)
            metadata.putString("firmware", XposedHelpers.callMethod(info, "getFirmwareVersion") as? String)
            metadata.putString("productId", XposedHelpers.callMethod(info, "getProductId") as? String)
            if (apiObj.javaClass.name == "ynq") {
                val transport = checkNotNull(XposedHelpers.callMethod(apiObj, "d"))
                metadata.putString("versionName", XposedHelpers.callMethod(transport, "getVersionName") as? String)
            }
            writer.execute {
                try {
                    context.contentResolver.call(CredentialProvider.URI, "observeTransport", null, metadata)
                } catch (_: RuntimeException) {
                    HookRuntime.log("TRANSPORT_OBSERVATION_REJECTED")
                } finally {
                    metadata.clear()
                }
            }
        } catch (_: Throwable) {
            HookRuntime.log("TRANSPORT_OBSERVATION_FAILED")
        }
    }

    private fun supplementToken(context: Context, writer: ThreadPoolExecutor, api: Any?, key: ByteArray) {
        val token = AuthToken.fromKeyBytes(key)
        if (!AuthToken.hex32(token)) return
        try {
            val request = context.contentResolver.call(CredentialProvider.URI, "getDiagnosticRequest", null, null)
            if (request == null || "DIAGNOSTIC_WINDOW_OPEN" != request.getString("status")) return
            val apiObj = checkNotNull(api)
            val info = checkNotNull(XposedHelpers.callMethod(apiObj, "getDeviceInfo"))
            val address = XposedHelpers.callMethod(info, "getAddress") as? String
            if (address == null || address.uppercase(Locale.ROOT) != request.getString("address")) return
            val payload = Bundle()
            payload.putString("token", token)
            payload.putString("address", request.getString("address"))
            payload.putString("nonce", request.getString("nonce"))
            writer.execute {
                try {
                    context.contentResolver.call(CredentialProvider.URI, "supplementToken", null, payload)
                } catch (_: RuntimeException) {
                    HookRuntime.log("TOKEN_SUPPLEMENT_REJECTED")
                } finally {
                    payload.clear()
                }
            }
        } catch (_: Throwable) {
            HookRuntime.log("TOKEN_SUPPLEMENT_REJECTED")
        }
    }
}
