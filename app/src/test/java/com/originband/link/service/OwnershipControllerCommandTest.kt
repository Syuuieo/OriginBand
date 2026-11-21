// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnershipControllerCommandTest {
    @Test
    fun allowLocalListenerCommandTargetsBandNotificationListener() {
        assertEquals(
            "cmd notification allow_listener com.originband.link/com.originband.link.notify.BandNotificationListener",
            OwnershipController.allowLocalListenerCommand(),
        )
    }

    @Test
    fun grantCallPermissionsCommandsCoverRuntimeAndAppOps() {
        assertEquals(
            listOf(
                "pm grant com.originband.link android.permission.READ_PHONE_STATE",
                "pm grant com.originband.link android.permission.ANSWER_PHONE_CALLS",
                "cmd appops set com.originband.link READ_PHONE_STATE allow",
                "cmd appops set com.originband.link ANSWER_PHONE_CALLS allow",
            ),
            OwnershipController.grantCallPermissionsCommands(),
        )
    }

    @Test
    fun grantSmsPermissionCommandsCoverRuntimeAndAppOps() {
        assertEquals(
            listOf(
                "pm grant com.originband.link android.permission.SEND_SMS",
                "cmd appops set com.originband.link SEND_SMS allow",
            ),
            OwnershipController.grantSmsPermissionCommands(),
        )
    }

    @Test
    fun setMediaVolumeCommandUsesStreamMusicAndClampsNegative() {
        assertEquals(
            "cmd media_session volume --show --stream 3 --set 10",
            OwnershipController.setMediaVolumeCommand(10),
        )
        assertEquals(
            "cmd media_session volume --show --stream 3 --set 0",
            OwnershipController.setMediaVolumeCommand(-5),
        )
    }
}
