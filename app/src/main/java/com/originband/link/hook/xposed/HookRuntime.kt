// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook.xposed

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.ArrayDeque
import java.util.Deque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * State of one module generation inside one host process.
 *
 * <p>Hot reload loads a new copy of this class. Before the old copy is retired, {@link #close()}
 * stops what it started in the host: worker threads, executors, receivers, content observers, and
 * callbacks queued on the main thread. Hooks are not closed here. The framework hands their handles
 * to the new generation, which unhooks them.</p>
 */
object HookRuntime {
    const val TAG: String = "OriginBand"

    @Volatile
    private var framework: XposedInterface? = null
    private val cleanups: Deque<Runnable> = ArrayDeque()
    private val carried: MutableMap<String, Any> = HashMap()
    private var main: Handler? = null

    /** Handed to old code that is still running after a hot reload. It drops every post. */
    private var retired: Handler? = null
    private var closed: Boolean = false

    @JvmStatic
    fun attach(value: XposedInterface?) {
        framework = value
    }

    @JvmStatic
    fun framework(): XposedInterface {
        return framework ?: throw IllegalStateException("XPOSED_FRAMEWORK_NOT_ATTACHED")
    }

    @JvmStatic
    fun log(line: String?) {
        write(Log.INFO, line, null)
    }

    @JvmStatic
    fun log(line: String?, failure: Throwable?) {
        write(Log.WARN, line, failure)
    }

    /** One hook install. A missing host method is logged under the token, and the rest of the installs continue. */
    @JvmStatic
    fun tryHook(token: String?, install: ThrowingRunnable) {
        try {
            install.run()
        } catch (failure: Throwable) {
            log(token, failure)
        }
    }

    fun interface ThrowingRunnable {
        @Throws(Throwable::class)
        fun run()
    }

    /** A log failure stays inside the logger. The hook that called it keeps running. */
    private fun write(priority: Int, line: String?, failure: Throwable?) {
        val safeLine = line ?: ""
        try {
            val message = if (failure == null) safeLine else "$line\n${Log.getStackTraceString(failure)}"
            Log.println(priority, TAG, message)
        } catch (_: RuntimeException) {
            // Unit tests run without android.util.Log.
        }
        val current = framework ?: return
        try {
            if (failure == null) current.log(priority, TAG, safeLine)
            else current.log(priority, TAG, safeLine, failure)
        } catch (_: RuntimeException) {
            // The framework log is best effort.
        }
    }

    /** Main-thread handler of this generation. Its queued callbacks are dropped on reload. */
    @JvmStatic
    @Synchronized
    fun main(): Handler {
        if (closed) {
            return retired ?: object : Handler(Looper.getMainLooper()) {
                override fun sendMessageAtTime(message: Message, uptimeMillis: Long): Boolean = false
            }.also { retired = it }
        }
        return main ?: Handler(Looper.getMainLooper()).also { created ->
            main = created
            track { created.removeCallbacksAndMessages(null) }
        }
    }

    @JvmStatic
    fun thread(name: String): HandlerThread {
        val thread = HandlerThread(name)
        thread.start()
        track { thread.quit() }
        return thread
    }

    /**
     * One daemon worker thread. A full bounded queue still rejects with an exception, as before.
     * After a hot reload the executor stops taking work: running and queued tasks finish, and a
     * task submitted later is dropped. Throwing there would crash the host when old code that is
     * still running, often on this same worker, queues its next step.
     */
    @JvmStatic
    fun worker(name: String, capacity: Int): ThreadPoolExecutor {
        val queue: BlockingQueue<Runnable> =
            if (capacity > 0) ArrayBlockingQueue(capacity) else LinkedBlockingQueue()
        val executor = ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            queue,
            ThreadFactory { runnable ->
                Thread(runnable, name).apply { isDaemon = true }
            },
            RejectedExecutionHandler { task, owner ->
                if (!owner.isShutdown) {
                    throw RejectedExecutionException("Task $task rejected from $name")
                }
            },
        )
        track { executor.shutdown() }
        return executor
    }

    @JvmStatic
    fun observe(
        resolver: ContentResolver,
        uri: Uri,
        descendants: Boolean,
        observer: ContentObserver,
    ) {
        resolver.registerContentObserver(uri, descendants, observer)
        track { resolver.unregisterContentObserver(observer) }
    }

    /**
     * Registers on the next main-thread turn. A receiver registered inside {@code Application.attach}
     * is filed under the bare ContextImpl, and the platform then switches that context's outer
     * context to the Application. {@code unregisterReceiver} looks under the Application and fails
     * with "Receiver not registered", so a hot reload could not remove it.
     */
    @JvmStatic
    fun receive(
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        permission: String?,
        scheduler: Handler?,
        flags: Int,
    ) {
        main().post {
            synchronized(HookRuntime) {
                if (closed) return@post
            }
            try {
                context.registerReceiver(receiver, filter, permission, scheduler, flags)
                track { context.unregisterReceiver(receiver) }
            } catch (failure: RuntimeException) {
                log("RECEIVER_REGISTER_FAILED ${receiver.javaClass.name}", failure)
            }
        }
    }

    /** Registers a cleanup. After a hot reload it runs at once, because no later close will. */
    @JvmStatic
    fun track(cleanup: Runnable) {
        synchronized(HookRuntime) {
            if (!closed) {
                cleanups.push(cleanup)
                return
            }
        }
        try {
            cleanup.run()
        } catch (failure: Throwable) {
            log("HOT_RELOAD_LATE_CLEANUP_FAILED", failure)
        }
    }

    /**
     * Host objects that the next generation needs to resume, such as a listener the host already
     * registered. The value is a host or platform object, so the next generation can still call it.
     */
    @JvmStatic
    @Synchronized
    fun carry(key: String, hostValue: Any?) {
        if (hostValue == null) carried.remove(key)
        else carried[key] = hostValue
    }

    @JvmStatic
    @Synchronized
    fun carried(key: String): Any? = carried[key]

    @JvmStatic
    @Synchronized
    fun carriedState(): Map<String, Any> = HashMap(carried)

    @JvmStatic
    @Synchronized
    fun restoreCarried(state: Map<*, *>?) {
        if (state == null) return
        for ((key, value) in state) {
            if (key is String && value != null) {
                carried[key] = value
            }
        }
    }

    /** Stops everything this generation started. Later {@link #track} calls clean up at once. */
    @JvmStatic
    fun close() {
        val pending: Array<Runnable>
        synchronized(HookRuntime) {
            closed = true
            pending = cleanups.toTypedArray()
            cleanups.clear()
            main = null
        }
        for (cleanup in pending) {
            try {
                cleanup.run()
            } catch (failure: Throwable) {
                log("HOT_RELOAD_CLEANUP_FAILED", failure)
            }
        }
    }
}
