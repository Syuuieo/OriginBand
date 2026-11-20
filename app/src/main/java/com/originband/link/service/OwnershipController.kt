// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import com.originband.link.data.LocalPrefs
import com.originband.link.data.SessionLog
import com.originband.link.host.HostIdentity
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Connection ownership touches only fixed package names, and it can hand the band back to the official app.
 * Shell commands are assembled in this class.
 *
 * [beginNativeSession] holds the `GATE` read lock for the whole SPP session.
 * [takeOver] waits for the write lock, and the write lock waits for that read lock, so calling
 * takeover while the session lock is held deadlocks. End the session with [endNativeSession] first.
 */
class OwnershipController(context: Context) {
    private val context: Context = context.applicationContext
    private val state: LocalPrefs = LocalPrefs.open(this.context, "ownership")

    class Failure(@JvmField val code: String) : Exception(code)

    fun mode(): String = state.getString("mode", "OFFICIAL")

    @Synchronized
    fun nativeReady(): Boolean {
        return "NATIVE" == mode() && state.getBoolean("ownsDisable", false)
                && !state.getBoolean("officialRestored", false)
                && context.getSystemService(UserManager::class.java).isUserUnlocked
    }

    /** `id -u` is 0 after the user has allowed this app in KernelSU. KernelSU has no grant dialog, so this only probes. */
    fun probeRoot(): Boolean {
        return try {
            "0" == execute("id -u", 8)
        } catch (_: Failure) {
            false
        }
    }

    @Synchronized
    @Throws(Failure::class)
    fun takeOver() {
        GATE.writeLock().lock()
        try {
            requireUnlocked()
            if (!HostIdentity.installed(context, HostIdentity.MI_PACKAGE)) {
                throw Failure("HOST_VERSION_UNSUPPORTED")
            }
            requireRoot()
            if (state.getBoolean("ownsDisable", false)) {
                if (nativeReady()) return
                throw Failure("OWNERSHIP_RECOVERY_REQUIRED")
            }
            val original = officialState()
            if (original != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                && original != PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) {
                throw Failure("OFFICIAL_ALREADY_DISABLED")
            }
            if (!state.edit().putInt("originalEnabled", original).putBoolean("transitionPending", true)
                    .putBoolean("officialRestored", false).putString("mode", "OFFICIAL").commit()
            ) {
                throw Failure("OWNERSHIP_STORAGE_FAILED")
            }
            try {
                run("am force-stop com.mi.health", "OFFICIAL_STOP_FAILED")
                run("pm disable-user --user 0 com.mi.health", "OFFICIAL_DISABLE_FAILED")
                run("am force-stop com.mi.health", "OFFICIAL_STOP_FAILED")
                if (officialState() != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    throw Failure("OFFICIAL_DISABLE_UNCONFIRMED")
                }
                ensureDoze()
                ensureStartupAllowed()
                if (!state.edit().putBoolean("ownsDisable", true).putBoolean("transitionPending", false)
                        .putString("mode", "NATIVE").commit()
                ) {
                    throw Failure("OWNERSHIP_STORAGE_FAILED")
                }
                if (officialState() != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    throw Failure("OFFICIAL_DISABLE_UNCONFIRMED")
                }
                run("am force-stop com.mi.health", "OFFICIAL_STOP_FAILED")
            } catch (error: Failure) {
                if (!rollbackPending(original)) throw Failure("OWNERSHIP_ROLLBACK_FAILED")
                throw error
            }
        } finally {
            GATE.writeLock().unlock()
        }
    }

