// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Member

/**
 * Before/after callback with the legacy XposedBridge contract, run as one libxposed interceptor.
 *
 * <p>API 102 modules cannot load {@code de.robv.android.xposed}. The hooks keep this shape so their
 * logic did not have to be rewritten: {@link #beforeHookedMethod} may change {@code args} or end the
 * call early with {@link MethodHookParam#setResult}; {@link #afterHookedMethod} sees and may replace
 * the result. A callback that throws is logged and ignored, as before.</p>
 */
open class XC_MethodHook {
    @Throws(Throwable::class)
    protected open fun beforeHookedMethod(param: MethodHookParam) {}

    @Throws(Throwable::class)
    protected open fun afterHookedMethod(param: MethodHookParam) {}

    @Throws(Throwable::class)
    fun intercept(chain: XposedInterface.Chain): Any? {
        val param = MethodHookParam(chain)
        try {
            beforeHookedMethod(param)
        } catch (failure: Throwable) {
            HookRuntime.log("HOOK_BEFORE_FAILED ${chain.executable}", failure)
            param.result = null
            param.throwable = null
            param.returnEarly = false
        }
        if (!param.returnEarly) {
            try {
                param.result = chain.proceed(param.args)
                param.throwable = null
            } catch (thrown: Throwable) {
                param.result = null
                param.throwable = thrown
            }
        }
        val lastResult = param.result
        val lastThrowable = param.throwable
        try {
            afterHookedMethod(param)
        } catch (failure: Throwable) {
            HookRuntime.log("HOOK_AFTER_FAILED ${chain.executable}", failure)
            param.result = lastResult
            param.throwable = lastThrowable
        }
        val finalThrowable = param.throwable
        if (finalThrowable != null) throw finalThrowable
        return param.result
    }

    open class MethodHookParam(chain: XposedInterface.Chain) {
        @JvmField
        val method: Member = chain.executable

        @JvmField
        val thisObject: Any? = chain.thisObject

        @JvmField
        val args: Array<Any?> = chain.args.toTypedArray()

        @JvmField
        var result: Any? = null

        @JvmField
        var throwable: Throwable? = null

        @JvmField
        var returnEarly: Boolean = false

        fun getResult(): Any? = result

        fun setResult(value: Any?) {
            result = value
            throwable = null
            returnEarly = true
        }

        fun getThrowable(): Throwable? = throwable

        fun hasThrowable(): Boolean = throwable != null

        fun setThrowable(value: Throwable?) {
            throwable = value
            result = null
            returnEarly = true
        }

        @Throws(Throwable::class)
        fun getResultOrThrowable(): Any? {
            val currentThrowable = throwable
            if (currentThrowable != null) throw currentThrowable
            return result
        }
    }
}
