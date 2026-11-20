// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import java.util.Locale
import java.util.regex.Pattern

/**
 * Xiaomi GPS file (sport flag set, detail type 2) to the track string OPPO Health
 * draws when a band record's version is 2. Points stay WGS84; the health map
 * converts them for China because the device type is a band.
 */
object SportGpsParser {
    private const val MAX_POINTS = 1500
    private val COMMA_SPLIT = Pattern.compile(",")

    @JvmStatic
    fun parse(bytes: ByteArray?): Track {
        if (bytes == null || bytes.size < 16) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val descriptor = bytes[6].toInt() and 0xff
        if ((descriptor and 0x80) == 0 || (descriptor and 3) != 2) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val activity = (descriptor and 0x7f) ushr 2
        if (activity != 22 && activity != 23) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val version = bytes[5].toInt() and 0xff
        if (version == 2) return parseFixedStride(bytes, 1, 4, 2)
        val validityBytes = if (version >= 5) 2 else 1
        if (version < 3 || version > 5) throw unsupported("UNSUPPORTED_SPORT_FILE")
        if (activity == 23 && version > 4) throw unsupported("UNSUPPORTED_SPORT_FILE")
        var cursor = 8 + validityBytes
        val end = bytes.size - 4
        if (cursor > end) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val time = flag(bytes, 0)
        val longitude = flag(bytes, 1)
        val latitude = flag(bytes, 2)
        if (!longitude || !latitude) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val accuracy = flag(bytes, 3)
        val speed = flag(bytes, 4)
        val source = flag(bytes, 5)
        val altitude = flag(bytes, 6)
        val hdop = flag(bytes, 7)
        val runway = version >= 5 && flag(bytes, 8)
        val stride = (if (time) 4 else 0) + 8 + (if (accuracy) 4 else 0) + (if (speed || source) 2 else 0) +
            (if (altitude) 4 else 0) + (if (hdop) 4 else 0) + (if (runway) 1 else 0)
        var count = Int.MAX_VALUE
        if (version >= 4) {
            if (cursor > end - 4) throw unsupported("UNSUPPORTED_SPORT_FILE")
            count = leInt(bytes, cursor)
            cursor += 4
            if (count < 0 || count > 100_000) throw unsupported("UNSUPPORTED_SPORT_FILE")
        }
        val headerMs = Integer.toUnsignedLong(leInt(bytes, 0)) * 1000L
        val points = ArrayList<DoubleArray>()
        var first = 0L
        var last = 0L
        var i = 0
        while (i < count && cursor <= end - stride) {
            i++
            var pointMs = headerMs
            var at = cursor
            if (time) {
                pointMs = Integer.toUnsignedLong(leInt(bytes, at)) * 1000L
                at += 4
            }
            val lon = leFloat(bytes, at)
            at += 4
            val lat = leFloat(bytes, at)
            cursor += stride
            if (!lat.isFinite() || !lon.isFinite() || lat < -90f || lat > 90f || lon < -180f || lon > 180f) {
                continue
            }
            if (lat == 0f && lon == 0f) continue
            if (points.isEmpty()) first = pointMs
            last = pointMs
            points.add(doubleArrayOf(lat.toDouble(), lon.toDouble(), pointMs.toDouble()))
        }
        if (points.size < 2) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val start = if (headerMs > 0L) headerMs else first
        val stop = maxOf(start + 1000L, maxOf(last, first))
        val kept = downsample(points)
        return Track(start, stop, kept.size, payload(kept))
    }

    /**
     * Band 8 Pro GPS v2. One flag byte, then points until the CRC.
     * After time, longitude and latitude the tail is a speed float in metres per
     * second and a uint16. The top three flag bits are set on a real file and are not more fields.
     */
    private fun parseFixedStride(
        bytes: ByteArray,
        validityBytes: Int,
        speedBytes: Int,
        tailBytes: Int,
    ): Track {
        val end = bytes.size - 4
        var cursor = 8 + validityBytes
        if (cursor > end) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val time = flag(bytes, 0)
        if (!flag(bytes, 1) || !flag(bytes, 2)) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val speed = flag(bytes, 3)
        val stride = (if (time) 4 else 0) + 8 + (if (speed) speedBytes else 0) +
            (if (flag(bytes, 4)) tailBytes else 0)
        val headerMs = Integer.toUnsignedLong(leInt(bytes, 0)) * 1000L
        val points = ArrayList<DoubleArray>()
        var first = 0L
        var last = 0L
        while (cursor <= end - stride) {
            var pointMs = headerMs
            var at = cursor
            if (time) {
                pointMs = Integer.toUnsignedLong(leInt(bytes, at)) * 1000L
                at += 4
            }
            val lon = leFloat(bytes, at)
            at += 4
            val lat = leFloat(bytes, at)
            at += 4
            var metersPerSecond = 0.0
            if (speed && speedBytes == 4) {
                val reading = leFloat(bytes, at)
                if (reading.isFinite() && reading > 0f && reading < 80f) {
                    metersPerSecond = reading.toDouble()
                }
            }
            cursor += stride
            if (!lat.isFinite() || !lon.isFinite() || lat < -90f || lat > 90f || lon < -180f || lon > 180f) {
                continue
            }
            if (lat == 0f && lon == 0f) continue
            if (points.isEmpty()) first = pointMs
            last = pointMs
            points.add(doubleArrayOf(lat.toDouble(), lon.toDouble(), pointMs.toDouble(), metersPerSecond))
        }
        if (points.size < 2) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val start = if (headerMs > 0L) headerMs else first
        val stop = maxOf(start + 1000L, maxOf(last, first))
        val kept = downsample(points)
        return Track(start, stop, kept.size, payload(kept))
    }

