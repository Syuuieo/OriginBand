// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BindingShapeTest {
    companion object {
        private const val TOKEN = "0123456789abcdef0123456789abcdef"
        private const val MAC = "AA:BB:CC:DD:EE:FF"
    }

    @Test
    fun blankUserIdStillRegisters() {
        assertNull(BindingShape.reject(binding().put("userId", "")))
    }

    @Test
    fun regionAndTokenAndAddressAreNamed() {
        assertEquals("BINDING_REGION", BindingShape.reject(binding().put("region", "")))
        assertEquals("TOKEN_ENCODING_UNSUPPORTED", BindingShape.reject(binding().put("token", "not-a-key")))
        assertEquals("BINDING_ADDRESS", BindingShape.reject(binding().put("address", "aa:bb:cc:dd:ee:ff")))
        assertEquals("UNPROVISIONED", BindingShape.reject(null))
    }

    @Test
    fun descriptionOmitsSecrets() {
        val text = BindingShape.describe(binding())
        assertFalse(text.contains(TOKEN))
        assertFalse(text.contains(MAC))
        assertFalse(text.contains("CN"))
        assertFalse(text.contains("123456"))
        assertEquals("address=ok userId=set region=set token=hex32 model=miwear.watch.n67cn", text)
    }

    @Test
    fun otherTokenReportsLengthOnly() {
        val text = BindingShape.describe(binding().put("token", "zzzz"))
        assertEquals("address=ok userId=set region=set token=other len=4 model=miwear.watch.n67cn", text)
    }

    private fun binding(): JSONObject = JSONObject()
        .put("address", MAC)
        .put("userId", "123456")
        .put("region", "CN")
        .put("token", TOKEN)
        .put("model", "miwear.watch.n67cn")
}
