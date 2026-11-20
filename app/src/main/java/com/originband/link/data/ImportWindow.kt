// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import java.util.Locale
import java.util.UUID

/** All calls are serialized by CredentialProvider. Process restart closes the window. */
class ImportWindow {
    private var address: String? = null
    private var nonce: String? = null
    private var deadline: Long = 0L

    fun open(selected: String?, nowMs: Long) {
        val normalized = selected?.uppercase(Locale.ROOT) ?: ""
        if (!MAC_REGEX.matches(normalized)) {
            throw IllegalArgumentException("INVALID_DEVICE_ADDRESS")
        }
        address = normalized
        nonce = UUID.randomUUID().toString()
        deadline = Math.addExact(nowMs, 120_000L)
    }

    fun isOpen(nowMs: Long): Boolean {
        if (nonce != null && nowMs >= deadline) close()
        return nonce != null
    }

    fun authorize(suppliedNonce: String?, suppliedAddress: String?, nowMs: Long) {
        if (!isOpen(nowMs) || nonce != suppliedNonce) {
            throw SecurityException("IMPORT_WINDOW_CLOSED")
        }
        if (address != suppliedAddress) {
            throw SecurityException("DEVICE_SELECTION_MISMATCH")
        }
    }

    fun address(): String? = address

    fun nonce(): String? = nonce

    fun close() {
        address = null
        nonce = null
        deadline = 0L
    }

    private companion object {
        val MAC_REGEX = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
    }
}
