// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiFitnessDeviceRowsTest {
    companion object {
        private const val KEY = "00112233445566778899aabbccddeeff"
    }

    @Test
    fun prefersEncryptKeyAndNormalizesMac() {
        val rows = MiFitnessDeviceRows.select(
            listOf(
                MiFitnessDeviceRows.Detail(
                    "miwear.watch.q66cn",
                    "Band 11",
                    "123",
                    "did",
                    "{\"encrypt_key\":\"$KEY\",\"token\":\"ffffffffffffffffffffffffffffffff\"," +
                        "\"mac\":\"aa-bb-cc-dd-ee-ff\",\"phone_id\":\"phone\"}",
                ),
            ),
            "",
        )
        assertEquals(1, rows.size)
        assertEquals(KEY, rows[0].token)
        assertEquals("AA:BB:CC:DD:EE:FF", rows[0].address)
        assertEquals("miwear.watch.q66cn", rows[0].model)
        assertEquals("phone", rows[0].phoneId)
    }

    @Test
    fun skipsRowsWithoutAKey() {
        val rows = MiFitnessDeviceRows.select(
            listOf(
                MiFitnessDeviceRows.Detail("mihlth.xiaomi.fitness.phone", "phone", "1", "", ""),
                MiFitnessDeviceRows.Detail("miwear.watch.q66cn", "band", "2", "d", "{\"mac\":\"AA:BB:CC:DD:EE:FF\"}"),
            ),
            "",
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun filtersToTheRequestedAddress() {
        val first = band("AA:BB:CC:DD:EE:01")
        val second = band("AA:BB:CC:DD:EE:02")
        assertEquals(2, MiFitnessDeviceRows.select(listOf(first, second), "").size)
        val chosen = MiFitnessDeviceRows.select(listOf(first, second), "aa:bb:cc:dd:ee:02")
        assertEquals(1, chosen.size)
        assertEquals("AA:BB:CC:DD:EE:02", chosen[0].address)
    }

    private fun band(mac: String): MiFitnessDeviceRows.Detail = MiFitnessDeviceRows.Detail(
        "miwear.watch.q66cn",
        "band",
        "9",
        "d",
        "{\"encrypt_key\":\"$KEY\",\"mac\":\"$mac\"}",
    )
}
