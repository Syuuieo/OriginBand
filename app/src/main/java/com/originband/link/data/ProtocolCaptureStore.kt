// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/** Bounded, local-only analysis captures. No public read IPC and no logcat payloads. */
class ProtocolCaptureStore(context: Context) : AutoCloseable {
    companion object {
        const val MAX_PAYLOAD = 65_536
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    }

    private val directory: File
    private var file: File? = null
    private var output: FileOutputStream? = null
    private var records = 0
    private var bytes = 0L

    init {
        if (context.isDeviceProtectedStorage) throw IllegalArgumentException("CE_STORAGE_REQUIRED")
        directory = File(context.noBackupFilesDir, "protocol-captures")
    }

    @Throws(Exception::class)
    fun start() {
        close()
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("CAPTURE_DIRECTORY_FAILED")
        }
        Os.chmod(directory.path, 0x1c0)
        val created = File.createTempFile("capture-", ".jsonl", directory)
        Os.chmod(created.path, 0x180)
        file = created
        output = FileOutputStream(created)
        records = 0
        bytes = 0L
    }

    @Throws(Exception::class)
    fun append(record: Bundle) {
        val out = output ?: throw IllegalStateException("CAPTURE_CLOSED")
        val payload = record.getByteArray("payload")
        val direction = record.getString("direction")
        if ((payload != null && payload.size > MAX_PAYLOAD) ||
            !record.containsKey("capturedAtMs") || !record.containsKey("elapsedRealtimeNs") ||
            !(direction == "tx" || direction == "rx" || direction == "event")
        ) {
            throw IllegalArgumentException("INVALID_CAPTURE_RECORD")
        }
        val row = JSONObject()
        row.put("direction", direction)
        row.put("capturedAtMs", record.getLong("capturedAtMs"))
        row.put("elapsedRealtimeNs", record.getLong("elapsedRealtimeNs"))
        row.put("receivedAtMs", System.currentTimeMillis())
        row.put("receivedElapsedMs", SystemClock.elapsedRealtime())
        if (record.containsKey("massChannel")) row.put("massChannel", record.getInt("massChannel"))
        if (record.containsKey("callArgument0")) row.put("callArgument0", record.getInt("callArgument0"))
        if (record.containsKey("resultCode")) row.put("resultCode", record.getInt("resultCode"))
        if (record.containsKey("responseRequested")) {
            row.put("responseRequested", record.getBoolean("responseRequested"))
        }
        row.put(
            "payloadBase64",
            if (payload == null) JSONObject.NULL else Base64.encodeToString(payload, Base64.NO_WRAP),
        )
        val line = "$row\n".toByteArray(StandardCharsets.UTF_8)
        if (records >= 4096 || bytes + line.size > MAX_FILE_BYTES) {
            close()
            throw IllegalStateException("CAPTURE_LIMIT_REACHED")
        }
        out.write(line)
        records++
        bytes += line.size
    }

    fun addStatus(status: Bundle) {
        status.putInt("captureRecords", records)
        status.putLong("captureBytes", bytes)
        file?.let { status.putString("captureFile", it.name) }
    }

    @Throws(Exception::class)
    override fun close() {
        val closing = output ?: return
        output = null
        try {
            closing.fd.sync()
        } finally {
            closing.close()
        }
    }
}
