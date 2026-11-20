// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * The reflection helpers the hooks used from the legacy XposedHelpers, with the same lookup rules.
 *
 * <p>Methods and fields are searched in the class and its superclasses. A call picks the overload
 * whose parameters fit the arguments at the lowest conversion cost, as the legacy helper did.</p>
 */
object XposedHelpers {
    private val FIELDS: MutableMap<String, Field> = ConcurrentHashMap()
    private val MEMBERS: MutableMap<String, Executable> = ConcurrentHashMap()
    private val PRIMITIVE_ORDER: Array<Class<*>> = arrayOf(
        Byte::class.javaPrimitiveType!!,
        Short::class.javaPrimitiveType!!,
        Char::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!,
        Long::class.javaPrimitiveType!!,
        Float::class.javaPrimitiveType!!,
        Double::class.javaPrimitiveType!!,
    )

    class ClassNotFoundError(message: String?, cause: Throwable?) : Error(message, cause)

    /** Thrown by the call helpers. The cause is what the called method threw. */
    class InvocationTargetError(cause: Throwable?) : Error(cause)

    @JvmStatic
    fun findClass(name: String, loader: ClassLoader?): Class<*> {
        return try {
            Class.forName(name, false, loader)
        } catch (missing: ClassNotFoundException) {
            throw ClassNotFoundError(name, missing)
        }
    }

    // ---- hooks ----

    @JvmStatic
    fun findAndHookMethod(
        className: String,
        loader: ClassLoader?,
        name: String,
        vararg typesAndCallback: Any?,
    ): XposedInterface.HookHandle {
        return findAndHookMethod(findClass(className, loader), name, *typesAndCallback)
    }

    @JvmStatic
    fun findAndHookMethod(
        type: Class<*>,
        name: String,
        vararg typesAndCallback: Any?,
    ): XposedInterface.HookHandle {
        val callback = typesAndCallback.lastOrNull() as? XC_MethodHook
            ?: throw IllegalArgumentException("No callback defined")
        val rawTypes = Array(typesAndCallback.size - 1) { typesAndCallback[it] }
        val parameters = parameterTypes(type.classLoader, rawTypes)
        return XposedBridge.hookMethod(findMethodExact(type, name, *parameters), callback)
    }

    @JvmStatic
    fun findMethodExact(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
        return try {
            val method = type.getDeclaredMethod(name, *parameters)
            method.isAccessible = true
            method
        } catch (_: NoSuchMethodException) {
            throw NoSuchMethodError("${type.name}#$name${parameters.contentToString()}#exact")
        }
    }

    private fun parameterTypes(loader: ClassLoader?, types: Array<Any?>): Array<Class<*>> {
        return Array(types.size) { i ->
            when (val type = types[i]) {
                is Class<*> -> type
                is String -> findClass(type, loader)
                else -> throw IllegalArgumentException("Parameter type must be a Class or a class name: $type")
            }
        }
    }

    // ---- fields ----

    @JvmStatic
    fun findField(type: Class<*>, name: String): Field {
        val key = "${type.name}#$name@${System.identityHashCode(type.classLoader)}"
        val cached = FIELDS[key]
        if (cached != null && cached.declaringClass.isAssignableFrom(type)) return cached
        var current: Class<*>? = type
        while (current != null) {
            try {
                val field = current.getDeclaredField(name)
                field.isAccessible = true
                FIELDS[key] = field
                return field
            } catch (_: NoSuchFieldException) {
                // Look in the superclass.
            }
            current = current.superclass
        }
        throw NoSuchFieldError("${type.name}#$name")
    }

    @JvmStatic
    fun getObjectField(target: Any, name: String): Any? {
        return try {
            findField(target.javaClass, name).get(target)
        } catch (denied: IllegalAccessException) {
            throw IllegalAccessError(denied.message)
        }
    }

    @JvmStatic
    fun getIntField(target: Any, name: String): Int {
        return try {
            findField(target.javaClass, name).getInt(target)
        } catch (denied: IllegalAccessException) {
            throw IllegalAccessError(denied.message)
        }
    }

