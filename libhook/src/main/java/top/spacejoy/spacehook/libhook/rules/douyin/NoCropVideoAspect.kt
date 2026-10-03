package top.spacejoy.spacehook.libhook.rules.douyin

/**
 * 「抖音不裁剪」移植版：从 Aweme 对象读取视频/图集的原始宽高比。
 *
 * 移植自 `douyinnocrop-v1.1.0.apk`（io.github.dunxuan.douyinnocrop.VideoAspect）。
 * 目标类（抖音 40.6.0 / versionCode 400601 已用 dexdump 复核）：
 * - `com.ss.android.ugc.aweme.feed.model.Aweme`：`getVideo()`、`getAid()`、
 *   字段 `imageInfos : Ljava/util/List;`、`images : Ljava/util/List;`（均未混淆）
 *
 * 全部读取均为「宽容反射」：任何一步失败返回 0 / false，不影响宿主。
 */
internal object NoCropVideoAspect {

    /** 宽高比 sanity 区间（与原实现一致） */
    private const val MIN_SANE = 0.1f
    private const val MAX_SANE = 20f

    private fun isSane(f: Float): Boolean = f > MIN_SANE && f < MAX_SANE

    /**
     * 读取 aweme 的宽高比（width / height）。
     * 取不到时返回 0f（调用方据此跳过适配）。
     */
    fun read(aweme: Any?): Float {
        if (aweme == null) return 0f
        // 路径 1：video 的 width/height（或混淆别名 t/s）
        val video = NoCropReflect.callNoArg(aweme, "getVideo")
        fromSizeFields(video)?.let { return it }
        // 路径 2：video 的 aspectRatio 字段
        val direct = NoCropReflect.asNumber(NoCropReflect.field(video, "aspectRatio"))
        if (isSane(direct)) return direct
        // 路径 3：图集 imageInfos[0].width/height
        return fromImageInfos(aweme)
    }

    /** 图集帖判定：`imageInfos` 或 `images` 为非空 List */
    fun isImagePost(aweme: Any?): Boolean {
        if (aweme == null) return false
        for (name in IMAGE_LIST_FIELDS) {
            val v = NoCropReflect.field(aweme, name)
            if (v is List<*> && v.isNotEmpty()) return true
        }
        return false
    }

    /** 作品 id（用于「同一作品才复用真实尺寸」的校验） */
    fun aid(aweme: Any?): String? = NoCropReflect.callNoArg(aweme, "getAid") as? String

    // ==================== 内部 ====================

    /** Video 尺寸字段的候选名：原名 width/height，混淆后可能变成 t/s */
    private val SIZE_FIELD_PAIRS = listOf(
        arrayOf("width", "height"),
        arrayOf("t", "s"),
    )
    private val IMAGE_LIST_FIELDS = listOf("imageInfos", "images")

    private fun fromSizeFields(obj: Any?): Float? {
        if (obj == null) return null
        for ((wName, hName) in SIZE_FIELD_PAIRS.map { it[0] to it[1] }) {
            val w = NoCropReflect.asNumber(NoCropReflect.field(obj, wName))
            val h = NoCropReflect.asNumber(NoCropReflect.field(obj, hName))
            if (w > 0f && h > 0f) {
                val f = w / h
                if (isSane(f)) return f
            }
        }
        return null
    }

    private fun fromImageInfos(aweme: Any): Float {
        for (name in IMAGE_LIST_FIELDS) {
            val list = NoCropReflect.field(aweme, name) as? List<*> ?: continue
            val first = list.firstOrNull() ?: continue
            fromSizeFields(first)?.let { return it }
        }
        return 0f
    }
}
