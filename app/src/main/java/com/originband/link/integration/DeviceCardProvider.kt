// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.UserManager
import com.originband.link.data.BandCatalog
import com.originband.link.data.DeviceImageStore
import com.originband.link.data.LocalPrefs
import com.originband.link.data.RawFitnessFileStore
import com.originband.link.host.HostIdentity
import com.originband.link.service.BandLiveService
import com.originband.link.service.HostKeepAlive
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException

/** Device-center projection and authenticated bridge commands. */
class DeviceCardProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun requireReader() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == 0 || uid == 2000) return // self, root, developer shell
        if (!uidHas(HOST) && !uidHas(HEALTH)) throw SecurityException("DEVICE_CARD_CALLER_NOT_AUTHORIZED")
    }

    private fun requireSyncCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (!uidHas(HEALTH)) throw SecurityException("DEVICE_SYNC_CALLER_NOT_AUTHORIZED")
    }

    private fun uidHas(packageName: String): Boolean =
        HostIdentity.uidHas(context, Binder.getCallingUid(), packageName)

    private fun state(): LocalPrefs {
        val ctx = requireContext()
        val users = ctx.getSystemService(UserManager::class.java)
        if (users == null || !users.isUserUnlocked) throw IllegalStateException("USER_LOCKED")
        return LocalPrefs.open(ctx, "band-state")
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        requireReader()
        requireUri(uri)
        if (sortOrder != null) throw IllegalArgumentException("UNSUPPORTED_SORT_ORDER")
        var key: String? = null
        var selectionValue: String? = null
        if (selection == null) {
            if (selectionArgs != null && selectionArgs.isNotEmpty()) {
                throw IllegalArgumentException("UNEXPECTED_ARGUMENTS")
            }
        } else {
            val arg0 = selectionArgs?.takeIf { it.size == 1 }?.get(0)
                ?: throw IllegalArgumentException("INVALID_SELECTION_ARGUMENTS")
            selectionValue = arg0
            key = when (selection) {
                "device_id=?", "device_id = ?" -> "device_id"
                "device_mac=?", "device_mac = ?" -> "device_mac"
                "authority=?", "authority = ?" -> "authority"
                else -> throw IllegalArgumentException("UNSUPPORTED_SELECTION")
            }
        }
        val requested = projection?.clone() ?: COLUMNS
        for (column in requested) {
            if (!isColumn(column)) throw IllegalArgumentException("UNSUPPORTED_COLUMN")
        }
        val ctx = requireContext()
        val result = MatrixCursor(requested, 1)
        result.setNotificationUri(ctx.contentResolver, URI)
        val state = state()
        if (!state.getBoolean("registered", false)) return result
        // When Health already placed this band in device space, the bridge row stays out.
        if (spaceYielded()) return result
        val deviceId = state.getString("deviceId", "")
        if (deviceId.isBlank()) return result
        val mac = state.getString("mac", "")
        if (key != null) {
            val actual = when (key) {
                "device_id" -> deviceId
                "device_mac" -> mac
                else -> AUTHORITY
            }
            if (actual != selectionValue) return result
        }
        try {
            val data = deviceData(state, deviceId, mac, bodyIcon(state))
            val row = result.newRow()
            for (column in requested) {
                row.add(
                    when (column) {
                        "device_id" -> deviceId
                        "device_mac" -> mac
                        "device_data" -> data.toString()
                        else -> AUTHORITY
                    },
                )
            }
            return result
        } catch (failure: JSONException) {
            result.close()
            throw IllegalStateException("DEVICE_CARD_SERIALIZATION_FAILED", failure)
        }
    }

    private fun spaceYielded(): Boolean {
        return try {
            LocalPrefs.open(requireContext(), "space-card").getBoolean("yield", false)
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun getType(uri: Uri): String {
        requireReader()
        if (imagePath(uri)) return "image/png"
        requireUri(uri)
        return "vnd.android.cursor.dir/vnd.$AUTHORITY.device_info"
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!imagePath(uri)) throw FileNotFoundException("UNSUPPORTED_DEVICE_CARD_URI")
        if (!mode.startsWith("r")) throw FileNotFoundException("DEVICE_IMAGE_READ_ONLY")
        requireReader()
        val file = DeviceImageStore.file(requireContext())
            ?: throw FileNotFoundException("DEVICE_IMAGE_MISSING")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun bodyIcon(state: LocalPrefs): String {
        val body = DeviceImageStore.uri(requireContext())
        return if (body.isEmpty()) spaceIcon(state) else body
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        requireReader()
        throw SecurityException("DEVICE_CARD_READ_ONLY")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        requireReader()
        throw SecurityException("DEVICE_CARD_READ_ONLY")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        requireReader()
        throw SecurityException("DEVICE_CARD_READ_ONLY")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if ("requestSync" == method) {
            requireSyncCaller()
            val ctx = requireContext()
            val identity = Binder.clearCallingIdentity()
            return try {
                val result = Bundle()
                // Service admission checks registration/ownership without root or Bluetooth work here.
                result.putString("status", BandLiveService.requestSync(ctx))
                result
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        if ("findWatch" == method) {
            requireSyncCaller()
            val start = extras == null || extras.getBoolean("start", true)
            val ctx = requireContext()
            val identity = Binder.clearCallingIdentity()
            return try {
                val result = Bundle()
                result.putString("status", BandLiveService.findWatch(ctx, start))
                result
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        if ("spaceYield" == method) {
            requireSyncCaller()
            val yield = extras != null && extras.getBoolean("yield")
            val ctx = requireContext()
            val identity = Binder.clearCallingIdentity()
            return try {
                val space = LocalPrefs.open(ctx, "space-card")
                if (space.getBoolean("yield", false) == yield) return Bundle.EMPTY
                space.edit().putBoolean("yield", yield).commit()
                ctx.contentResolver.notifyChange(URI, null)
                Bundle.EMPTY
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        requireReader()
        if ("bandDisplay" != method) throw SecurityException("DEVICE_CARD_READ_ONLY")
        val ctx = requireContext()
        HostKeepAlive.ensureBridge(ctx)
        val state = state()
        val display = Bundle()
        val registered = state.getBoolean("registered", false)
        display.putBoolean("registered", registered)
        display.putString(
            "name",
            BandCatalog.displayName(state.getString("modelId", ""), state.getString("name", "")),
        )
        display.putString("deviceId", state.getString("deviceId", ""))
        display.putString("mac", state.getString("mac", ""))
        display.putString("modelId", state.getString("modelId", ""))
        display.putString("firmware", state.getString("verifiedFirmware", ""))
        display.putString("hardware", state.getString("verifiedHardware", ""))
        display.putBoolean("connected", registered && state.getBoolean("connected", false))
        display.putInt("battery", state.getInt("battery", -1))
        display.putBoolean("charging", state.getBoolean("charging", false))
        display.putInt("historyFiles", RawFitnessFileStore(ctx).storedFiles())
        display.putLong("stepsToday", state.getLong("stepsToday", -1))
        display.putInt("heartRate", state.getInt("heartRate", -1))
        display.putLong("registeredAtMs", state.getLong("registeredAtMs", 0))
        display.putLong("lastSyncAtMs", state.getLong("lastSyncAtMs", 0))
        display.putLong("heartRateAtMs", state.getLong("heartRateAtMs", 0))
        display.putLong("stepsAtMs", state.getLong("stepsAtMs", 0))
        display.putLong("lastUpdateMs", state.getLong("lastUpdateMs", 0))
        display.putLong("revision", state.getLong("revision", 0))
        display.putString("imageUri", DeviceImageStore.uri(ctx))
        return display
    }

    companion object {
        const val AUTHORITY = "com.originband.link.devices"

        @JvmField
        val URI: Uri = Uri.parse("content://$AUTHORITY/device_info_table_new")

        private const val HOST = HostIdentity.DEVICES_PACKAGE
        private const val HEALTH = HostIdentity.HEALTH_PACKAGE
        private val COLUMNS = arrayOf("device_id", "device_mac", "device_data", "authority")
        private val BODY_IMAGE_REGEX = Regex("miband-body-\\d+\\.png")

        /** Card hooks install in the device-space process. The host is matched by package name, so a cert rotation on a system update still injects. */
        @JvmStatic
        fun matchesInstalledHost(context: Context?): Boolean = HostIdentity.installed(context, HOST)

        private fun requireUri(uri: Uri) {
            if (URI != uri) throw IllegalArgumentException("UNSUPPORTED_DEVICE_CARD_URI")
        }

        private fun isColumn(column: String?): Boolean = column != null && COLUMNS.contains(column)

        @Throws(JSONException::class)
        private fun deviceData(
            state: LocalPrefs,
            deviceId: String,
            mac: String,
            icon: String,
        ): JSONObject {
            val connected = state.getBoolean("registered", false) && state.getBoolean("connected", false)
            val connectState = if (connected) "CONNECTED" else "DISCONNECTED"
            val battery = state.getInt("battery", -1)
            val batteries = JSONArray()
            val levels = JSONArray()
            if (battery in 0..100) {
                batteries.put(
                    JSONObject()
                        .put("batteryType", "SINGLE")
                        .put("value", battery)
                        .put("charge", state.getBoolean("charging", false)),
                )
                levels.put(battery)
            }
            val lastUpdateMs = state.getLong("lastUpdateMs", 0)
            val connection = JSONObject()
                .put("connectState", connectState)
                .put("coordinationState", "DEFAULT")
                .put("lastConnectTime", if (connected) lastUpdateMs else 0)
                .put("lastDisconnectTime", if (connected) 0 else lastUpdateMs)
            return JSONObject()
                .put("mDeviceId", deviceId)
                .put(
                    "mDeviceName",
                    BandCatalog.displayName(state.getString("modelId", ""), state.getString("name", "")),
                )
                .put("mMacAddress", mac)
                .put("modelId", NativePanel.BAND_MODEL)
                // An empty picture URL makes device space use icon_default_device, which looks like a phone.
                // A band uses the system wristband picture; a watch shape uses the watch picture.
                .put("mDeviceType", BandCatalog.spaceType(state.getString("modelId", "")))
                .put("iconUrl", icon)
                .put("mIconUrl", icon)
                .put("mBatteryInfoList", batteries)
                .put("mBatteryList", levels)
                .put("connection", connection)
                .put("mConnectState", connectState)
                .put("mAuthority", AUTHORITY)
                .put("isActive", connected)
                .put("cardStyle", 1)
                .put("timestamp", lastUpdateMs)
                .put("versionCode", 1L)
                .put("feature", 0)
                .put("linkageVersion", 0)
                .put("autoSwitch", false)
                .put("revision", state.getLong("revision", 0))
        }

        private fun spaceIcon(@Suppress("UNUSED_PARAMETER") state: LocalPrefs): String = ""

        private fun imagePath(uri: Uri?): Boolean {
            if (uri == null || AUTHORITY != uri.authority) return false
            val segments = uri.pathSegments
            if (segments.size == 1 && "device_image" == segments[0]) return true
            return segments.size == 2 && "device_image" == segments[0] &&
                BODY_IMAGE_REGEX.matches(segments[1])
        }
    }
}
