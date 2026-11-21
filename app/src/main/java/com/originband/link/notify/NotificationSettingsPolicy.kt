// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

/** Pure allowlist and summary formatting rules for notification, call, and music settings UI. */
object NotificationSettingsPolicy {
    @JvmField
    val POPULAR_PACKAGES: List<String> = listOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.android.mms",
        "com.vivo.message",
        "com.alibaba.android.rimet",
        "com.ss.android.lark",
        "com.tencent.wework",
        "com.eg.android.AlipayGphone",
    )

    @JvmStatic
    fun popularInstalled(installedPackages: Set<String>): Set<String> {
        val result = LinkedHashSet<String>()
        for (pkg in POPULAR_PACKAGES) {
            if (installedPackages.contains(pkg)) result.add(pkg)
        }
        return result
    }

    @JvmStatic
    fun togglePackage(
        current: Set<String>,
        packageName: String,
        selected: Boolean,
    ): Set<String> {
        val updated = LinkedHashSet(current)
        val clean = packageName.trim()
        if (clean.isEmpty()) return updated
        if (selected) updated.add(clean) else updated.remove(clean)
        return updated
    }

    @JvmStatic
    fun allowlistSummary(selectedCount: Int): String {
        if (selectedCount <= 0) return "全部应用"
        return "已选 $selectedCount 个应用"
    }

    @JvmStatic
    fun homeSummary(
        listenerGranted: Boolean,
        notifyEnabled: Boolean,
        callsEnabled: Boolean,
        musicEnabled: Boolean,
    ): String {
        if (!listenerGranted) return "待开启通知使用权"
        val active = ArrayList<String>(3)
        if (notifyEnabled) active.add("通知")
        if (callsEnabled) active.add("来电")
        if (musicEnabled) active.add("音乐")
        if (active.isEmpty()) return "按需开启通知、来电与音乐联动"
        return active.joinToString(" · ") + " 已开启"
    }
}
