// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.originband.link.data.SessionLog
import com.originband.link.host.HostIdentity
import com.originband.link.notify.PhoneAlarmNotice
import com.originband.link.protocol.BandAlarmCommand
import com.originband.link.service.BandLiveService

/** Clock process to bridge. Authorizes the system clock, vivo Health, and self. */
class PhoneAlarmProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!authorizedCaller()) return null
        if ("status" == method) {
            SessionLog.line(context, "CLOCK_HOOK ${extras?.getString("hook", "") ?: ""}")
            val status = Bundle()
            status.putString("status", "QUEUED")
            return status
        }
        if (extras == null) return null
        val reply = extras.getParcelable("reply", Messenger::class.java)
        if (reply != null) replyTo = reply
        val op = extras.getInt("op", -1)
        val id = extras.getInt("id", -1)
        val alertTimeSec = extras.getInt("alertTimeSec", -1)
        val label = extras.getString("label")
        val identity = Binder.clearCallingIdentity()
        return try {
            if (op == 0) PhoneAlarmNotice.noteClockRing()
            if (PhoneAlarmNotice.claim(op)) {
                SessionLog.line(context, "ALARM_PHONE op=$op id=$id")
                BandLiveService.sendSessionCommand(BandAlarmCommand.operation(op, id, alertTimeSec, label))
            }
            val result = Bundle()
            result.putString("status", "QUEUED")
            result
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun authorizedCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val ctx = context ?: return false
        return HostIdentity.uidHas(ctx, uid, HostIdentity.CLOCK_PACKAGE) ||
            HostIdentity.uidHas(ctx, uid, HostIdentity.HEALTH_PACKAGE)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        throw SecurityException("PHONE_ALARM_IPC_ONLY")
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw SecurityException("PHONE_ALARM_IPC_ONLY")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw SecurityException("PHONE_ALARM_IPC_ONLY")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        throw SecurityException("PHONE_ALARM_IPC_ONLY")
    }

    companion object {
        @Volatile
        private var replyTo: Messenger? = null

        /** Band dismissed or snoozed. Calls the messenger the clock left when it started ringing. */
        @JvmStatic
        fun offer(op: Int, id: Int) {
            if (op != 1 && op != 2) return
            val reply = replyTo
            replyTo = null
            if (reply == null) return
            val message = Message.obtain()
            message.arg1 = op
            message.arg2 = id
            try {
                reply.send(message)
            } catch (_: RemoteException) {
                Log.i("OriginBand", "CLOCK_ALARM_REPLY_DEAD")
            }
        }
    }
}
