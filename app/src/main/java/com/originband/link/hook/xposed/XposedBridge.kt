// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Modifier

/** Legacy hook entry points, installed through the libxposed 102 interceptor chain. */
object XposedBridge {
    @JvmStatic
    fun hookMethod(member: Member?, callback: XC_MethodHook?): XposedInterface.HookHandle {
        if (member !is Executable) {
            throw IllegalArgumentException("Only methods and constructors can be hooked: $member")
        }
        if (Modifier.isAbstract(member.modifiers)) {
            throw IllegalArgumentException("Cannot hook abstract methods: $member")
        }
        if (callback == null) throw IllegalArgumentException("callback")
        return HookRuntime.framework().hook(member)
            // The adapter already contains callback failures. What it throws is the host's own
            // exception, or one a callback chose with setThrowable, and must reach the caller.
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept(callback::intercept)
    }

    /** Every method declared by {@code type} with this name. Inherited methods are not included. */
    @JvmStatic
    fun hookAllMethods(
        type: Class<*>,
        name: String,
        callback: XC_MethodHook?,
    ): List<XposedInterface.HookHandle> {
        val handles = ArrayList<XposedInterface.HookHandle>()
        for (method in type.declaredMethods) {
            if (method.name == name) handles.add(hookMethod(method, callback))
        }
        return handles
    }

    @JvmStatic
    fun log(line: String?) {
        HookRuntime.log(line)
    }

    @JvmStatic
    fun log(failure: Throwable?) {
        HookRuntime.log("OplusBandBridge failure", failure)
    }
}