    @Synchronized
    @Throws(Failure::class)
    fun restoreOfficial() {
        GATE.writeLock().lock()
        try {
            if (!state.getBoolean("ownsDisable", false) && !state.getBoolean("transitionPending", false)) return
            requireRoot()
            val original = state.getInt("originalEnabled", -1)
            if (original != 0 && original != 1) throw Failure("ORIGINAL_STATE_UNKNOWN")
            if (!state.getBoolean("officialRestored", false)) {
                restorePackage(original)
                if (officialState() != original) throw Failure("OFFICIAL_RESTORE_UNCONFIRMED")
                if (!state.edit().putBoolean("officialRestored", true).commit()) {
                    throw Failure("OWNERSHIP_STORAGE_FAILED")
                }
            }
            dropDoze()
            if (!state.edit().putString("mode", "OFFICIAL").putBoolean("ownsDisable", false)
                    .putBoolean("transitionPending", false).remove("originalEnabled")
                    .remove("officialRestored").remove("dozeAdded").commit()
            ) {
                throw Failure("OWNERSHIP_STORAGE_FAILED")
            }
        } finally {
            GATE.writeLock().unlock()
        }
    }

    @Synchronized
    @Throws(Failure::class)
    fun restoreIfModuleInvalid(): Boolean {
        GATE.writeLock().lock()
        try {
            if (!state.getBoolean("ownsDisable", false) && !state.getBoolean("transitionPending", false)) return false
            if (nativeReady()) return false
            restoreOfficial()
            return true
        } finally {
            GATE.writeLock().unlock()
        }
    }

    /** Rollback returns the official package to the enabled state from before takeover. On failure Xiaomi Health stays disabled, and the caller shows that result. */
    private fun rollbackPending(original: Int): Boolean {
        return try {
            restorePackage(original)
            if (officialState() != original) {
                SessionLog.line(context, "ownership rollback unconfirmed state=" + officialState())
                return false
            }
            dropDoze()
            if (!state.edit().putString("mode", "OFFICIAL").putBoolean("ownsDisable", false)
                    .putBoolean("transitionPending", false).remove("originalEnabled")
                    .remove("officialRestored").remove("dozeAdded").commit()
            ) {
                SessionLog.line(context, "ownership rollback failed OWNERSHIP_STORAGE_FAILED")
                return false
            }
            true
        } catch (failed: Failure) {
            SessionLog.line(context, "ownership rollback failed " + failed.code)
            false
        }
    }

    @Throws(Failure::class)
    private fun restorePackage(original: Int) {
        when (original) {
            0 -> run("pm default-state --user 0 com.mi.health", "OFFICIAL_RESTORE_FAILED")
            1 -> run("pm enable --user 0 com.mi.health", "OFFICIAL_RESTORE_FAILED")
            else -> throw Failure("ORIGINAL_STATE_UNKNOWN")
        }
    }

    @Throws(Failure::class)
    private fun officialState(): Int {
        return try {
            context.packageManager.getApplicationEnabledSetting(OFFICIAL)
        } catch (_: IllegalArgumentException) {
            throw Failure("OFFICIAL_APP_MISSING")
        }
    }

