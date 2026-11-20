// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import com.originband.link.host.HostIdentity

/** Verified host builds. A different installed version is a hint, not a block. */
object HostVersionNotice {
    data class Expectation(
        val label: String,
        val packageName: String,
        val verified: String,
    ) {
        fun label(): String = label
        fun packageName(): String = packageName
        fun verified(): String = verified
    }

    @JvmField
    val EXPECTATIONS: List<Expectation> = listOf(
        Expectation("小米运动健康", HostIdentity.MI_PACKAGE, "3.59.1"),
        Expectation("vivo 健康", HostIdentity.HEALTH_PACKAGE, "7.1.3.72"),
    )

    @JvmRecord
    data class Installed(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val present: Boolean,
    )

    @JvmStatic
    fun accepted(packageName: String?, versionName: String?, versionCode: Long): Boolean {
        val name = versionName ?: ""
        return when (packageName) {
            HostIdentity.MI_PACKAGE -> versionCode == 359_001L || name.startsWith("3.59.1")
            HostIdentity.HEALTH_PACKAGE -> versionCode == 71_372L || name.startsWith("7.1.3.72")
            else -> true
        }
    }

    /** Empty when every installed host matches a verified build. Missing packages are not nags. */
    @JvmStatic
    fun fingerprint(installed: List<Installed>): String {
        val text = StringBuilder()
        for (app in installed) {
            if (!app.present || accepted(app.packageName, app.versionName, app.versionCode)) continue
            if (text.isNotEmpty()) text.append('\n')
            text.append(app.packageName).append('=').append(app.versionCode)
                .append(':').append(app.versionName ?: "")
        }
        return text.toString()
    }

    @JvmStatic
    fun message(installed: List<Installed>): String {
        val text = StringBuilder("这些应用的版本和实测版本有差异，可以照常使用。实测兼容版本如下：\n")
        for (expect in EXPECTATIONS) {
            val app = find(installed, expect.packageName)
            if (app == null || !app.present || accepted(app.packageName, app.versionName, app.versionCode)) continue
            val current = if (app.versionName.isNullOrBlank()) app.versionCode.toString() else app.versionName
            text.append('\n').append(expect.label).append(" 当前 ").append(current)
                .append("，实测 ").append(expect.verified)
        }
        text.append("\n\n如遇到兼容性问题，建议核对宿主的实测版本。")
        return text.toString()
    }

    private fun find(installed: List<Installed>, packageName: String): Installed? {
        return installed.firstOrNull { packageName == it.packageName }
    }
}
