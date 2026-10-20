package top.spacejoy.spacehook.libhook.rules.amap

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 高德「初始出行方式」初始化方法的结构适配层。
 *
 * ## 为什么需要它
 *
 * 高德决定「进入路线页时用哪种出行方式」的那段逻辑，所在类名、方法名、请求字段名
 * 都是 **R8 混淆名**，且随版本变化（15.10 是 `p91#a(s91, callback)`，
 * 17.01 是 `h15#a(g15, callback)`）。因此这里不按名字硬匹配，而是按**结构特征**校验：
 *
 * 1. 静态方法、返回 `void`、正好 2 个参数；
 * 2. 第 2 个参数是**只有一个抽象方法**的回调接口；
 * 3. 第 1 个参数是一个具体类，有公开无参构造，字段可写（用于复制请求）；
 * 4. 该请求类里**恰好一个枚举字段**，枚举名含 DEFAULT/CAR/BUS/ONFOOT/RIDE，
 *    且「名字 → 数值」映射为 -1/0/1/2/3（通过枚举上的无参 int 方法验证）；
 * 5. 请求类里至少有 2 个「POI 形态」字段（`getName():String` + `getPoint():非基本类型`）。
 *
 * 只有结构全部对得上才认为定位成功；对不上就不挂载、保持高德原有行为。
 *
 * ## 覆盖语义
 *
 * - 请求里**已经指定**了出行方式（非 DEFAULT）→ 原样放行（专门的驾车入口、带方式的第三方链接不受影响）；
 * - 请求未指定 → **复制一份请求**、把副本的枚举字段设成用户选的方式，再调用原方法；
 *   高德持有的原始请求不被修改，页内手动切换也不受影响。
 */
