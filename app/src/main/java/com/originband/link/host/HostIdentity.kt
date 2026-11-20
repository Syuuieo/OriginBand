// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.host

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process

/** The host is identified by package name. A system update rotates the signing cert, so a pinned cert would drop the hooks. */
object HostIdentity {
    const val MI_PACKAGE = "com.mi.health"
    const val HEALTH_PACKAGE = "com.vivo.health"
    const val HEALTH_MAIN_PROCESS = "com.vivo.health"
    const val HEALTH_DEVICE_PROCESS = "com.vivo.health.deviceConn"
    const val HEALTH_PERMANENT_PROCESS = "com.vivo.health.permanent"
    const val DEVICES_PACKAGE = "com.vivo.health"
    const val CLOCK_PACKAGE = "com.android.BBKClock"
    const val HEALTH_RPC_SERVICE = ""
    const val HEALTH_NOTIFICATION_LISTENER = ""
    const val ACTION_SET_DND = "com.originband.link.SET_DND"
    const val PERMISSION_SET_DND = "com.originband.link.permission.SET_DND"

    @JvmStatic
    fun isSelf(): Boolean = Binder.getCallingUid() == Process.myUid()

    @JvmStatic
    fun requireMiCaller(context: Context?) {
        requireCaller(context, MI_PACKAGE)
    }

    @JvmStatic
    fun requireCaller(context: Context?, packageName: String?) {
        if (!uidHas(context, Binder.getCallingUid(), packageName)) {
            throw SecurityException("CALLER_NOT_AUTHORIZED")
        }
    }

    /** This UID owns {@code packageName}. A shared UID can list more than one package. */
    @JvmStatic
    fun uidHas(context: Context?, uid: Int, packageName: String?): Boolean {
        if (context == null || packageName == null) return false
        val packages = context.packageManager.getPackagesForUid(uid) ?: return false
        return packages.any { packageName == it }
    }

    @JvmStatic
    fun installed(context: Context?, packageName: String?): Boolean {
        if (context == null || packageName == null) return false
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    @JvmStatic
    fun hasHealthRpcService(): Boolean = HEALTH_RPC_SERVICE.isNotEmpty()

    @JvmStatic
    fun hasHealthNotificationListener(): Boolean = HEALTH_NOTIFICATION_LISTENER.isNotEmpty()
}
