// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import com.originband.link.protocol.BandNotificationCommand
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.function.IntSupplier

/** In-memory, ACK-aware notification lifecycle. No offline replay or persisted content. */
class NotificationRelay(
    private val sender: Sender,
    private val payloadLimit: IntSupplier,
    private val callbacks: Executor,
) {
    fun interface Sender {
        fun send(command: XiaomiProto.Command): CompletionStage<Void?>
    }

    data class Event(
        @JvmField val packageName: String?,
        @JvmField val appName: String?,
        @JvmField val key: String?,
        @JvmField val title: String?,
        @JvmField val body: String?,
        @JvmField val publicTitle: String?,
        @JvmField val publicBody: String?,
        @JvmField val postedAtMs: Long,
        @JvmField val visibility: Int,
        @JvmField val locked: Boolean,
        @JvmField val foreground: Boolean,
        @JvmField val summary: Boolean,
        @JvmField val importance: Int,
    ) {
        fun packageName(): String? = packageName
        fun appName(): String? = appName
        fun key(): String? = key
        fun title(): String? = title
        fun body(): String? = body
        fun publicTitle(): String? = publicTitle
        fun publicBody(): String? = publicBody
        fun postedAtMs(): Long = postedAtMs
        fun visibility(): Int = visibility
        fun locked(): Boolean = locked
        fun foreground(): Boolean = foreground
        fun summary(): Boolean = summary
        fun importance(): Int = importance
    }

    companion object {
        const val CAPACITY = 256

        private fun sameContent(a: XiaomiProto.Command?, b: XiaomiProto.Command?): Boolean {
            if (a == null || b == null) return false
            val x = a.notification.notification2.notification3
            val y = b.notification.notification2.notification3
            return x.`package` == y.`package` && x.appName == y.appName &&
                x.title == y.title && x.body == y.body
        }

        /** Bounded plain text, excluding control, bidi-format and malformed surrogate code points. */
        @JvmStatic
        fun sanitize(value: CharSequence?): String {
            if (value == null) return ""
            val result = StringBuilder(minOf(value.length, 2048))
            var count = 0
            var offset = 0
            while (offset < value.length && count < 2048) {
                val cp = Character.codePointAt(value, offset)
                offset += Character.charCount(cp)
                if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() ||
                    cp in 0xd800..0xdfff
                ) {
                    continue
                }
                result.appendCodePoint(cp)
                count++
            }
            return result.toString().trim { Character.isWhitespace(it) }
        }
    }

    private class Entry(
        @JvmField val key: String?,
        @JvmField val packageName: String?,
        @JvmField val id: Int,
    ) {
        var desired: XiaomiProto.Command? = null
        var delivered: XiaomiProto.Command? = null
        var busy = false
        var removed = false
    }

    private val entries = HashMap<String?, Entry>()
    private val baseline = HashMap<String?, Long>()
    private var packages: Set<String> = emptySet()
    private var enabled = false
    private var showBody = true
    private var connected = false
    private var nextId = 1
    private var failure: String? = null

    @Synchronized
    fun configure(enabled: Boolean, packages: Set<String>, showBody: Boolean) {
        val copy = java.util.Set.copyOf(packages)
        if (this.enabled != enabled || this.packages != copy || this.showBody != showBody) {
            entries.clear()
            baseline.clear()
        }
        this.enabled = enabled
        this.packages = copy
        this.showBody = showBody
    }

    @Synchronized
    fun connected(active: Map<String, Long>) {
        entries.clear()
        baseline.clear()
        failure = null
        connected = true
        if (active.size > CAPACITY) {
            connected = false
            failure = "NOTIFICATION_BASELINE_CAPACITY"
            return
        }
        for ((key, value) in active) {
            if (baseline.size == CAPACITY) break
            baseline[key] = value
        }
    }

    @Synchronized
    fun disconnected() {
        connected = false
        entries.clear()
        baseline.clear()
    }

    @Synchronized
    fun cancelPending() {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.desired = null
            if (!entry.busy && entry.delivered == null) iterator.remove()
        }
    }

    @Synchronized
    fun lastFailureCode(): String? = failure

    @Synchronized
    fun posted(event: Event) {
        if (!NotifyAdmission.packageAllowed(packages, event.packageName) || event.foreground || event.summary ||
            event.importance <= 2 || event.visibility == -1
        ) {
            removed(event.key)
            return
        }
        val oldTime = baseline.remove(event.key)
        if (oldTime != null && oldTime == event.postedAtMs) {
            baseline[event.key] = oldTime
            return
        }
        var title = event.title
        var body = if (showBody) event.body else ""
        if (event.locked && event.visibility == 0) {
            if (event.publicTitle != null || event.publicBody != null) {
                title = event.publicTitle
                body = if (showBody) event.publicBody else ""
            } else {
                body = ""
            }
        }
        title = sanitize(title)
        body = sanitize(body)
        if (title.isBlank() && body.isBlank()) {
            removed(event.key)
            return
        }
        var entry = entries[event.key]
        if (entry == null) {
            if (entries.size >= CAPACITY) {
                failure = "NOTIFICATION_CAPACITY"
                return
            }
            // An id stays unique for this process lifetime.
            if (nextId == Int.MAX_VALUE) {
                failure = "NOTIFICATION_ID_EXHAUSTED"
                return
            }
            entry = Entry(event.key, event.packageName, nextId++)
        }
        val command = try {
            BandNotificationCommand.fitToPayload(
                BandNotificationCommand.post(
                    event.packageName,
                    event.appName,
                    event.key,
                    entry.id,
                    title,
                    body,
                    Instant.ofEpochMilli(event.postedAtMs),
                    ZoneId.systemDefault(),
                ),
                payloadLimit.asInt,
            )
        } catch (_: IllegalArgumentException) {
            failure = "NOTIFICATION_PAYLOAD_REJECTED"
            return
        }
        if (sameContent(command, entry.desired) || (!entry.busy && sameContent(command, entry.delivered))) return
        entries[event.key] = entry
        entry.desired = command
        entry.removed = false
        pump(entry)
    }

    @Synchronized
    fun removed(key: String?) {
        baseline.remove(key)
        val entry = entries[key] ?: return
        entry.desired = null
        entry.removed = true
        pump(entry)
    }

    private fun pump(entry: Entry) {
        if (!connected || !enabled || entry.busy || entries[entry.key] !== entry) return
        val clear = entry.removed
        if (clear && entry.delivered == null) {
            entries.remove(entry.key)
            return
        }
        val command = if (clear) {
            BandNotificationCommand.dismiss(entry.packageName, entry.key, entry.id)
        } else {
            val desired = entry.desired
            if (desired == null || sameContent(desired, entry.delivered)) return
            desired
        }
        entry.busy = true
        try {
            sender.send(command).whenCompleteAsync({ _, error -> completed(entry, command, clear, error) }, callbacks)
        } catch (error: RuntimeException) {
            completed(entry, command, clear, error)
        }
    }

    @Synchronized
    private fun completed(
        entry: Entry,
        command: XiaomiProto.Command,
        clear: Boolean,
        error: Throwable?,
    ) {
        if (entries[entry.key] !== entry) return // Old session or revoked settings.
        entry.busy = false
        if (error != null) {
            failure = "NOTIFICATION_TRANSPORT_FAILED"
            if (clear) {
                entries.remove(entry.key)
                return
            }
            if (entry.desired === command) entry.desired = null
        } else if (clear) {
            entry.delivered = null
            if (entry.removed) {
                entries.remove(entry.key)
                return
            }
        } else {
            entry.delivered = command
        }
        pump(entry)
        if (!entry.busy && entry.desired == null && entry.delivered == null) entries.remove(entry.key)
    }
}
