import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    // 热修复插桩。只作用于 data / player / update 包，UI 层不碰 —— 见 build-logic 里的说明。
    id("com.taotao.hotfix")
}

val localSigningProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionCode = versionProperties.getProperty("VERSION_CODE").toInt()
val appVersionName = versionProperties.getProperty("VERSION_NAME")

android { namespace = "com.taotao.music"; compileSdk = 35
    buildFeatures { buildConfig = true }  // 用 BuildConfig.VERSION_NAME 拼真实版本进 User-Agent
    // AGP 8.7.3 内置 lint 的 Kotlin Analysis API 与 Kotlin 2.2 编译产物不兼容，lifecycle 库的
    // NonNullableMutableLiveDataDetector 会在 lintVitalAnalyzeRelease 直接崩溃（按 lint 自身
    // 给出的处置禁用该 detector；它只覆盖 MutableLiveData 的可空性提示，不影响发布校验）。
    lint {
        disable += "NullSafeMutableLiveData"
    }
    defaultConfig {
        applicationId = "com.taotao.music"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // 正式包仅支持 64 位 ARM 真机，移除 x86 与 32 位 ARM 原生库以减小体积。
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
    signingConfigs {
        create("release") {
            storeFile = file(rootProject.file("taotao-release.jks"))
            storePassword = localSigningProperties.getProperty("TAOTAO_STORE_PASSWORD")
            keyAlias = localSigningProperties.getProperty("TAOTAO_KEY_ALIAS")
            keyPassword = localSigningProperties.getProperty("TAOTAO_KEY_PASSWORD")
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") } }

    // 传输加密 native 库（`plans/009`）。产物由 tools 仓库云端交叉编译，经
    // tools/fetch-crypto.ps1 落到 crypto/dist/android/<abi>/libtaotao_crypto.so。
    // 目录结构即 jniLibs 约定（<srcDir>/<abi>/lib*.so），abiFilters 只保留 arm64-v8a
    // 时也只打包对应那份。产物缺失时不阻断构建，运行时由 NativeCrypto.available 兜底降级明文。
    sourceSets["main"].jniLibs.srcDir(rootProject.file("crypto/dist/android"))
}

tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    finalizedBy("incrementVersion")
}

tasks.register("incrementVersion") {
    group = "版本管理"
    description = "编译完成后自动递增版本号"
    doLast {
        // 纯 JVM 实现，不再调 tools/Update-Version.ps1——旧实现依赖 powershell 命令，
        // CI 构建机迁到 ubuntu 后进程直接起不来（2026-10-05 实测）。语义与原脚本一致：
        // VERSION_CODE 与 VERSION_NAME 第三段各 +1，只回写这两行。
        val file = rootProject.file("version.properties")
        val props = mutableMapOf<String, String>()
        // 去掉可能存在的 UTF-8 BOM，否则第一个键名会带上不可见字符。
        file.readText(Charsets.UTF_8).replace("\uFEFF", "").lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) props[line.substring(0, idx).trim()] = line.substring(idx + 1)
        }
        val code = (props["VERSION_CODE"]?.toIntOrNull() ?: 0) + 1
        val nameParts = (props["VERSION_NAME"] ?: "").split('.')
        val patch = (nameParts.getOrNull(2)?.toIntOrNull() ?: 0) + 1
        val newName = nameParts.take(2).joinToString(".") + "." + patch
        // writeText 默认 UTF-8 无 BOM：Java Properties.load 会把 BOM 当作键名的一部分，
        // 导致下一次构建读不到 VERSION_CODE（Windows PowerShell 5.1 的老坑，注释留警示）。
        file.writeText("VERSION_CODE=$code\nVERSION_NAME=$newName\n", Charsets.UTF_8)
        println("版本已更新为 $newName ($code)")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":player-ui"))
    testImplementation(kotlin("test"))
    // 本地单元测试的 mockable android.jar 里 org.json 方法会抛 not mocked，需要真实实现才能测 SongCodec。
    testImplementation("org.json:json:20240303")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-database:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // 与部署中的悟空 IM v2.2.5 同日发布的 Android SDK，避免协议版本漂移。
    implementation("com.github.WuKongIM:WuKongIMAndroidSDK:1.5.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
