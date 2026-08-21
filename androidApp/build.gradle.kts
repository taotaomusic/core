import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
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
    defaultConfig { applicationId = "com.taotao.music"; minSdk = 24; targetSdk = 35; versionCode = appVersionCode; versionName = appVersionName }
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
    debugImplementation("androidx.compose.ui:ui-tooling")
}
