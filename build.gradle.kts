plugins {
    // 8.13.2：8.x 系列最新稳定。内置 lint 以 Kotlin 2.2.10 构建，能读取 Kotlin ≤2.3 的
    // 产物 metadata；依赖库 metadata 若超此上限，需观察 lintVitalAnalyzeRelease 是否告警。
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    // Kotlin 2.3.21：CMP 1.11.x 配套的稳定版（compose 编译器与 runtime 1.11 成对发布）。
    // Miuix 0.9.3 以 Kotlin 2.4.0 编译发布，2.3 编译器按「向前兼容一个小版本」规则可读。
    // 不上 2.4：CMP 1.12 对应的 androidx compose 1.12.1 经 AAR metadata 硬性要求
    // compileSdk 37 + AGP 9.1+，那组连锁升级留待 AGP 9 生态成熟后单独做。
    kotlin("android") version "2.3.21" apply false
    kotlin("multiplatform") version "2.3.21" apply false
    // 热修复补丁模块是纯 JVM 模块：它只产出一个 dex，不需要资源、清单和签名。
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.compose") version "2.3.21" apply false
    // 1.11.1：与 Miuix 0.9.3 的编译环境完全一致（其 foundation 依赖即 1.11.1），
    // Android 侧映射 androidx compose 1.11.2（AAR metadata 仅要求 compileSdk ≥35、无 AGP 下限）。
    id("org.jetbrains.compose") version "1.11.1" apply false
    // Room 注解处理走 KSP2。KSP 自 2.3.0 起与 Kotlin 版本解耦、独立发版（插件不再钉
    // KGP 依赖），2.3.12 可直接配 Kotlin 2.3/2.4，不再需要 <Kotlin 版本>-<KSP 版本> 拼接。
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