    @Throws(Failure::class)
    private fun requireUnlocked() {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            throw Failure("USER_LOCKED")
        }
    }

    @Throws(Failure::class)
    private fun requireRoot() {
        if ("0" != execute("id -u")) throw Failure("ROOT_REQUIRED")
    }

    /** Doze whitelist plus background execution, so a swiped task leaves the bridge running. */
    fun keepAwake() {
        if (!probeRoot()) return
        try {
            ensureDoze()
        } catch (_: Failure) {
        }
        val startup = ensureStartupAllowed()
        android.util.Log.i(
            "OplusBandBridge",
            "STARTUP_GRANT " + (startup?.replace('\n', ' ') ?: "failed")
        )
        try {
            execute("cmd appops set $BRIDGE RUN_ANY_IN_BACKGROUND allow")
            execute("cmd appops set $BRIDGE RUN_IN_BACKGROUND allow")
        } catch (_: Failure) {
        }
    }

    /**
     * Autostart and associate start grants for the bridge and host health package.
     * Returns the helper output, or null when it could not run.
     */
    fun ensureStartupAllowed(): String? {
        val command = startupGrantCommand(context.applicationInfo.sourceDir) ?: return null
        return try {
            execute(command, 20)
        } catch (_: Failure) {
            null
        }
    }

    /**
     * The doze whitelist matches a whole comma-separated package field. A line is
     * `user,package,uid` or `system-excidle,package,uid`, and system packages come first.
     * A 256-byte read used to hide the bridge package, so `contains` treated it as absent,
     * every pass ran `whitelist +`, and `dozeAdded` became true.
     * Restore then ran `whitelist -` and removed a row the user had added.
     * Bytes still waiting at the cap fail the command and leave the list as it is.
     */
    @Throws(Failure::class)
    private fun ensureDoze() {
        val list = execute("cmd deviceidle whitelist", 30, MAX_WHITELIST_OUTPUT)
        if (whitelistContains(list, BRIDGE)) return
        execute("cmd deviceidle whitelist +$BRIDGE")
        if (state.edit().putBoolean("dozeAdded", true).commit()) return
        try {
            execute("cmd deviceidle whitelist -$BRIDGE")
        } catch (_: Failure) {
            // The prefs write missed, so dozeAdded stays false and a later restore skips whitelist -.
            // This removal is the chance to take back the row just added.
        }
        throw Failure("OWNERSHIP_STORAGE_FAILED")
    }

    private fun dropDoze() {
        if (!state.getBoolean("dozeAdded", false)) return
        try {
            execute("cmd deviceidle whitelist -$BRIDGE")
        } catch (_: Failure) {
        }
    }

    @Throws(Failure::class)
    private fun run(fixedCommand: String, failureCode: String) {
        try {
            execute(fixedCommand)
        } catch (_: Failure) {
            throw Failure(failureCode)
        }
    }

    companion object {
        private const val BRIDGE = "com.originband.link"
        private const val OFFICIAL = HostIdentity.MI_PACKAGE

        /** id, pm, and the startup-grant helper all return short text. */
        const val MAX_COMMAND_OUTPUT = 4096

        /**
         * `cmd deviceidle whitelist` prints system packages first, and a device can have hundreds of lines.
         * Bytes still waiting at this limit mean the list is incomplete.
         */
        const val MAX_WHITELIST_OUTPUT = 256 * 1024
        private val GATE = ReentrantReadWriteLock(true)
        private val LINE_SPLIT = Regex("\\R")
        private val TOKEN_SPLIT = Regex("[,\\s]+")
        private val APK_PATH_REGEX = Regex("/data/app/[A-Za-z0-9._~=+/-]+/base\\.apk")

        @JvmStatic
        fun beginNativeSession() {
            GATE.readLock().lock()
        }

        @JvmStatic
        fun endNativeSession() {
            GATE.readLock().unlock()
        }

        /** Fixed class and packages. The APK path comes from PackageManager and is checked. */
        @JvmStatic
        fun startupGrantCommand(apkPath: String?): String? {
            if (apkPath == null || !APK_PATH_REGEX.matches(apkPath) || apkPath.contains("..")) return null
            return "CLASSPATH=" + apkPath + " /system/bin/app_process /system/bin " +
                    RootStartupGrant::class.java.name + " " + BRIDGE + " " + HostIdentity.HEALTH_PACKAGE
        }

        /** The package name has to be a whole comma- or whitespace-separated field, so a longer package name is not a hit. */
        @JvmStatic
        fun whitelistContains(list: String?, packageName: String?): Boolean {
            if (list == null || packageName.isNullOrEmpty()) return false
            for (line in list.split(LINE_SPLIT)) {
                for (token in line.split(TOKEN_SPLIT)) {
                    if (packageName == token) return true
                }
            }
            return false
        }

        @JvmStatic
        fun shellQuote(command: String): String {
            return "'" + command.replace("'", "'\\''") + "'"
        }

        @Throws(Failure::class)
        private fun execute(
            fixedCommand: String,
            timeoutSeconds: Int = 30,
            maxOutput: Int = MAX_COMMAND_OUTPUT
        ): String {
            var process: Process? = null
            try {
                process = ProcessBuilder("su", "-c", "/system/bin/sh -c " + shellQuote(fixedCommand))
                    .redirectErrorStream(true).start()
                if (!process.waitFor(maxOf(1, timeoutSeconds).toLong(), TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw Failure("ROOT_ACTION_TIMEOUT")
                }
                val text = readLimited(process.inputStream, maxOutput).trim()
                if (process.exitValue() != 0) throw Failure("ROOT_ACTION_FAILED")
                return text
            } catch (failure: IOException) {
                throw Failure("ROOT_ACTION_FAILED")
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw Failure("ROOT_ACTION_FAILED")
            } finally {
                process?.destroy()
            }
        }

        /** Reads until the stream ends. Bytes still waiting after maxOutput make the output incomplete for the caller. */
        @JvmStatic
        @Throws(IOException::class)
        fun readLimited(input: InputStream, maxOutput: Int): String {
            val buf = ByteArray(minOf(8192, maxOf(1, maxOutput)))
            val out = ByteArrayOutputStream()
            var total = 0
            while (total < maxOutput) {
                val n = input.read(buf, 0, minOf(buf.size, maxOutput - total))
                if (n < 0) return out.toString(StandardCharsets.UTF_8.name())
                out.write(buf, 0, n)
                total += n
            }
            if (input.read() >= 0) throw IOException("OUTPUT_LIMIT")
            return out.toString(StandardCharsets.UTF_8.name())
        }

        /** Fixed components only. Callers cannot pass a shell string. */
        @JvmStatic
        fun rootStartCommand(packageName: String?, className: String?, foreground: Boolean): String? {
            if (foreground) {
                if ("com.originband.link" != packageName
                    || "com.originband.link.service.BandLiveService" != className
                ) return null
                return "am start-foreground-service --user 0 -n $packageName/$className"
            }
            if (HostIdentity.HEALTH_PACKAGE != packageName
                || !HostIdentity.hasHealthRpcService()
                || HostIdentity.HEALTH_RPC_SERVICE != className
            ) return null
            return "am start-service --user 0 -n $packageName/$className"
        }

        /** Shell uid is allowed to call this. */
        @JvmStatic
        fun zenCommand(on: Boolean): String {
            return "cmd notification set_dnd " + if (on) "priority" else "off"
        }

        @JvmStatic
        fun allowDndCommand(): String {
            return "cmd notification allow_dnd com.originband.link"
        }

        @JvmStatic
        fun allowHealthListenerCommand(): String {
            if (!HostIdentity.hasHealthNotificationListener()) return ""
            return "cmd notification allow_listener " + HostIdentity.HEALTH_PACKAGE + "/" +
                    HostIdentity.HEALTH_NOTIFICATION_LISTENER
        }

        /** One fixed component. Shell uid may run this; the app cannot grant its own listener. */
        @JvmStatic
        fun allowHealthListener(): Boolean {
            val command = allowHealthListenerCommand()
            if (command.isEmpty()) return false
            return try {
                execute(command, 8)
                true
            } catch (_: Failure) {
                false
            }
        }

        @JvmStatic
        fun setZenMode(on: Boolean): Boolean {
            try {
                execute(allowDndCommand(), 8)
            } catch (_: Failure) {
            }
            return try {
                execute(zenCommand(on), 8)
                true
            } catch (_: Failure) {
                false
            }
        }

        @JvmStatic
        fun rootStart(packageName: String?, className: String?, foreground: Boolean): Boolean {
            val command = rootStartCommand(packageName, className, foreground) ?: return false
            return try {
                execute(command, 8)
                true
            } catch (_: Failure) {
                false
            }
        }
    }
}
