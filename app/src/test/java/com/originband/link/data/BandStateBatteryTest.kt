// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import com.originband.link.protocol.BandBatteryState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BandStateBatteryTest {
    @Test
    @Throws(Exception::class)
    fun unknownStatusClearsStaleChargingWhileUpdatingBattery() {
        for (status in arrayOf<Int?>(null, 0, 9)) {
            val stored = persist(
                JSONObject().put("charging", true).put("chargerState", 1)
                    .put("battery", 10).put("connected", true),
                BandBatteryState(72, status),
            )
            assertEquals(72, stored.getInt("battery"))
            assertTrue(stored.getBoolean("connected"))
            assertFalse(stored.has("charging"))
            assertFalse(stored.has("chargerState"))
        }
    }

    @Test
    @Throws(Exception::class)
    fun knownStatusPersistsChargingAndFullOrIdle() {
        for (status in 1..3) {
            val stored = persist(JSONObject(), BandBatteryState(100, status))
            assertEquals(status, stored.getInt("chargerState"))
            assertEquals(status == 1, stored.getBoolean("charging"))
            assertEquals(100, stored.getInt("battery"))
        }
    }

    companion object {
        @Throws(Exception::class)
        private fun persist(initial: JSONObject, battery: BandBatteryState): JSONObject {
            val file = Files.createTempFile("band-battery", ".json").toFile()
            try {
                val constructor = LocalPrefs::class.java.getDeclaredConstructor(File::class.java, JSONObject::class.java)
                constructor.isAccessible = true
                val prefs = constructor.newInstance(file, initial)
                val editor = prefs.edit()
                BandStateRepository.writeBattery(editor, battery)
                assertTrue(editor.commit())
                return LocalPrefs.readStored(file).json
            } finally {
                Files.deleteIfExists(file.toPath())
            }
        }
    }
}