    @JvmStatic
    fun setIntField(target: Any, name: String, value: Int) {
        try {
            findField(target.javaClass, name).setInt(target, value)
        } catch (denied: IllegalAccessException) {
            throw IllegalAccessError(denied.message)
        }
    }

    @JvmStatic
    fun setBooleanField(target: Any, name: String, value: Boolean) {
        try {
            findField(target.javaClass, name).setBoolean(target, value)
        } catch (denied: IllegalAccessException) {
            throw IllegalAccessError(denied.message)
        }
    }

    @JvmStatic
    fun setLongField(target: Any, name: String, value: Long) {
        try {
            findField(target.javaClass, name).setLong(target, value)
        } catch (denied: IllegalAccessException) {
            throw IllegalAccessError(denied.message)
        }
    }

    @JvmStatic
    fun setObjectField(target: Any, name: String, value: Any?) {
        try {
            findField(target.javaClass, name).set(target, value)
        } catch (denied: IllegalAccessException) {
            throw IllegalAccessError(denied.message)
        }
    }

    @JvmStatic
    fun getStaticObjectField(type: Class<*>, name: String): Any? {
        return try {
            findField(type, name).get(null)
        } catch (denied: IllegalAccessException) {
            throw IllegalAccessError(denied.message)
        }
    }

    // ---- calls ----

    @JvmStatic
    fun callMethod(target: Any, name: String, vararg args: Any?): Any? {
        return invoke(findMethodBestMatch(target.javaClass, name, *args), target, args)
    }

    @JvmStatic
    fun callStaticMethod(type: Class<*>, name: String, vararg args: Any?): Any? {
        return invoke(findMethodBestMatch(type, name, *args), null, args)
    }

    @JvmStatic
    fun newInstance(type: Class<*>, vararg args: Any?): Any {
        val constructor = findConstructorBestMatch(type, *args)
        return try {
            constructor.newInstance(*args)
        } catch (failed: InvocationTargetException) {
            throw InvocationTargetError(failed.cause)
        } catch (denied: IllegalAccessException) {
            throw IllegalAccessError(denied.message)
        } catch (abstractType: InstantiationException) {
            throw InstantiationError(abstractType.message)
        }
    }

    private fun invoke(method: Method, target: Any?, args: Array<out Any?>): Any? {
        return try {
            method.invoke(target, *args)
        } catch (failed: InvocationTargetException) {
            throw InvocationTargetError(failed.cause)
        } catch (denied: IllegalAccessException) {
            throw IllegalAccessError(denied.message)
        }
    }

