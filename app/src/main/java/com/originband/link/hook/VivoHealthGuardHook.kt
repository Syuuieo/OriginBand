// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook

import com.originband.link.hook.xposed.HookRuntime
import com.originband.link.hook.xposed.XC_MethodHook
import com.originband.link.hook.xposed.XposedHelpers

/**
 * Neutralizes the non-vivo LSPAppComponentFactoryStub process-kill check in
 * com.vivo.health.app.VivoHealthApplication.q() for non-vivo test devices.
 */
object VivoHealthGuardHook {
    @JvmStatic
    fun install(loader: ClassLoader?) {
        HookRuntime.tryHook("VIVO_GUARD_HOOK_FAILED") {
            XposedHelpers.findAndHookMethod(
                "com.vivo.health.app.VivoHealthApplication",
                loader,
                "q",
                object : XC_MethodHook() {
                    protected override fun beforeHookedMethod(param: MethodHookParam) {
                        param.setResult(null)
                    }
                },
            )
        }
    }
}
