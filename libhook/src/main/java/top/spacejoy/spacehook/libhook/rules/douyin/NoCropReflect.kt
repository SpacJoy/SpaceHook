package top.spacejoy.spacehook.libhook.rules.douyin

import java.lang.reflect.Method

/**
 * 「抖音不裁剪」移植版的反射小工具。
 *
 * 抖音的目标类字段/方法多为 public（`VideoItemParams.mAweme`、`Aweme.getVideo` 等），
 * 这里统一提供「沿继承链」的宽容查找：找不到一律返回 null，不抛异常，
 * 保证单个反射失败不影响整个 Hook 链路。
 *
 * 移植自 `douyinnocrop-v1.1.0.apk`（io.github.dunxuan.douyinnocrop）。
 */
internal object NoCropReflect {

    /** 取字段值：先按 public `getField`，失败后沿继承链 `getDeclaredField` */
    fun field(target: Any?, name: String): Any? {
        if (target == null) return null
        try {
            val f = target.javaClass.getField(name)
            f.isAccessible = true
            return f.get(target)
        } catch (_: Throwable) { /* 继续尝试 */ }
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    /** 调用无参方法（public 优先，含继承链） */
    fun callNoArg(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            findMethodExact(target.javaClass, name, emptyArray())?.invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    /** 按名称 + 参数类型查找方法（含继承链；public 优先） */
    fun findMethodExact(clazz: Class<*>, name: String, params: Array<Class<*>>): Method? {
        try {
            return clazz.getMethod(name, *params)
        } catch (_: Throwable) { /* 继续尝试 */ }
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, *params)
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    /**
     * 抗混淆探测：按**参数签名**找唯一匹配的方法（方法名不限）。
     *
     * 抖音的 `CleanModeViewModel.sA(...)` 这类短名是混淆产物，版本更新随时改名；
     * 而「(CleanModeCommand, String, boolean)」这种独特签名在同层级里通常唯一，
     * 因此可以绕过方法名变化。找不到唯一匹配时返回 null（宁缺毋滥）。
     */
    fun findMethodBySignature(clazz: Class<*>, params: Array<Class<*>>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            val matches = c.declaredMethods.filter { it.parameterTypes.contentEquals(params) }
            if (matches.size == 1) return matches[0]
            c = c.superclass
        }
        return null
    }

    /** 找第一个「字段类型可赋值为 [type]」的字段值（沿继承链） */
    fun fieldOfType(target: Any?, type: Class<*>): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (!type.isAssignableFrom(f.type)) continue
                try {
                    f.isAccessible = true
                    return f.get(target)
                } catch (_: Throwable) { /* 试下一个字段 */ }
            }
            c = c.superclass
        }
        return null
    }

    /** 数值字段（Number 才有效），其余返回 0 */
    fun asNumber(value: Any?): Float = (value as? Number)?.toFloat() ?: 0f
}