internal class AmapRouteAdapter private constructor(
    /** 被挂载的初始化方法（静态，参数为「请求, 回调」） */
    val initializer: Method,
    private val requestClass: Class<*>,
    private val requestConstructor: Constructor<*>,
    /** 请求里的出行方式枚举字段（唯一） */
    private val routeField: Field,
    /** 复制请求时要搬运的全部实例字段 */
    private val copyFields: List<Field>,
    /** 枚举常量名 → 枚举实例 */
    private val modes: Map<String, Any>,
    /** DEFAULT（=「未指定」）枚举实例 */
    private val automatic: Any,
    /**
     * 额外的同名同构入口（如 `PlanHomeService#getInitRouteType`）。
     *
     * 它只是把参数原样转发给 [initializer]，所以一并挂上做冗余：
     * 万一将来页面改用服务接口进入，主入口失效了这里还能接住。
     * 重复拦截是安全的 —— 副本已带明确方式，第二次会走「已指定」分支原样放行。
     */
    val secondaryEntries: List<Method>,
) {

    companion object {
        /**
         * 已知的候选类名（混淆名，随高德版本变化）。
         *
         * - `h15`：17.01.0.2012（真机实测；dex 里是默认包名 `Lh15;`，jadx 反编译时显示为 `defpackage.h15`）
         * - `p91`：15.10.1.2063（参考项目实测版本，同样在默认包）
         *
         * 名字只是「去哪儿找」，最终是否采用由结构校验决定。
         */
        private val CANDIDATE_CLASSES = listOf("h15", "p91", "defpackage.h15", "defpackage.p91")

        private val REQUIRED_MODES = listOf("DEFAULT", "CAR", "BUS", "ONFOOT", "RIDE")
        private val MODE_VALUES = listOf(-1, 0, 1, 2, 3)

        /** 未混淆的服务实现类：它的 `getInitRouteType` 直接转发给初始化方法，用作交叉校验锚点 */
        private const val SERVICE_CLASS = "com.amap.bundle.planhome.PlanHomeService"
        private const val SERVICE_METHOD = "getInitRouteType"

        /**
         * 定位并校验适配器。找不到 / 校验不过 / 匹配不唯一都返回 null（调用方据此安静退出）。
         */
        fun locate(loader: ClassLoader, log: (String) -> Unit): AmapRouteAdapter? {
            // 未混淆锚点：服务实现类上「请求 + 回调」的转发方法。拿它的第 1 个参数类型
            // 作为「请求类」的独立证据，用来交叉校验候选初始化方法，避免只靠混淆类名撞运气。
            val serviceEntry = findServiceEntry(loader, log)
            val anchoredRequest = serviceEntry?.parameterTypes?.getOrNull(0)

            val found = LinkedHashMap<String, AmapRouteAdapter>()
            for (className in CANDIDATE_CLASSES) {
                val clazz = try {
                    Class.forName(className, false, loader)
                } catch (_: Throwable) {
                    log("候选类不存在，跳过：$className")
                    continue
                }
                for (method in clazz.declaredMethods) {
                    if (!Modifier.isStatic(method.modifiers)) continue
                    if (Modifier.isNative(method.modifiers)) continue
                    if (method.parameterCount != 2) continue
                    if (method.returnType != Void.TYPE) continue
                    if (anchoredRequest != null && method.parameterTypes[0] != anchoredRequest) {
                        log("候选 $className#${method.name} 请求类型与服务接口不一致，跳过")
                        continue
                    }
                    val key = "$className#${method.name}"
                    if (found.containsKey(key)) continue
                    val adapter = try {
                        validate(method, serviceEntry)
                    } catch (t: Throwable) {
                        log("候选 $key 校验失败：${t.javaClass.simpleName} ${t.message ?: ""}")
                        null
                    }
                    if (adapter != null) {
                        log("结构校验通过：$key")
                        found[key] = adapter
                    }
                }
            }
            if (found.size != 1) {
                log("期望恰好 1 个有效初始化方法，实际 ${found.size} 个 → 不挂载")
                return null
            }
            return found.values.first()
        }

        /** 找未混淆的服务转发方法；不存在就返回 null（不影响主流程） */
        private fun findServiceEntry(loader: ClassLoader, log: (String) -> Unit): Method? = try {
            val service = Class.forName(SERVICE_CLASS, false, loader)
            val candidates = service.declaredMethods.filter {
                it.name == SERVICE_METHOD &&
                    it.parameterCount == 2 &&
                    it.returnType == Void.TYPE &&
                    it.parameterTypes[1].isInterface
            }
            when (candidates.size) {
                1 -> candidates.first().also { log("服务锚点：$SERVICE_CLASS#$SERVICE_METHOD") }
                0 -> {
                    log("未找到服务锚点 $SERVICE_CLASS#$SERVICE_METHOD（继续按结构定位）")
                    null
                }
                else -> {
                    log("服务锚点不唯一（${candidates.size}），不采用")
                    null
                }
            }
        } catch (_: Throwable) {
            log("服务锚点类不存在（继续按结构定位）")
            null
        }

        /** 按结构校验单个候选方法，不通过抛 [IllegalStateException] */
        private fun validate(initialize: Method, serviceEntry: Method?): AmapRouteAdapter {
            initialize.isAccessible = true

            val params = initialize.parameterTypes
            val requestClass = params[0]
            val callback = params[1]

            require(callback.isInterface) { "第 2 个参数不是接口" }
            val abstractCallbacks = callback.methods.filter {
                Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers)
            }
            require(abstractCallbacks.size == 1) { "回调接口不是单方法（${abstractCallbacks.size}）" }
            val onResult = abstractCallbacks.first()
            require(onResult.parameterCount == 1) { "回调方法参数个数不是 1" }
            require(onResult.returnType == Void.TYPE) { "回调方法返回值不是 void" }

            require(!requestClass.isInterface) { "请求是接口" }
            require(!Modifier.isAbstract(requestClass.modifiers)) { "请求是抽象类" }
            require(requestClass.superclass == Any::class.java) { "请求有父类（不支持继承结构）" }

            val requestConstructor = requestClass.getDeclaredConstructor()
            requestConstructor.isAccessible = true

            val copyFields = ArrayList<Field>()
            val enumFields = ArrayList<Field>()
            var poiFields = 0
            for (field in requestClass.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                require(!Modifier.isFinal(field.modifiers)) { "请求字段是 final：${field.name}" }
                field.isAccessible = true
                copyFields.add(field)
                if (field.type.isEnum) enumFields.add(field)
                if (isPoi(field.type)) poiFields++
            }
            require(enumFields.size == 1) { "出行方式枚举字段不唯一（${enumFields.size}）" }
            require(poiFields >= 2) { "POI 字段不足（$poiFields）" }

            val routeField = enumFields.first()
            val routeType = routeField.type
            val modes = LinkedHashMap<String, Any>()
            for (constant in routeType.enumConstants ?: emptyArray<Any>()) {
                modes[(constant as Enum<*>).name] = constant
            }
            for (name in REQUIRED_MODES) {
                require(modes.containsKey(name)) { "枚举缺少常量 $name" }
            }
            require(verifyModeValues(routeType, modes)) { "枚举「名字 → 数值」映射变化" }

            return AmapRouteAdapter(
                initializer = initialize,
                requestClass = requestClass,
                requestConstructor = requestConstructor,
                routeField = routeField,
                copyFields = copyFields,
                modes = modes,
                automatic = modes.getValue("DEFAULT"),
                secondaryEntries = serviceEntry?.let { listOf(it) } ?: emptyList(),
            )
        }

        /** 请求类里的 POI 字段：`getName():String` + `getPoint():非基本类型` */
        private fun isPoi(type: Class<*>): Boolean = try {
            type.getMethod("getName").returnType == String::class.java &&
                !type.getMethod("getPoint").returnType.isPrimitive
        } catch (_: NoSuchMethodException) {
            false
        }

        /**
         * 校验枚举的数值语义：找到一个无参 int 方法，让 5 个常量分别返回 -1/0/1/2/3。
         *
         * 只认名字不够 —— 不同版本若调换了数值，按名字塞进去会落到错误的分支。
         */
        private fun verifyModeValues(routeType: Class<*>, modes: Map<String, Any>): Boolean {
            for (method in routeType.declaredMethods) {
                if (Modifier.isStatic(method.modifiers)) continue
                if (method.parameterCount != 0) continue
                if (method.returnType != Integer.TYPE) continue
                method.isAccessible = true
                val matches = REQUIRED_MODES.indices.all { i ->
                    runCatching {
                        method.invoke(modes.getValue(REQUIRED_MODES[i])) == MODE_VALUES[i]
                    }.getOrDefault(false)
                }
                if (matches) return true
            }
            return false
        }
    }

    /**
     * 计算要替换进去的请求副本；返回 null 表示「不改，按原样执行」。
     *
     * @param input 原始请求（null 表示高德根本没带请求，此时不做覆盖，交给它自己走兜底分支）
     * @param modeKey 用户选择：OFF / BUS / ONFOOT / RIDE / CAR
     */
    fun replacement(input: Any?, modeKey: String): Any? {
        if (input == null) return null                        // 无请求：不构造空请求，避免干扰兜底分支
        val desired = modes[modeKey] ?: return null          // OFF / 未知取值：不覆盖
        if (desired === automatic) return null
        val explicit = routeField.get(input)
        if (explicit != null && explicit !== automatic) return null   // 调用方已明确指定
        val copy = requestConstructor.newInstance()
        for (field in copyFields) field.set(copy, field.get(input))
        routeField.set(copy, desired)
        return copy
    }

    /** 诊断用：本次请求是否已明确指定出行方式 */
    fun explicitModeOf(input: Any?): String? =
        input?.let { routeField.get(it) }?.let { (it as Enum<*>).name }
}
