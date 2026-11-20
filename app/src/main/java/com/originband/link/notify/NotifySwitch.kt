// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

/** OHealth whitelist values are Boolean on some builds and 0/1 on others. */
object NotifySwitch {
    @JvmStatic
    fun on(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> false
    }
}
