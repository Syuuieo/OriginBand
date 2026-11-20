/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo, Yoran Vulker
 *  SPDX-License-Identifier: AGPL-3.0-or-later
 *
 *  Adapted from Gadgetbridge XiaomiAuthService.java at
 *  75f923904f8504b03fabdee0987fd1c269a92278.
 *
 *  Gadgetbridge is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published
 *  by the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Gadgetbridge is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.originband.link.protocol

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** WearAuthV2 cryptography for the non-OOB branch. Protobuf parsing and device readiness stay with the caller. */
object XiaomiSessionCrypto {
    private val AUTH_INFO = "miwear-auth".toByteArray(StandardCharsets.US_ASCII)
    private const val CCM_TAG_BYTES = 4

    /**
     * Derives an inactive session. Each input must contain exactly 16 bytes.
     * The caller retains ownership of the inputs and must clear its binding key when no longer needed.
     * Callers must reject an OOB requirement before using this non-OOB API.
     */
    @JvmStatic
    fun derive(bindingKey: ByteArray?, phoneNonce: ByteArray?, watchNonce: ByteArray?): Session {
        val key = require16(bindingKey, "bindingKey")
        val phone = require16(phoneNonce, "phoneNonce")
        val watch = require16(watchNonce, "watchNonce")
        val nonces = ByteArray(32)
        System.arraycopy(phone, 0, nonces, 0, 16)
        System.arraycopy(watch, 0, nonces, 16, 16)
        var material: ByteArray? = null
        return try {
            material = deriveMaterial(key, nonces)
            Session(material, nonces)
        } catch (e: RuntimeException) {
            nonces.fill(0)
            throw e
        } finally {
            material?.fill(0)
        }
    }

    private fun deriveMaterial(bindingKey: ByteArray, nonces: ByteArray): ByteArray {
        val extracted = hmacSha256(nonces, bindingKey)
        var first: ByteArray? = null
        var second: ByteArray? = null
        return try {
            val mac = newHmac(extracted)
            mac.update(AUTH_INFO)
            mac.update(1.toByte())
            first = mac.doFinal()
            mac.update(first)
            mac.update(AUTH_INFO)
            mac.update(2.toByte())
            second = mac.doFinal()
            // Upstream expands 64 bytes; only the first 40 bytes are used.
            val material = ByteArray(40)
            System.arraycopy(first, 0, material, 0, 32)
            System.arraycopy(second, 0, material, 32, 8)
            material
        } finally {
            extracted.fill(0)
            first?.fill(0)
            second?.fill(0)
        }
    }

    private fun require16(input: ByteArray?, name: String): ByteArray {
        if (input == null || input.size != 16) {
            throw IllegalArgumentException("$name must contain exactly 16 bytes")
        }
        return input
    }

