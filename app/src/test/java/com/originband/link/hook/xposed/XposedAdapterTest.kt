// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook.xposed

import io.github.libxposed.api.XposedInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Executable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class XposedAdapterTest {
    /** Records what reaches the original method. */
    private class FakeChain(
        private val result: Any?,
        private val failure: Throwable?,
        vararg args: Any?,
    ) : XposedInterface.Chain {
        private val args: Array<Any?> = Array(args.size) { args[it] }
        var proceeded: Array<Any?>? = null

        override fun getExecutable(): Executable {
            return try {
                String::class.java.getMethod("length")
            } catch (impossible: NoSuchMethodException) {
                throw AssertionError(impossible)
            }
        }

        override fun getThisObject(): Any = "self"

        override fun getArgs(): List<Any?> = args.asList()

        override fun getArg(index: Int): Any? = args[index]

        @Throws(Throwable::class)
        override fun proceed(): Any? = proceed(args)

        @Throws(Throwable::class)
        override fun proceed(next: Array<out Any?>): Any? {
            proceeded = Array(next.size) { next[it] }
            if (failure != null) throw failure
            return result
        }

        override fun proceedWith(thisObject: Any): Any? = throw UnsupportedOperationException()

        override fun proceedWith(thisObject: Any, next: Array<out Any?>): Any? =
            throw UnsupportedOperationException()
    }

    @Test
    @Throws(Throwable::class)
    fun beforeCanRewriteArgumentsAndAfterCanReplaceResult() {
        val chain = FakeChain("original", null, "a", 1)
        val result = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[0] = "b"
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                assertEquals("original", param.getResult())
                param.setResult("changed")
            }
        }.intercept(chain)
        assertEquals("changed", result)
        assertEquals(listOf("b", 1), chain.proceeded?.toList())
    }

    @Test
    @Throws(Throwable::class)
    fun setResultInBeforeSkipsOriginal() {
        val chain = FakeChain("original", null)
        val result = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.setResult(7)
            }
        }.intercept(chain)
        assertEquals(7, result)
        assertNull(chain.proceeded)
    }

    @Test
    fun hostExceptionReachesCallerAndAfterSeesIt() {
        val host = IllegalStateException("host")
        var seen = false
        try {
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    seen = param.hasThrowable() && param.getThrowable() === host
                }
            }.intercept(FakeChain(null, host))
            fail("host exception swallowed")
        } catch (thrown: Throwable) {
            assertSame(host, thrown)
        }
        assertTrue(seen)
    }

    @Test
    @Throws(Throwable::class)
    fun failingCallbacksDoNotChangeTheCall() {
        val chain = FakeChain("original", null, "a")
        val result = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.setResult("early")
                throw RuntimeException("before")
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                param.setResult("lost")
                throw RuntimeException("after")
            }
        }.intercept(chain)
        assertEquals("original", result)
        assertNotNull(chain.proceeded)
    }

    open class Base {
        @Suppress("unused")
        fun pick(value: Any?): String = "object"

        open fun pick(value: CharSequence?): String = "chars"

        @Suppress("unused")
        fun wide(value: Long): String = "long"

        @Suppress("unused")
        private var secret: Int = 3

        @Suppress("unused")
        private var wideSecret: Long = 7L

        @Suppress("unused")
        private var label: String = "old"
    }

    class Child : Base() {
        override fun pick(value: CharSequence?): String = "child-chars"
    }

    @Test
    fun callMethodPicksClosestOverloadAndSearchesSuperclasses() {
        val child = Child()
        assertEquals("child-chars", XposedHelpers.callMethod(child, "pick", "text"))
        assertEquals("object", XposedHelpers.callMethod(child, "pick", 5))
        assertEquals("long", XposedHelpers.callMethod(child, "wide", 5))
        assertEquals(3, XposedHelpers.getIntField(child, "secret"))
        XposedHelpers.setIntField(child, "secret", 9)
        assertEquals(9, XposedHelpers.getObjectField(child, "secret"))
        XposedHelpers.setLongField(child, "wideSecret", 19L)
        assertEquals(19L, XposedHelpers.getObjectField(child, "wideSecret"))
        XposedHelpers.setObjectField(child, "label", "updated")
        assertEquals("updated", XposedHelpers.getObjectField(child, "label"))
    }

    @Test
    fun callMethodWrapsHostException() {
        try {
            XposedHelpers.callMethod("abc", "charAt", 10)
            fail("expected failure")
        } catch (wrapped: XposedHelpers.InvocationTargetError) {
            assertTrue(wrapped.cause is IndexOutOfBoundsException)
        }
    }

    @Test
    fun missingMethodIsAnError() {
        try {
            XposedHelpers.callMethod("abc", "nope")
            fail("expected failure")
        } catch (expected: NoSuchMethodError) {
            assertTrue(expected.message.orEmpty().contains("nope"))
        }
    }

    /** One test, because HookRuntime state is per class loader and close() is final. */
    @Test
    @Throws(Exception::class)
    fun workerRejectsWhenFullAndDropsAfterReload() {
        val release = CountDownLatch(1)
        val worker = HookRuntime.worker("test-worker", 1)
        worker.execute {
            try {
                release.await()
            } catch (_: InterruptedException) {
            }
        }
        var rejected = false
        var i = 0
        while (i < 3 && !rejected) {
            try {
                worker.execute { }
            } catch (_: RejectedExecutionException) {
                rejected = true
            }
            i++
        }
        assertTrue("a full queue must still reject while the generation is live", rejected)
        release.countDown()

        var runs = 0
        HookRuntime.track { runs++ }
        HookRuntime.close()
        assertEquals(1, runs)
        assertTrue(worker.isShutdown)
        // Old code that is still running must not throw on a host thread.
        worker.execute { runs += 10 }
        HookRuntime.track { runs++ }
        assertEquals(2, runs)
        assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(2, runs)
    }
}