    @JvmStatic
    fun findMethodBestMatch(type: Class<*>, name: String, vararg args: Any?): Method {
        val argTypes = argumentTypes(args)
        val key = memberKey(type, name, argTypes)
        val cached = MEMBERS[key]
        if (cached is Method) return cached
        var best: Method? = null
        var bestCost = Float.MAX_VALUE
        var current: Class<*>? = type
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name != name) continue
                val cost = cost(argTypes, method.parameterTypes)
                // A subclass declaration wins a tie, so overriding methods resolve as before.
                if (cost < bestCost) {
                    best = method
                    bestCost = cost
                }
            }
            current = current.superclass
        }
        if (best == null) {
            throw NoSuchMethodError("${type.name}#$name${argTypes.contentToString()}#bestmatch")
        }
        best.isAccessible = true
        MEMBERS[key] = best
        return best
    }

    @JvmStatic
    fun findConstructorBestMatch(type: Class<*>, vararg args: Any?): Constructor<*> {
        val argTypes = argumentTypes(args)
        val key = memberKey(type, "<init>", argTypes)
        val cached = MEMBERS[key]
        if (cached is Constructor<*>) return cached
        var best: Constructor<*>? = null
        var bestCost = Float.MAX_VALUE
        for (constructor in type.declaredConstructors) {
            val cost = cost(argTypes, constructor.parameterTypes)
            if (cost < bestCost) {
                best = constructor
                bestCost = cost
            }
        }
        if (best == null) {
            throw NoSuchMethodError("${type.name}#<init>${argTypes.contentToString()}#bestmatch")
        }
        best.isAccessible = true
        MEMBERS[key] = best
        return best
    }

    private fun memberKey(type: Class<*>, name: String, argTypes: Array<Class<*>?>): String {
        return "${type.name}@${System.identityHashCode(type.classLoader)}#$name${argTypes.contentToString()}"
    }

    private fun argumentTypes(args: Array<out Any?>): Array<Class<*>?> {
        return Array(args.size) { i -> args[i]?.javaClass }
    }

    /** Conversion cost, or {@link Float#MAX_VALUE} when an argument cannot be passed. */
    @JvmStatic
    fun cost(args: Array<Class<*>?>, parameters: Array<Class<*>>): Float {
        if (args.size != parameters.size) return Float.MAX_VALUE
        var total = 0f
        for (i in args.indices) {
            val step = cost(args[i], parameters[i])
            if (step == Float.MAX_VALUE) return Float.MAX_VALUE
            total += step
        }
        return total
    }

    private fun cost(arg: Class<*>?, parameter: Class<*>): Float {
        if (arg == null) return if (parameter.isPrimitive) Float.MAX_VALUE else 1.5f
        if (parameter.isPrimitive) return primitiveCost(arg, parameter)
        if (!parameter.isAssignableFrom(arg)) return Float.MAX_VALUE
        var cost = 0f
        var current: Class<*>? = arg
        while (current != null && current != parameter) {
            if (parameter.isInterface && parameter.isAssignableFrom(current)) {
                cost += 0.25f
                break
            }
            cost++
            current = current.superclass
        }
        if (current == null) cost += 1.5f
        return cost
    }

    private fun primitiveCost(arg: Class<*>, parameter: Class<*>): Float {
        val current = unbox(arg) ?: return Float.MAX_VALUE
        val cost = if (arg.isPrimitive) 0f else 0.1f
        if (current == parameter) return cost
        val booleanPrimitive = Boolean::class.javaPrimitiveType!!
        if (current == booleanPrimitive || parameter == booleanPrimitive) return Float.MAX_VALUE
        if (!widens(current, parameter)) return Float.MAX_VALUE
        val from = indexOf(current)
        val to = indexOf(parameter)
        return cost + 0.1f * (to - from)
    }

    private fun widens(from: Class<*>, to: Class<*>): Boolean {
        val charPrimitive = Char::class.javaPrimitiveType!!
        if (from == charPrimitive) {
            return to == Int::class.javaPrimitiveType ||
                to == Long::class.javaPrimitiveType ||
                to == Float::class.javaPrimitiveType ||
                to == Double::class.javaPrimitiveType
        }
        if (to == charPrimitive) return false
        return indexOf(from) >= 0 && indexOf(to) > indexOf(from)
    }

    private fun indexOf(primitive: Class<*>): Int {
        for (i in PRIMITIVE_ORDER.indices) {
            if (PRIMITIVE_ORDER[i] == primitive) return i
        }
        return -1
    }

    private fun unbox(type: Class<*>): Class<*>? {
        if (type.isPrimitive) return type
        return when (type) {
            Int::class.javaObjectType -> Int::class.javaPrimitiveType
            Long::class.javaObjectType -> Long::class.javaPrimitiveType
            Boolean::class.javaObjectType -> Boolean::class.javaPrimitiveType
            Double::class.javaObjectType -> Double::class.javaPrimitiveType
            Float::class.javaObjectType -> Float::class.javaPrimitiveType
            Short::class.javaObjectType -> Short::class.javaPrimitiveType
            Byte::class.javaObjectType -> Byte::class.javaPrimitiveType
            Char::class.javaObjectType -> Char::class.javaPrimitiveType
            else -> null
        }
    }
}
