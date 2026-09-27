package com.taotao.music

/** 统一的 HTTP 头常量。User-Agent 带**真实应用版本**（BuildConfig.VERSION_NAME）。 */
object AppHttp {
    /** 例如 `TaotaoMusic/1.0.236`。全 App 请求共用，避免各处硬编码 `1.0`。 */
    val USER_AGENT: String = "TaotaoMusic/${BuildConfig.VERSION_NAME}"
}
