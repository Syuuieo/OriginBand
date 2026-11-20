// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import android.content.Context
import com.originband.link.data.SessionLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Cgroup unfreezer guard: some OEM ROMs freeze cached processes by writing 1 to
 * `/sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze`. The process then stops running
 * until something touches one of its providers, which is why the live service can sit on a stale
 * foreground notice for a long time.
 *
 * Writing the cgroup file as root undoes each freeze a few seconds after it happens, running in
 * a separate root shell process so it keeps running while this app is frozen.
 *
 * The loop exits with this process: it polls `kill -0 <pid>` so a service restart starts a
 * fresh guard instead of leaving an orphan behind.
 */
object RootFreezeGuard {
    private const val POLL_SECONDS = 5L
    private val LOCK = Any()
    private var started = false
    private var process: Process? = null

    /** Called from the service's root worker; `su` runs off the main thread. */
    @JvmStatic
    fun start(context: Context?) {
        if (context == null) return
        val app = context.applicationContext
        val uid = app.applicationInfo.uid
        val pid = android.os.Process.myPid()
        synchronized(LOCK) {
            val existing = process
            if (started && existing != null && isAlive(existing)) return
            started = true
        }
        val script = "APP_PID=$pid; APP_UID=$uid; " +
                "while kill -0 \$APP_PID 2>/dev/null; do " +
                "for f in /sys/fs/cgroup/apps/uid_\$APP_UID/pid_*/cgroup.freeze; do " +
                "[ -f \"\$f\" ] || continue; " +
                "if [ \"\$(cat \"\$f\" 2>/dev/null)\" = 1 ]; then " +
                "echo 0 > \"\$f\" 2>/dev/null && echo thaw \$(basename \$(dirname \"\$f\")); " +
                "fi; done; sleep $POLL_SECONDS; done"
        try {
            val spawned = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true).start()
            synchronized(LOCK) { process = spawned }
            SessionLog.line(app, "FREEZE_GUARD started uid=$uid pid=$pid")
            val reader = Thread({
                try {
                    BufferedReader(
                        InputStreamReader(
                            spawned.inputStream,
                            StandardCharsets.UTF_8
                        )
                    ).use { out ->
                        while (true) {
                            val line = out.readLine() ?: break
                            if (!line.startsWith("thaw ")) continue
                            SessionLog.line(app, "FREEZE_GUARD " + line.trim())
                        }
                    }
                } catch (_: Exception) {
                    // The service stopping closes the stream; nothing left to report.
                }
            }, "band-freeze-guard")
            reader.isDaemon = true
            reader.start()
        } catch (unavailable: Exception) {
            synchronized(LOCK) {
                started = false
                process = null
            }
            SessionLog.line(app, "FREEZE_GUARD unavailable " + unavailable.javaClass.simpleName)
        }
    }

    private fun isAlive(candidate: Process): Boolean {
        return try {
            candidate.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }
}
