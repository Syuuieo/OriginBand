// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Watch pixel layouts from the icon request. Pixels are packed ARGB, row by row. */
object NotificationIconPixels {
    const val RGB_565_LE = 0
    const val RGB_565_BE = 1
    const val XRGB_8888_LE = 2
    const val ARGB_8888_LE = 3
    const val ARGB_8565_LE = 7
    const val ABGR_8565_LE = 8

    @JvmStatic
    fun encode(argb: IntArray?, width: Int, height: Int, format: Int): ByteArray {
        if (width < 1 || height < 1 || width > 256 || height > 256 ||
            argb == null || argb.size != width * height
        ) {
            throw IllegalArgumentException("ICON_BITMAP")
        }
        return when (format) {
            RGB_565_LE -> rgb565(argb, ByteOrder.LITTLE_ENDIAN)
            RGB_565_BE -> rgb565(argb, ByteOrder.BIG_ENDIAN)
            XRGB_8888_LE, ARGB_8888_LE -> argb8888(argb)
            ARGB_8565_LE -> rgb8565(argb, swapRedBlue = false)
            ABGR_8565_LE -> rgb8565(argb, swapRedBlue = true)
            else -> throw IllegalArgumentException("ICON_PIXEL_FORMAT")
        }
    }

    private fun rgb565(argb: IntArray, order: ByteOrder): ByteArray {
        val buffer = ByteBuffer.allocate(argb.size * 2).order(order)
        for (pixel in argb) {
            val red = (pixel shr 16) and 0xff
            val green = (pixel shr 8) and 0xff
            val blue = pixel and 0xff
            val packed = ((red shr 3) shl 11) or ((green shr 2) shl 5) or (blue shr 3)
            buffer.putShort(packed.toShort())
        }
        return buffer.array()
    }

    private fun argb8888(argb: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(argb.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (pixel in argb) buffer.putInt(pixel)
        return buffer.array()
    }

    private fun rgb8565(argb: IntArray, swapRedBlue: Boolean): ByteArray {
        val buffer = ByteBuffer.allocate(argb.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        for (pixel in argb) {
            val alpha = (pixel shr 24) and 0xff
            val red = (pixel shr 19) and 0x1f
            val green = (pixel shr 10) and 0x3f
            val blue = (pixel shr 3) and 0x1f
            val packed = ((if (swapRedBlue) blue else red) shl 11) or
                (green shl 5) or
                (if (swapRedBlue) red else blue)
            buffer.putShort(packed.toShort())
            buffer.put(alpha.toByte())
        }
        return buffer.array()
    }
}
