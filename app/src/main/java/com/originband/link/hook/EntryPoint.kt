// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook

import android.app.Application
import android.content.Context
import com.originband.link.hook.xposed.HookRuntime
import com.originband.link.hook.xposed.XC_MethodHook
import com.originband.link.hook.xposed.XposedHelpers
import com.originband.link.host.HostIdentity
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * libxposed API 102 entry. Supports module hot reload across target host processes.
 */
class EntryPoint : XposedModule() {
    private val targets: MutableList<Target> = CopyOnWriteArrayList()
    private var processName: String? = ""

    private class Target(
        val packageName: String,
        val loader: ClassLoader,
        @Volatile var context: Context?,
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookRuntime.attach(this)
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName
        if (!handled(pkg)) return
        val target = Target(pkg, param.classLoader, null)
        targets.add(target)
        dispatch(target)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        HookRuntime.close()
        val saved = ArrayList<Array<Any?>>()
        for (target in targets) {
            saved.add(arrayOf(target.packageName, target.loader, target.context))
        }
        val state = HashMap<String, Any?>()
        state[STATE_PROCESS] = processName
        state[STATE_TARGETS] = saved
        state[STATE_CARRIED] = HashMap(HookRuntime.carriedState())
        param.setSavedInstanceState(state)
        probe("HOT_RELOAD_LEAVING targets=${saved.size}")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        HookRuntime.attach(this)
        processName = param.processName
        val handles = param.oldHookHandles
        val old = handles.size
        handles.forEach { it.unhook() }
        val state = param.savedInstanceState as? Map<*, *>
        if (state == null) {
            probe("HOT_RELOAD_WITHOUT_STATE unhooked=$old")
            return
        }
        (state[STATE_CARRIED] as? Map<*, *>)?.let { HookRuntime.restoreCarried(it) }
        val saved = state[STATE_TARGETS] as? List<*>
        if (saved != null) {
            for (entry in saved) {
                val values = entry as? Array<*> ?: continue
                if (values.size != 3) continue
                val pkg = values[0] as? String ?: continue
                val loader = values[1] as? ClassLoader ?: continue
                val target = Target(pkg, loader, values[2] as? Context)
                targets.add(target)
                try {
                    dispatch(target)
                } catch (failure: Throwable) {
                    probe("HOT_RELOAD_DISPATCH_FAILED pkg=$pkg ${failure.javaClass.simpleName}")
                }
            }
        }
        probe("HOT_RELOADED process=$processName unhooked=$old targets=${targets.size}")
    }

    private fun dispatch(load: Target) {
        val process = processName
        if (HostIdentity.CLOCK_PACKAGE == load.packageName) {
            if (clockLogged.compareAndSet(false, true)) {
                probe("VIVO_CLOCK_PACKAGE_LOADED process=$process")
            }
            return
        }
        if (HostIdentity.HEALTH_PACKAGE == load.packageName) {
            if (healthLogged.compareAndSet(false, true)) {
                probe("VIVO_HEALTH_PACKAGE_LOADED process=$process")
            }
            if (vivoGuardInstalled.compareAndSet(false, true)) {
                VivoHealthGuardHook.install(load.loader)
            }
            whenAttached(load) { context ->
                if (!vivoInstalled.compareAndSet(false, true)) return@whenAttached
                if (HostIdentity.HEALTH_DEVICE_PROCESS == process) {
                    VivoHealthDeviceHook.installInDeviceConn(context, load.loader)
                } else if (HostIdentity.HEALTH_MAIN_PROCESS == process || process.isNullOrEmpty()) {
                    VivoHealthDeviceHook.installInMain(context, load.loader)
                    VivoHealthImportHook.install(context, load.loader)
                }
            }
            return
        }
        probe("PACKAGE_LOADED pkg=${load.packageName} process=$process")
        whenAttached(load) { context ->
            if (!installed.compareAndSet(false, true)) return@whenAttached
            probe("ATTACH process=$process")
            if (!HostIdentity.installed(context, HostIdentity.MI_PACKAGE)) {
                probe("MI_PACKAGE_ABSENT")
                return@whenAttached
            }
            try {
                MiFitnessImportHook.install(context, load.loader)
                probe("OriginBand: IMPORT_HOOK_INSTALLED")
            } catch (_: Throwable) {
                probe("OriginBand: HOST_VERSION_UNSUPPORTED")
            }
            try {
                TransportProbeHook.install(context, load.loader)
            } catch (_: Throwable) {
                probe("TRANSPORT_PROBE_UNAVAILABLE")
            }
            try {
                ProtocolCaptureHook.install(context, load.loader)
            } catch (_: Throwable) {
                probe("PROTOCOL_CAPTURE_UNAVAILABLE")
            }
        }
    }

    private companion object {
        private const val STATE_PROCESS = "process"
        private const val STATE_TARGETS = "targets"
        private const val STATE_CARRIED = "carried"
        private val installed = AtomicBoolean()
        private val healthLogged = AtomicBoolean()
        private val clockLogged = AtomicBoolean()
        private val vivoGuardInstalled = AtomicBoolean()
        private val vivoInstalled = AtomicBoolean()

        private fun handled(pkg: String): Boolean {
            return HostIdentity.MI_PACKAGE == pkg ||
                HostIdentity.HEALTH_PACKAGE == pkg ||
                HostIdentity.CLOCK_PACKAGE == pkg
        }

        /** Runs now if the host Application is known, else after {@code Application.attach}. */
        private fun whenAttached(load: Target, action: (Context) -> Unit) {
            val known = load.context
            if (known != null) {
                action(known)
                return
            }
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    protected override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as? Context ?: return
                        val app = context.applicationContext ?: context
                        if (load.context == null) load.context = app
                        action(app)
                    }
                },
            )
        }

        private fun probe(line: String) {
            HookRuntime.log(line)
        }
    }
}
