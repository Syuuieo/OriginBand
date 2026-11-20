// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

/** Content rules Health applies in {@code BaseInterceptWorker.filterAdaptation}. */
object HealthContentFilter {
    private const val ONGOING_OR_NO_CLEAR = 0x22
    private const val GROUP_SUMMARY = 0x200
    private const val FOREGROUND_SERVICE = 0x40
    private const val MEDIA = "android.app.Notification\$MediaStyle"

    /** @return null when Health would keep the notification, otherwise {@code content-*} */
    @JvmStatic
    fun blockReason(
        extrasMissing: Boolean,
        template: String?,
        flags: Int,
        group: String?,
        category: String?,
        progressMax: Int,
        tag: String?,
        key: String?,
        title: String?,
        text: String?,
        channelId: String?,
        packageName: String?,
        mms: Boolean,
    ): String? {
        if (extrasMissing) return "content-empty"
        if (MEDIA == template) return "content-media"
        if ((flags and ONGOING_OR_NO_CLEAR) != 0) return "content-ongoing"
        if ((flags and FOREGROUND_SERVICE) != 0) return "content-foreground"
        if ("progress" == category || progressMax > 0) return "content-progress"
        if (contains(tag, "ranker_group") || contains(key, "ranker_group")) return "content-ranker"
        if (group != null && (flags and GROUP_SUMMARY) != 0) return "content-summary"
        if (blank(title) && blank(text)) return "content-blank"
        if (mms && blank(text)) return "content-mms"
        if (filteredChannel(packageName, channelId)) return "content-channel"
        return null
    }

    private fun filteredChannel(packageName: String?, channelId: String?): Boolean {
        if (channelId.isNullOrBlank()) return false
        if ("com.tencent.mm" == packageName && "reminder_channel_id" == channelId) return true
        if ("com.teamtalk.im" != packageName) return false
        return channelId.startsWith("yzj_notification_channel_update_app") ||
            channelId.startsWith("yzj_notification_channel_others") ||
            channelId.startsWith("com.oppo.im_client")
    }

    private fun contains(value: String?, needle: String): Boolean = value != null && value.contains(needle)

    private fun blank(value: String?): Boolean = value.isNullOrBlank()
}
