// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook

import android.content.Context
import android.os.Bundle
import com.originband.link.data.AuthToken
import com.originband.link.hook.xposed.HookRuntime
import com.originband.link.hook.xposed.XC_MethodHook
import com.originband.link.hook.xposed.XposedBridge
import com.originband.link.hook.xposed.XposedHelpers
import com.originband.link.integration.CredentialProvider
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object MiFitnessImportHook {
    @JvmStatic
    @Throws(Exception::class)
    fun install(context: Context, loader: ClassLoader?) {
        val source = Class.forName("com.xiaomi.fitness.device.manager.export.bean.WearableDeviceInfo", false, loader)
        val owner = Class.forName("com.xiaomi.fit.device.extensions.DeviceModelExtKt", false, loader)
        val convert = owner.getDeclaredMethod("convert", source)
        if (convert.returnType.name != "com.xiaomi.wearable.core.DeviceInfo") {
            throw NoSuchMethodException("HOST_VERSION_UNSUPPORTED")
        }
        val writer = HookRuntime.worker("OplusBandImport", 0)
        val pending = AtomicBoolean()
        XposedBridge.hookMethod(
            convert,
            object : XC_MethodHook() {
                protected override fun afterHookedMethod(param: MethodHookParam) {
                    val info = param.getResult()
                    if (param.hasThrowable() || info == null) return
                    if (!pending.compareAndSet(false, true)) return
                    var dispatched = false
                    try {
                        val request = context.contentResolver.call(
                            CredentialProvider.URI,
                            "getImportRequest",
                            null,
                            null,
                        )
                        if (request == null || "IMPORT_WINDOW_OPEN" != request.getString("status")) return
                        val address = XposedHelpers.callMethod(info, "getAddress") as? String
                        if (address == null || address.uppercase(Locale.ROOT) != request.getString("address")) return
                        // Only the selected device's credential getters are read.
                        val binding = Bundle()
                        binding.putString("nonce", request.getString("nonce"))
                        binding.putString("address", request.getString("address"))
                        val strings = arrayOf(
                            "did" to "getDid",
                            "model" to "getModel",
                            "productId" to "getProductId",
                            "userId" to "getUserId",
                            "region" to "getRegion",
                            "appDeviceId" to "getAppDeviceId",
                            "token" to "getToken",
                            "firmware" to "getFirmwareVersion",
                            "oob" to "getOob",
                            "deviceName" to "getDeviceName",
                        )
                        for ((key, getter) in strings) {
                            binding.putString(key, XposedHelpers.callMethod(info, getter) as? String)
                        }
                        fillTokenFromSource(binding, param.args[0], info)
                        fillAccount(binding, loader)
                        binding.putInt("type", XposedHelpers.callMethod(info, "getType") as Int)
                        binding.putInt("accessType", XposedHelpers.callMethod(info, "getAccessType") as Int)
                        val privateUUID = XposedHelpers.callMethod(info, "getPrivateUUID")
                        if (privateUUID != null) {
                            val uuids = Bundle()
                            for (key in arrayOf("fitness", "mass", "otaRX", "otaTX", "protoRX", "protoTX", "service", "voice")) {
                                val getter = "get${key[0].uppercaseChar()}${key.substring(1)}"
                                uuids.putString(key, XposedHelpers.callMethod(privateUUID, getter) as? String)
                            }
                            binding.putBundle("privateUUID", uuids)
                        }
                        writer.execute {
                            try {
                                context.contentResolver.call(CredentialProvider.URI, "importBinding", null, binding)
                            } catch (_: RuntimeException) {
                                HookRuntime.log("OplusBandBridge: IMPORT_REJECTED")
                            } finally {
                                binding.clear()
                                pending.set(false)
                            }
                        }
                        dispatched = true
                    } catch (_: Throwable) {
                        // Log the exception class. The message can carry host credentials.
                        HookRuntime.log("OplusBandBridge: IMPORT_CAPTURE_FAILED")
                    } finally {
                        if (!dispatched) pending.set(false)
                    }
                }
            },
        )
    }

    /** convert() picks one of authKey/token/appToken/encryptKey; 10 Pro often leaves getToken() empty. */
    private fun fillTokenFromSource(binding: Bundle, source: Any?, converted: Any) {
        if (AuthToken.hex32(binding.getString("token"))) return
        var device = call(source, "getDevice")
        if (device == null) device = field(source, "device")
        val detail = call(device, "getDetail")
        val token = AuthToken.firstKey(
            binding.getString("token"),
            call(converted, "getToken") as? String,
            call(detail, "getEncryptKey") as? String,
            call(detail, "getToken") as? String,
            call(detail, "getAuthKey") as? String,
            call(detail, "getAppToken") as? String,
        )
        if (AuthToken.hex32(token)) binding.putString("token", token)
    }

    /** convert() copies these from the account. Read them again when DeviceInfo left them blank. */
    private fun fillAccount(binding: Bundle, loader: ClassLoader?) {
        if (binding.getString("userId").isNullOrBlank()) {
            val userId = accountValue(
                loader,
                "com.xiaomi.fitness.account.user.UserInfoManager",
                "com.xiaomi.fitness.account.extensions.AccountManagerExtKt",
                "getUserId",
            )
            if (!userId.isNullOrBlank()) binding.putString("userId", userId)
        }
        if (binding.getString("region").isNullOrBlank()) {
            val region = accountValue(
                loader,
                "com.xiaomi.fitness.login.export.RegionManager",
                "com.xiaomi.fitness.login.export.RegionExtKt",
                "getLocalCountry",
            )
            if (!region.isNullOrBlank()) binding.putString("region", region)
        }
    }

    private fun accountValue(loader: ClassLoader?, typeName: String, extensionName: String, getter: String): String? {
        return try {
            val type = Class.forName(typeName, false, loader)
            val companion = type.getField("Companion").get(null) ?: return null
            val manager = Class.forName(extensionName, false, loader)
                .getMethod("getInstance", companion.javaClass)
                .invoke(null, companion) ?: return null
            manager.javaClass.getMethod(getter).invoke(manager) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun call(target: Any?, getter: String): Any? {
        if (target == null) return null
        return try {
            XposedHelpers.callMethod(target, getter)
        } catch (_: Throwable) {
            null
        }
    }

    private fun field(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            XposedHelpers.getObjectField(target, name)
        } catch (_: Throwable) {
            null
        }
    }
}
