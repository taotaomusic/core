package com.taotao.music.model

/**
 * 音质档位。
 *
 * [value] 就是后端 `quality` 参数的取值，与上游 `/song/info` 返回的 `qualityInfo[].type`
 * 一一对应（已用同一首歌逐档核对：请求 quality=10 拿到的文件名与 type=10 那条完全一致）。
 *
 * 只暴露适合当作播放档位的几档。上游实际有 0–18 共 19 档，其余的是特殊形态：
 * 12 杜比全景声是 mp4 容器、15–17 是 AI 消音与 AI 钢琴的试验档、18 是 nac 私有格式，
 * 都不适合放进「默认播放音质」让用户随便选。展示别名见 [labelOfQuality]。
 */
enum class AudioQuality(val value: Int, val label: String, val lossless: Boolean) {
    STANDARD(4, "标准", false),
    HIGH(8, "HQ 高音质", false),
    LOSSLESS(10, "SQ 无损", true),
    HIRES(11, "Hi-Res", true),
    MASTER(14, "臻品母带", true);

    companion object {
        /** 默认档位。HQ 在音质与流量之间比较平衡，也是后端的历史默认值附近。 */
        val Default = HIGH

        /** 按取值查档位；未知取值退回默认档，避免存进偏好的脏数据让播放彻底不可用。 */
        fun of(value: Int): AudioQuality = entries.firstOrNull { it.value == value } ?: Default
    }
}

/**
 * 任意音质取值的中文名，覆盖上游全部 0–18 档。
 *
 * 与 [AudioQuality] 分开是因为用途不同：枚举是「用户能选什么」，这个函数是
 * 「服务端实际给了什么」—— 降级后可能落在枚举之外的档位上，界面仍要能说出它的名字。
 */
fun labelOfQuality(value: Int): String = when (value) {
    0 -> "试听"
    1, 2 -> "有损"
    in 3..7 -> "标准"
    8, 9 -> "HQ 高音质"
    10 -> "SQ 无损"
    11 -> "Hi-Res"
    12 -> "杜比全景声"
    13 -> "臻品全景声"
    14 -> "臻品母带"
    15 -> "AI 伴奏消音"
    16 -> "AI 人声消音"
    17 -> "AI 钢琴"
    18 -> "NAC"
    else -> "音质 $value"
}
