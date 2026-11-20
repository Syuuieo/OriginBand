// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiProductImageTest {
    @Test
    fun skuKeepsThePrefixBeforeTheSlash() {
        assertEquals("M2616B1", MiProductImage.sku("M2616B1/ABC"))
        assertEquals("M2616B1", MiProductImage.sku("  M2616B1  "))
        assertEquals("M2616B1", MiProductImage.sku("M2616B1/"))
        assertEquals("", MiProductImage.sku("/ABC"))
        assertEquals("M2616B1", MiProductImage.sku("M2616B1"))
        assertEquals("", MiProductImage.sku("has space"))
        assertEquals("", MiProductImage.sku("a".repeat(33)))
        assertEquals("", MiProductImage.sku(null))
    }

    @Test
    fun previewWithDefaultFaceWinsOverTheEmptyBody() {
        val json = product(
            "{" +
                "\"sku_big_icon\":\"{\\\"M2616B1\\\":\\\"https://cdn.example/sku.png\\\"}\"," +
                "\"big_icon_3\":\"https://cdn.example/icon3.png\"," +
                "\"big_icon\":\"https://cdn.example/icon.png\"," +
                "\"sku_big_preview\":\"{\\\"M2616B1\\\":\\\"https://cdn.example/preview-sku.png\\\"}\"," +
                "\"big_preview\":\"https://cdn.example/preview.png\"" +
                "}",
        )
        assertEquals("sku_big_preview", MiProductImage.choose(json, "miwear.watch.q66cn", "M2616B1")?.source())
        assertEquals(
            "https://cdn.example/preview-sku.png",
            MiProductImage.choose(json, "miwear.watch.q66cn", "M2616B1")?.url(),
        )
        assertEquals("big_preview", MiProductImage.choose(json, "miwear.watch.q66cn", "")?.source())
    }

    @Test
    fun missingSkuFallsThroughToBigIcon3() {
        val json = product(
            "{" +
                "\"sku_big_icon\":\"{\\\"OTHER\\\":\\\"https://cdn.example/sku.png\\\"}\"," +
                "\"big_icon_3\":\"https://cdn.example/icon3.png\"," +
                "\"big_icon\":\"https://cdn.example/icon.png\"" +
                "}",
        )
        val choice = requireNotNull(MiProductImage.choose(json, "miwear.watch.q66cn", "M2616B1"))
        assertEquals("big_icon_3", choice.source())
        assertEquals("https://cdn.example/icon3.png", choice.url())
    }

    @Test
    fun brokenSkuMapFallsThrough() {
        val json = product(
            "{" +
                "\"sku_big_icon\":\"not-json\"," +
                "\"big_icon\":\"https://cdn.example/icon.png\"" +
                "}",
        )
        assertEquals("big_icon", MiProductImage.choose(json, "miwear.watch.q66cn", "M2616B1")?.source())
    }

    @Test
    fun httpUrlIsSkipped() {
        val json = product(
            "{" +
                "\"big_icon_3\":\"http://cdn.example/icon3.png\"," +
                "\"big_icon\":\"https://cdn.example/icon.png\"" +
                "}",
        )
        assertEquals("big_icon", MiProductImage.choose(json, "miwear.watch.q66cn", "")?.source())
    }

    @Test
    fun previewIsLast() {
        val json = product("{\"big_preview\":\"https://cdn.example/preview.png\"}")
        assertEquals("big_preview", MiProductImage.choose(json, "miwear.watch.q66cn", "")?.source())
        assertEquals(4, MiProductImage.choose(json, "miwear.watch.q66cn", "")?.productType())
    }

    @Test
    fun unknownModelReturnsNull() {
        assertNull(
            MiProductImage.choose(
                product("{\"big_icon\":\"https://cdn.example/a.png\"}"),
                "other.model",
                "M2616B1",
            ),
        )
        assertNull(MiProductImage.choose("{", "miwear.watch.q66cn", ""))
        assertNull(MiProductImage.choose("{}", "", ""))
    }

    @Test
    fun nestedProductIsFound() {
        val json = "{\"payload\":{\"items\":[${product("{\"big_icon_3\":\"https://cdn.example/nested.png\"}")}]}}"
        assertEquals(
            "https://cdn.example/nested.png",
            MiProductImage.choose(json, "miwear.watch.q66cn", "")?.url(),
        )
    }

    private fun product(fields: String): String =
        "{\"dataList\":[{\"model\":\"miwear.watch.q66cn\",\"pd_type\":4,${fields.substring(1)}]}"
}
