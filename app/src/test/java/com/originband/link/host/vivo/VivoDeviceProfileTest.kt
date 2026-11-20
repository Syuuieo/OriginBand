// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.host.vivo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VivoDeviceProfileTest {
    @Test
    fun mapsRectangularAndRoundProductIds() {
        assertEquals(VivoDeviceProfile.PRODUCT_ID_RECT, VivoDeviceProfile.productId("miwear.watch.n66"))
        assertEquals(VivoDeviceProfile.PRODUCT_ID_RECT, VivoDeviceProfile.productId("miwear.watch.n67"))
        assertEquals(VivoDeviceProfile.PRODUCT_ID_RECT, VivoDeviceProfile.productId("hqbd3.watch.l67"))
        assertEquals(VivoDeviceProfile.PRODUCT_ID_RECT, VivoDeviceProfile.productId("miwear.watch.o65"))
        assertEquals(VivoDeviceProfile.PRODUCT_ID_RECT, VivoDeviceProfile.productId(""))

        assertEquals(VivoDeviceProfile.PRODUCT_ID_ROUND, VivoDeviceProfile.productId("mijia.watch.n62"))
        assertEquals(VivoDeviceProfile.PRODUCT_ID_ROUND, VivoDeviceProfile.productId("mijia.watch.o62"))
        assertEquals(VivoDeviceProfile.PRODUCT_ID_ROUND, VivoDeviceProfile.productId("miwear.watch.p62lte"))
        assertEquals(VivoDeviceProfile.PRODUCT_ID_ROUND, VivoDeviceProfile.productId("mijia.watch.l61"))
    }

    @Test
    fun normalizesMacAndDerivesStableDeviceId() {
        assertEquals("AA:BB:CC:DD:EE:FF", VivoDeviceProfile.normalizeMac("aa-bb-cc-dd-ee-ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", VivoDeviceProfile.normalizeMac("aabbccddeeff"))
        assertEquals("", VivoDeviceProfile.normalizeMac(" "))

        assertEquals("mi_aabbccddeeff", VivoDeviceProfile.stableDeviceId("", "aa:bb:cc:dd:ee:ff"))
        assertEquals("custom-id", VivoDeviceProfile.stableDeviceId(" custom-id ", "aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun mapsBatteryChargingAndSnapshotFlags() {
        assertEquals(
            VivoDeviceProfile.RAW_BATTERY_NOT_CHARGING,
            VivoDeviceProfile.rawWatchBatteryState(false, 80),
        )
        assertEquals(
            VivoDeviceProfile.RAW_BATTERY_CHARGING,
            VivoDeviceProfile.rawWatchBatteryState(true, 80),
        )
        assertEquals(
            VivoDeviceProfile.RAW_BATTERY_FULL,
            VivoDeviceProfile.rawWatchBatteryState(true, 100),
        )

        val snap = VivoDeviceProfile.Snapshot.fromValues(
            true, "", "", "11:22:33:44:55:66", "mijia.watch.n62", "2.1.0", "",
            true, 120, true, 5432L, 72, 1_750_000_000_000L,
        )
        assertTrue(snap.isUsable())
        assertTrue(snap.isRoundWatch())
        assertEquals(VivoDeviceProfile.PRODUCT_ID_ROUND, snap.productId())
        assertEquals(VivoDeviceProfile.DEVICE_TYPE_WATCH, snap.deviceType())
        assertEquals(VivoDeviceProfile.CONN_STATE_CONNECTED, snap.connState())
        assertEquals(100, snap.clampedBattery())
        assertEquals(VivoDeviceProfile.RAW_BATTERY_FULL, snap.rawWatchBatteryState())
        assertEquals("小米手表 S3", snap.displayName())
        assertEquals("mi_112233445566", snap.stableDeviceId())

        val unregistered = VivoDeviceProfile.Snapshot.fromValues(
            false, "Band", "id", "11:22:33:44:55:66", "miwear.watch.n66", "", "",
            true, -1, false, -1L, -1, 0L,
        )
        assertFalse(unregistered.isUsable())
        assertFalse(unregistered.hasKnownBattery())
        assertEquals(0, unregistered.clampedBattery())
    }
}