    private fun newHmac(key: ByteArray): Mac {
        return try {
            Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(key, "HmacSHA256"))
            }
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Cannot initialize HMAC-SHA256", e)
        }
    }

    @JvmStatic
    fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray {
        return newHmac(key).doFinal(input)
    }

    @JvmStatic
    fun ccmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val ecb = newEcbCipher(SecretKeySpec(key, "AES"))
        return ccmWithCipher(ecb, encrypt = true, nonce = nonce, input = plaintext)
    }

    @JvmStatic
    fun ccmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val ecb = newEcbCipher(SecretKeySpec(key, "AES"))
        return ccmWithCipher(ecb, encrypt = false, nonce = nonce, input = ciphertext)
    }

    @JvmStatic
    fun ctrCrypt(operation: Int, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(operation, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(input)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Cannot process SPP V2 ciphertext", e)
        }
    }

    private fun newEcbCipher(keySpec: SecretKeySpec): Cipher {
        return try {
            Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, keySpec)
            }
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Cannot initialize AES-CCM cipher", e)
        }
    }

    /**
     * Pure-JCE RFC 3610 AES-CCM (T=4, Alen=0) over hardware-backed AES/ECB/NoPadding.
     * Caller guarantees single-threaded access to [ecbCipher] already initialized with the AES key.
     */
    private fun ccmWithCipher(
        ecbCipher: Cipher,
        encrypt: Boolean,
        nonce: ByteArray,
        input: ByteArray,
    ): ByteArray {
        require(nonce.size in 7..13) { "CCM nonce must be 7..13 bytes, got ${nonce.size}" }
        val l = 15 - nonce.size
        val payloadLen = if (encrypt) {
            input.size
        } else {
            if (input.size < CCM_TAG_BYTES) {
                throw IllegalStateException("Cannot decrypt V1 payload")
            }
            input.size - CCM_TAG_BYTES
        }
        if (l < 4 && (payloadLen ushr (8 * l)) != 0) {
            throw IllegalArgumentException("Payload too large for L=$l")
        }

        val block = ByteArray(16)
        val mac = ByteArray(16)
        val ctrBlock = ByteArray(16)
        val keystream = ByteArray(16)
        val s0 = ByteArray(16)
        val output = ByteArray(if (encrypt) payloadLen + CCM_TAG_BYTES else payloadLen)

        try {
            // B_0: Flags = (M' shl 3) | (L - 1), M' = (T - 2) / 2 = 1 for 4-byte tag.
            block[0] = ((1 shl 3) or (l - 1)).toByte()
            System.arraycopy(nonce, 0, block, 1, nonce.size)
            writeBigEndian(block, 1 + nonce.size, l, payloadLen.toLong())
            ecbCipher.update(block, 0, 16, mac, 0)

            // Counter template A_i: Flags = L - 1.
            ctrBlock[0] = (l - 1).toByte()
            System.arraycopy(nonce, 0, ctrBlock, 1, nonce.size)
            ctrBlock.fill(0, 1 + nonce.size, 16)
            ecbCipher.update(ctrBlock, 0, 16, s0, 0)

            var offset = 0
            var counter = 1L
            while (offset < payloadLen) {
                val chunk = minOf(16, payloadLen - offset)
                writeBigEndian(ctrBlock, 1 + nonce.size, l, counter)
                ecbCipher.update(ctrBlock, 0, 16, keystream, 0)

                if (encrypt) {
                    for (i in 0 until chunk) {
                        val p = input[offset + i]
                        mac[i] = (mac[i].toInt() xor p.toInt()).toByte()
                        output[offset + i] = (p.toInt() xor keystream[i].toInt()).toByte()
                    }
                } else {
                    for (i in 0 until chunk) {
                        val p = (input[offset + i].toInt() xor keystream[i].toInt()).toByte()
                        output[offset + i] = p
                        mac[i] = (mac[i].toInt() xor p.toInt()).toByte()
                    }
                }
                ecbCipher.update(mac, 0, 16, mac, 0)
                offset += chunk
                counter++
            }

            if (encrypt) {
                for (i in 0 until CCM_TAG_BYTES) {
                    output[payloadLen + i] = (mac[i].toInt() xor s0[i].toInt()).toByte()
                }
                return output
            }

            val rxTag = ByteArray(CCM_TAG_BYTES)
            val calcTag = ByteArray(CCM_TAG_BYTES)
            for (i in 0 until CCM_TAG_BYTES) {
                rxTag[i] = (input[payloadLen + i].toInt() xor s0[i].toInt()).toByte()
                calcTag[i] = mac[i]
            }
            val valid = MessageDigest.isEqual(calcTag, rxTag)
            rxTag.fill(0)
            calcTag.fill(0)
            if (!valid) {
                output.fill(0)
                throw IllegalStateException("Cannot decrypt V1 payload")
            }
            return output
        } catch (e: GeneralSecurityException) {
            output.fill(0)
            throw IllegalStateException(
                if (encrypt) "Cannot encrypt phone information" else "Cannot decrypt V1 payload",
                e,
            )
        } finally {
            block.fill(0)
            mac.fill(0)
            ctrBlock.fill(0)
            keystream.fill(0)
            s0.fill(0)
        }
    }

    private fun writeBigEndian(dest: ByteArray, offset: Int, len: Int, value: Long) {
        var remaining = value
        for (i in len - 1 downTo 0) {
            dest[offset + i] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
    }

    class Session internal constructor(material: ByteArray, private val nonces: ByteArray) : AutoCloseable {
        private enum class State { DERIVED, WATCH_VERIFIED, ACTIVE, CLOSED }

        private val watchKey = material.copyOfRange(0, 16)
        private val phoneKey = material.copyOfRange(16, 32)
        private val phoneInfoNonce = ByteArray(12)
        private val watchIv = ByteArray(4)

        private val watchAesKey = SecretKeySpec(watchKey, "AES")
        private val phoneAesKey = SecretKeySpec(phoneKey, "AES")
        private val phoneCtrIv = IvParameterSpec(phoneKey)
        private val watchCtrIv = IvParameterSpec(watchKey)
        private val ecbCipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding")
        private val ctrCipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding")
        private var ecbKeySpec: SecretKeySpec? = null

        private var state = State.DERIVED
        private var phoneInfoEncrypted = false

        init {
            System.arraycopy(material, 32, watchIv, 0, 4)
            System.arraycopy(material, 36, phoneInfoNonce, 0, 4)
        }

        private fun prepareEcb(keySpec: SecretKeySpec): Cipher {
            if (ecbKeySpec !== keySpec) {
                ecbCipher.init(Cipher.ENCRYPT_MODE, keySpec)
                ecbKeySpec = keySpec
            }
            return ecbCipher
        }

        /** Verifies the full 32-byte proof once. A failed proof closes the session. */
        @Synchronized
        fun verifyWatchProof(proof: ByteArray?): Boolean {
            requireState(State.DERIVED)
            val mac = newHmac(watchKey)
            mac.update(nonces, 16, 16)
            mac.update(nonces, 0, 16)
            val expected = mac.doFinal()
            val verified = try {
                MessageDigest.isEqual(expected, proof)
            } finally {
                expected.fill(0)
            }
            if (verified) {
                state = State.WATCH_VERIFIED
            } else {
                close()
            }
            return verified
        }

        /** Returns HMAC(phoneKey, phoneNonce || watchNonce) after successful watch verification. */
        @Synchronized
        fun phoneProof(): ByteArray {
            requireState(State.WATCH_VERIFIED)
            return hmacSha256(phoneKey, nonces)
        }

        /**
         * Encrypts serialized phone information with a 4-byte CCM tag and no associated data.
         * The nonce is phoneIV || eight zero bytes. The caller must reuse the ciphertext for a retry.
         */
        @Synchronized
        fun encryptPhoneInfo(plaintext: ByteArray): ByteArray {
            requireState(State.WATCH_VERIFIED)
            if (phoneInfoEncrypted) throw IllegalStateException("Phone information nonce already used")
            val ciphertext = ccmWithCipher(prepareEcb(phoneAesKey), encrypt = true, nonce = phoneInfoNonce, input = plaintext)
            phoneInfoEncrypted = true
            return ciphertext
        }

        /** The caller must call this only after receiving a successful device authentication confirmation. */
        @Synchronized
        fun activate() {
            requireState(State.WATCH_VERIFIED)
            if (!phoneInfoEncrypted) throw IllegalStateException("Phone confirmation has not been constructed")
            state = State.ACTIVE
        }

        /** SPP V2 resets CTR for each message and uses the phone key itself as the IV. */
        @Synchronized
        fun encryptV2(plaintext: ByteArray): ByteArray {
            requireState(State.ACTIVE)
            return try {
                ctrCipher.init(Cipher.ENCRYPT_MODE, phoneAesKey, phoneCtrIv)
                ctrCipher.doFinal(plaintext)
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("Cannot process SPP V2 ciphertext", e)
            }
        }

        /** SPP V2 uses the watch key as the IV. CTR leaves the ciphertext unauthenticated. */
        @Synchronized
        fun decryptV2(ciphertext: ByteArray): ByteArray {
            requireState(State.ACTIVE)
            return try {
                ctrCipher.init(Cipher.DECRYPT_MODE, watchAesKey, watchCtrIv)
                ctrCipher.doFinal(ciphertext)
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("Cannot process SPP V2 ciphertext", e)
            }
        }

        /** SPP V1 / BLE V1 encrypt with CCM. Counter 0 is the phone-info nonce. */
        @Synchronized
        fun encryptV1(plaintext: ByteArray, counter: Int): ByteArray {
            requireState(if (counter == 0) State.WATCH_VERIFIED else State.ACTIVE)
            return ccmWithCipher(prepareEcb(phoneAesKey), encrypt = true, nonce = v1Nonce(phoneInfoNonce, counter), input = plaintext)
        }

        /** SPP V1 / BLE V1 decrypt with the watch CCM IV and counter 0. */
        @Synchronized
        fun decryptV1(ciphertext: ByteArray): ByteArray {
            requireState(State.ACTIVE)
            return ccmWithCipher(prepareEcb(watchAesKey), encrypt = false, nonce = v1Nonce(watchIv, 0), input = ciphertext)
        }

        private fun v1Nonce(iv: ByteArray, counter: Int): ByteArray {
            val nonce = ByteArray(12)
            System.arraycopy(iv, 0, nonce, 0, 4)
            nonce[8] = counter.toByte()
            nonce[9] = (counter shr 8).toByte()
            nonce[10] = (counter shr 16).toByte()
            nonce[11] = (counter shr 24).toByte()
            return nonce
        }

        private fun requireState(required: State) {
            if (state != required) {
                throw IllegalStateException("Session is $state; required $required")
            }
        }

        /** Clears arrays owned by this session. Caller-owned inputs and returned arrays stay with the caller. */
        @Synchronized
        override fun close() {
            watchKey.fill(0)
            phoneKey.fill(0)
            phoneInfoNonce.fill(0)
            watchIv.fill(0)
            nonces.fill(0)
            ecbKeySpec = null
            state = State.CLOSED
        }
    }
}