    /**
     * Pace samples for the Health speed chart. The chart key is `pace`,
     * and each value is seconds per kilometre. A leading 2 is the field count.
     */
    @JvmStatic
    fun paceSeries(gps: String?): String? {
        if (gps == null || !gps.startsWith("5,")) return null
        val part = COMMA_SPLIT.split(gps)
        val stride = try {
            part[0].toInt()
        } catch (_: NumberFormatException) {
            return null
        }
        if (stride < 5 || (part.size - 1) % stride != 0) return null
        val speeds = DoubleArray((part.size - 1) / stride)
        var count = 0
        var i = 1 + stride
        while (i + 4 < part.size) {
            val speed = speedAt(part, i + 4)
            if (speed > 0.0) speeds[count++] = speed
            i += stride
        }
        if (count < 2) return null
        val ordered = speeds.copyOf(count)
        ordered.sort()
        // One GPS spike of 40 m/s becomes 144 km/h and stretches the chart until the ride disappears.
        val cap = maxOf(ordered[count / 2] * 4.0, 6.0)
        val body = StringBuilder("2,0,0")
        var points = 0
        i = 1 + stride
        while (i + 4 < part.size) {
            val speed = speedAt(part, i + 4)
            if (speed in 0.3..cap) {
                val pace = Math.round(1000.0 / speed).toInt()
                if (pace in 30..7200) {
                    body.append(',').append(part[i]).append(',').append(pace)
                    points++
                }
            }
            i += stride
        }
        return if (points < 2) null else body.toString()
    }

    private fun speedAt(part: Array<String>, index: Int): Double = try {
        part[index].toDouble()
    } catch (_: NumberFormatException) {
        0.0
    }

    /** Fastest plausible pace in a [paceSeries] string, or null. */
    @JvmStatic
    fun fastestPace(pace: String?): Int? {
        if (pace == null || !pace.startsWith("2,")) return null
        val part = COMMA_SPLIT.split(pace)
        var best = Int.MAX_VALUE
        var i = 4
        while (i < part.size) {
            try {
                val value = part[i].toInt()
                if (value in 30 until best) best = value
            } catch (_: NumberFormatException) {
                return null
            }
            i += 2
        }
        return if (best == Int.MAX_VALUE) null else best
    }

    /** OPPO band track: a leading 5 says each point is time, latitude, longitude, pause, speed. */
    @JvmStatic
    fun payload(points: List<DoubleArray>): String {
        val body = StringBuilder("5,0,0,0,0,0")
        for (point in points) {
            body.append(',')
                .append(point[2].toLong()).append(',')
                .append(String.format(Locale.US, "%.6f", point[0])).append(',')
                .append(String.format(Locale.US, "%.6f", point[1]))
                .append(",0,")
                .append(String.format(Locale.US, "%.2f", if (point.size > 3) point[3] else 0.0))
        }
        return body.toString()
    }

    private fun downsample(points: List<DoubleArray>): List<DoubleArray> {
        if (points.size <= MAX_POINTS) return points
        val kept = ArrayList<DoubleArray>(MAX_POINTS)
        val step = (points.size - 1) / (MAX_POINTS - 1).toDouble()
        for (i in 0 until MAX_POINTS) {
            kept.add(points[Math.round(i * step).toInt()])
        }
        return kept
    }

    private fun flag(bytes: ByteArray, index: Int): Boolean =
        (bytes[8 + index / 8].toInt() and (1 shl (7 - (index % 8)))) != 0

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun leFloat(bytes: ByteArray, offset: Int): Float =
        java.lang.Float.intBitsToFloat(leInt(bytes, offset))

    private fun unsupported(status: String): IllegalArgumentException =
        IllegalArgumentException(status)

    class Track(
        @JvmField val startMs: Long,
        @JvmField val endMs: Long,
        @JvmField val points: Int,
        @JvmField val payload: String,
    )
}
