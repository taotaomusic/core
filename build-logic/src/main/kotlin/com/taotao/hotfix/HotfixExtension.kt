package com.taotao.hotfix

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** 插桩范围配置，在 `androidApp/build.gradle.kts` 里用 `hotfix { ... }` 调整。 */
interface HotfixExtension {
    /**
     * 要插桩的包前缀，斜杠形式（`com/taotao/music/data/`）。
     * 刻意不含 `ui/`：Compose 可组合函数不能在开头提前 return。
     */
    val includedPackages: ListProperty<String>

    /** 关掉插桩用于对比构建产物，排查是不是插桩引起的问题。 */
    val enabled: Property<Boolean>
}
