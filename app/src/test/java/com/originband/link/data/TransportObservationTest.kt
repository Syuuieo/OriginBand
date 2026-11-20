// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportObservationTest {
    private fun verified(model: String, versionName: String): JSONObject = JSONObject()
        .put("model", model)
        .put("transport", "SPP")
        .put("versionName", versionName)
        .put("rfcommUuid", "00001101-0000-1000-8000-00805f9b34fb")
        .put("rfcommSecure", true)
        .put("officialAuthConnected", true)
        .put("appCapability", TransportObservation.APP_CAPABILITY)
        .put("authOobPresent", false)
        .put("authAppDeviceIdPresent", false)

    @Test
    fun phoneCapabilityKeepsCapturedBitsAndAddsZenRuleSync() {
        val sent = TransportObservation.withZenRuleSync(TransportObservation.APP_CAPABILITY)
        assertEquals(0, TransportObservation.APP_CAPABILITY and TransportObservation.ZEN_RULE_SYNC)
        assertEquals(TransportObservation.APP_CAPABILITY or TransportObservation.ZEN_RULE_SYNC, sent)
    }

    @Test
    fun capturedSppWearAuthAllowsLiveSession() {
        assertTrue(TransportObservation.supportsLive(verified("miwear.watch.q66cn", "3.2.15"), "miwear.watch.q66cn"))
        assertTrue(TransportObservation.supportsLive(verified("lchz.watch.m67", "1.0.50"), "lchz.watch.m67"))
        assertTrue(TransportObservation.supportsLive(verified("miwear.watch.n66cn", "2.0.0"), "miwear.watch.n66cn"))
        assertTrue(TransportObservation.supportsLive(verified("miwear.watch.o66cn", "3.0.0"), "miwear.watch.o66cn"))
        assertTrue(
            TransportObservation.supportsLive(
                JSONObject()
                    .put("model", "miwear.watch.m66")
                    .put("transport", "GATT")
                    .put("officialAuthConnected", true)
                    .put("appCapability", TransportObservation.APP_CAPABILITY)
                    .put("authOobPresent", false)
                    .put("authAppDeviceIdPresent", false),
                "miwear.watch.m66",
            ),
        )
    }

    @Test
    fun knownBandUsesDefaultsUntilACaptureExists() {
        val band11 = TransportObservation.knownProfile("miwear.watch.q66cn")
        assertTrue(TransportObservation.supportsLive(band11, "miwear.watch.q66cn"))
        assertEquals("2", TransportObservation.framingVersion(band11))
        val band8 = requireNotNull(TransportObservation.knownProfile("miwear.watch.m66"))
        assertEquals("GATT", band8.getString("transport"))
        assertTrue(TransportObservation.supportsLive(band8, "miwear.watch.m66"))
        assertNull(TransportObservation.knownProfile("other.model"))
    }

    @Test
    fun emptyMismatchedOrOobProfileBlocksLiveSession() {
        val band11 = verified("miwear.watch.q66cn", "3.2.15")
        assertFalse(TransportObservation.supportsLive(JSONObject(), "miwear.watch.q66cn"))
        assertFalse(TransportObservation.supportsLive(band11.put("officialAuthConnected", false), "miwear.watch.q66cn"))
        assertFalse(TransportObservation.supportsLive(verified("miwear.watch.q66cn", "3.2.15"), "other.model"))
        assertFalse(TransportObservation.supportsLive(null, "miwear.watch.q66cn"))
        assertFalse(
            TransportObservation.supportsLive(
                verified("lchz.watch.m67", "1.0.50").put("authOobPresent", true),
                "lchz.watch.m67",
            ),
        )
        assertFalse(
            TransportObservation.supportsLive(
                verified("lchz.watch.m67", "1.0.50").put("authAppDeviceIdPresent", true),
                "lchz.watch.m67",
            ),
        )
        assertFalse(
            TransportObservation.supportsLive(
                verified("lchz.watch.m67", "1.0.50").put("appCapability", 0),
                "lchz.watch.m67",
            ),
        )
    }

    @Test
    fun band8ProV1ObservationCompletesImportWithoutFirmware() {
        val observation = verified("lchz.watch.m67", "1.0.50")
            .put("queueClass", "com.xiaomi.wearable.transport.queue.SppTaskQueueV1")
            .put("authImplementation", "WearAuthV2")
        val binding = JSONObject()
            .put("address", "AA:BB:CC:DD:EE:FF")
            .put("model", "lchz.watch.m67")
            .put("userId", "u")
            .put("region", "CN")
            .put("token", "0123456789abcdef0123456789abcdef")
        TransportObservation.applyToBinding(binding, observation)
        assertEquals("SPP", binding.getString("observedTransport"))
        assertEquals("1", binding.getString("framingVersion"))
        assertEquals("WearAuthV2", binding.getString("authenticationBranch"))
        assertEquals("", TransportObservation.missingForLive(binding, observation))
        assertTrue(TransportObservation.supportsLive(observation, "lchz.watch.m67"))
    }

    @Test
    fun band10ProV2ObservationCompletesImportWithoutFirmware() {
        val observation = verified("miwear.watch.p67cn", "3.2.7")
            .put("queueClass", "com.xiaomi.wearable.transport.queue.SppTaskQueueV2")
            .put("authImplementation", "WearAuthV2")
        val binding = JSONObject()
            .put("address", "AA:BB:CC:DD:EE:FF")
            .put("model", "miwear.watch.p67cn")
            .put("userId", "u")
            .put("region", "CN")
            .put("token", "0123456789abcdef0123456789abcdef")
        TransportObservation.applyToBinding(binding, observation)
        assertEquals("2", binding.getString("framingVersion"))
        assertEquals("", TransportObservation.missingForLive(binding, observation))
        assertTrue(TransportObservation.supportsLive(observation, "miwear.watch.p67cn"))
    }

    @Test
    fun capturedQueueWinsOverVersionPacketMajor() {
        val v1 = verified("lchz.watch.m67", "1.0.50")
            .put("queueClass", "com.xiaomi.wearable.transport.queue.SppTaskQueueV1")
        assertTrue(TransportObservation.useV1Framing(v1, 3))
        val v2 = verified("miwear.watch.p67cn", "3.2.7")
            .put("queueClass", "com.xiaomi.wearable.transport.queue.SppTaskQueueV2")
        assertFalse(TransportObservation.useV1Framing(v2, 1))
        assertTrue(TransportObservation.useV1Framing(JSONObject(), 1))
    }
}
