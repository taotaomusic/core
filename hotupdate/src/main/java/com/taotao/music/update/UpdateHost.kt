package com.taotao.music.update

/**
 * 宿主注入的热更新外部依赖契约。
 *
 * `:hotupdate` 作为独立模块只依赖这几个窄接口，不直接触碰 app 的登录会话、
 * Room 数据库与全局 HTTP 常量：那些基础设施归宿主所有，依赖方向永远是
 * app -> 模块。宿主实现见 androidApp 的 `com.taotao.music.update.TaotaoUpdateHost`。
 */
interface UpdateHost {
    /** 服务端 base URL，例如 `https://music.xydaigua.cn`。 */
    val endpoint: String

    /** 全 App 统一 User-Agent。 */
    val userAgent: String

    /**
     * 当前访问令牌；未登录返回 null，服务端会退回按设备号分桶。
     *
     * 刻意只要「当前令牌」而不提供续期：检查更新是后台动作，令牌过期时
     * 不能触发续期失败回踢登录页，服务端会自行退回按设备号分桶。
     */
    fun currentToken(): String?

    /** 灰度分桶用的匿名设备号，首次生成后必须保持稳定。 */
    fun deviceId(): String

    /** 远程配置的本地缓存，读取永远有默认值兜底。 */
    val config: UpdateConfigStore

    /** 用户上次划掉的可选更新版本号；0 表示没有记录。 */
    fun dismissedVersionCode(): Long

    /** 记住用户划掉的版本号，跨启动生效（强制更新不允许划掉，由模块侧把关）。 */
    fun commitDismissed(versionCode: Long)
}

/**
 * 远程配置缓存契约。
 *
 * 读取在首帧与离线时都会发生，实现必须同步可查并兜底默认值；
 * 落盘与读取语义与宿主的 RemoteConfigStore 一一对应。
 */
interface UpdateConfigStore {
    /** 落盘一份服务端下发的配置；版本号相同则实现可跳过写入。 */
    fun save(values: Map<String, String>, version: Long)

    fun text(key: String, default: String = ""): String

    fun boolean(key: String, default: Boolean = false): Boolean

    fun int(key: String, default: Int = 0): Int

    /** 清空缓存（登出等场景）。 */
    fun clear()
}
