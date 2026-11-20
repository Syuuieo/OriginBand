// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.originband.link.BuildConfig
import com.originband.link.data.ProtocolCaptureStore
import com.originband.link.hook.xposed.HookRuntime
import com.originband.link.hook.xposed.XC_MethodHook
import com.originband.link.hook.xposed.XposedBridge
import com.originband.link.hook.xposed.XposedHelpers
import com.originband.link.integration.CredentialProvider
import java.util.Locale
import java.util.concurrent.ThreadPoolExecutor

/** Analysis-only, explicit-window API capture for the already selected wearable. */
object ProtocolCaptureHook {
    @JvmStatic
    @Throws(Exception::class)
    fun install(context: Context, loader: ClassLoader?) {
        if (!BuildConfig.DEBUG) return
        val writer = HookRuntime.worker("OplusBandProtocolCapture", 64)
        val api = Class.forName("com.xiaomi.wearable.wear.api.WearApiCall", false, loader)
        val callback = Class.forName("com.xiaomi.wearable.core.ICallback", false, loader)
        val result = Class.forName("com.xiaomi.wearable.core.WearApiResult", false, loader)
        XposedBridge.hookMethod(
            api.getDeclaredMethod("call", Int::class.java, ByteArray::class.java, Boolean::class.java, callback, Int::class.java),
            object : XC_MethodHook() {
                protected override fun beforeHookedMethod(param: MethodHookParam) {
                    val request = authorizedRequest(context, param.thisObject) ?: return
                    val meta = Bundle()
                    meta.putInt("callArgument0", param.args[0] as Int)
                    meta.putBoolean("responseRequested", param.args[2] as Boolean)
                    capture(context, writer, request, "tx", param.args[1] as? ByteArray, meta)
                }
            },
        )
        XposedBridge.hookMethod(
            api.getDeclaredMethod("massCall", Int::class.java, Int::class.java, ByteArray::class.java, Boolean::class.java, callback, Int::class.java),
            object : XC_MethodHook() {
                protected override fun beforeHookedMethod(param: MethodHookParam) {
                    val request = authorizedRequest(context, param.thisObject) ?: return
                    val meta = Bundle()
                    meta.putInt("callArgument0", param.args[1] as Int)
                    meta.putInt("massChannel", param.args[0] as Int)
                    meta.putBoolean("responseRequested", param.args[3] as Boolean)
                    capture(context, writer, request, "tx", param.args[2] as? ByteArray, meta)
                }
            },
        )
        XposedBridge.hookMethod(
            api.getDeclaredMethod("callbackClient", callback, result),
            object : XC_MethodHook() {
                protected override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val request = authorizedRequest(context, param.thisObject) ?: return
                        val res = checkNotNull(param.args[1])
                        val meta = Bundle()
                        meta.putInt("resultCode", XposedHelpers.callMethod(res, "a") as Int)
                        capture(
                            context,
                            writer,
                            request,
                            "rx",
                            XposedHelpers.callMethod(res, "c") as? ByteArray,
                            meta,
                        )
                    } catch (_: Throwable) {
                        HookRuntime.log("PROTOCOL_RESPONSE_CAPTURE_FAILED")
                    }
                }
            },
        )
        XposedBridge.hookMethod(
            api.getDeclaredMethod("handleData", Int::class.java, ByteArray::class.java),
            object : XC_MethodHook() {
                protected override fun beforeHookedMethod(param: MethodHookParam) {
                    val request = authorizedRequest(context, param.thisObject) ?: return
                    val meta = Bundle()
                    meta.putInt("callArgument0", param.args[0] as Int)
                    capture(context, writer, request, "event", param.args[1] as? ByteArray, meta)
                }
            },
        )
    }

    private fun authorizedRequest(context: Context, api: Any?): Bundle? {
        return try {
            val request = context.contentResolver.call(CredentialProvider.URI, "getCaptureRequest", null, null)
            if (request == null || "PROTOCOL_CAPTURE_OPEN" != request.getString("status")) return null
            val apiObj = checkNotNull(api)
            val info = checkNotNull(XposedHelpers.callMethod(apiObj, "getDeviceInfo"))
            val address = XposedHelpers.callMethod(info, "getAddress") as? String
            if (address != null && address.uppercase(Locale.ROOT) == request.getString("address")) request else null
        } catch (_: Throwable) {
            HookRuntime.log("PROTOCOL_CAPTURE_GATE_FAILED")
            null
        }
    }

    private fun capture(
        context: Context,
        writer: ThreadPoolExecutor,
        request: Bundle,
        direction: String,
        payload: ByteArray?,
        metadata: Bundle,
    ) {
        try {
            if (payload != null && payload.size > ProtocolCaptureStore.MAX_PAYLOAD) {
                captureFault(context, request, "CAPTURE_PAYLOAD_TOO_LARGE")
                return
            }
            metadata.putString("address", request.getString("address"))
            metadata.putString("nonce", request.getString("nonce"))
            metadata.putString("direction", direction)
            metadata.putLong("capturedAtMs", System.currentTimeMillis())
            metadata.putLong("elapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
            val snapshot = payload?.clone()
            metadata.putByteArray("payload", snapshot)
            try {
                writer.execute {
                    try {
                        context.contentResolver.call(CredentialProvider.URI, "recordPacket", null, metadata)
                    } catch (_: RuntimeException) {
                        captureFault(context, request, "CAPTURE_WRITE_REJECTED")
                    } finally {
                        snapshot?.fill(0)
                        metadata.clear()
                    }
                }
            } catch (_: RuntimeException) {
                snapshot?.fill(0)
                captureFault(context, request, "CAPTURE_QUEUE_FULL")
            }
        } catch (_: Throwable) {
            HookRuntime.log("PROTOCOL_CAPTURE_FAILED")
        }
    }

    private fun captureFault(context: Context, request: Bundle, code: String) {
        try {
            val failure = Bundle(request)
            failure.putString("failure", code)
            context.contentResolver.call(CredentialProvider.URI, "captureFault", null, failure)
        } catch (_: RuntimeException) {
            HookRuntime.log("PROTOCOL_CAPTURE_FAULT_NOT_RECORDED")
        }
        HookRuntime.log(code)
    }
}
