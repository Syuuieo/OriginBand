// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs as root through `app_process`. Applies standard Android doze whitelist and appops
 * background execution grants for the given packages; OriginOS-specific startup rules will be added
 * in Phase 2 after inspecting the target ROM.
 */
class RootStartupGrant private constructor() {
    companion object {
        private val PKG_REGEX = Regex("[A-Za-z0-9._]+")

        @JvmStatic
        fun main(args: Array<String>) {
            val code: Int = try {
                if (run(args)) 0 else 1
            } catch (failure: Throwable) {
                println("ERROR " + failure.javaClass.simpleName + " " + failure.message)
                2
            }
            System.out.flush()
            exitProcess(code)
        }

        @Throws(Exception::class)
        private fun run(args: Array<String>): Boolean {
            val packages = ArrayList<String>()
            for (arg in args) {
                if (PKG_REGEX.matches(arg)) packages.add(arg)
            }
            if (packages.isEmpty()) throw IllegalArgumentException("NO_PACKAGES")
            var all = true
            for (pkg in packages) {
                val doze = exec("cmd", "deviceidle", "whitelist", "+$pkg")
                val anyBg = exec("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow")
                val inBg = exec("cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow")
                val ok = doze && anyBg && inBg
                all = all and ok
                println("$pkg doze=$doze appops=${anyBg && inBg}")
            }
            return all
        }

        @JvmStatic
        fun missing(wanted: List<String>, allowed: List<String>): ArrayList<String> {
            val result = ArrayList<String>()
            for (pkg in wanted) {
                if (!allowed.contains(pkg)) result.add(pkg)
            }
            return result
        }

        @Throws(IOException::class, InterruptedException::class)
        private fun exec(vararg command: String): Boolean {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            return try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    false
                } else {
                    process.exitValue() == 0
                }
            } finally {
                process.destroy()
            }
        }
    }
}
