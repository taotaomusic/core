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
}

tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    finalizedBy("incrementVersion")
}

tasks.register("incrementVersion") {
    group = "版本管理"
    description = "编译完成后自动递增版本号"
    doLast {
        exec { commandLine("powershell", "-ExecutionPolicy", "Bypass", "-File", rootProject.file("tools/Update-Version.ps1").absolutePath) }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":player-ui"))
    testImplementation(kotlin("test"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-database:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // 与部署中的悟空 IM v2.2.5 同日发布的 Android SDK，避免协议版本漂移。
    implementation("com.github.WuKongIM:WuKongIMAndroidSDK:1.5.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
