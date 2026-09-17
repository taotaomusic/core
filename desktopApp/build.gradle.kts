import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

val desktopVersion = Properties().run {
    rootProject.file("version.properties").inputStream().use(::load)
    getProperty("VERSION_NAME", "1.0.0")
}
val desktopVersionCode = Properties().run {
    rootProject.file("version.properties").inputStream().use(::load)
    getProperty("VERSION_CODE", "1").toInt()
}

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.taotao.desktop-modules")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":player-ui"))
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")

    // FFmpeg 负责网络流与无损格式解码，只携带 Windows x64 native，避免打入其它平台二进制。
    implementation("org.bytedeco:javacv:1.5.14") {
        isTransitive = false
    }
    implementation("org.bytedeco:ffmpeg:8.1.2-1.5.14")
    runtimeOnly("org.bytedeco:javacpp:1.5.14:windows-x86_64")
    runtimeOnly("org.bytedeco:ffmpeg:8.1.2-1.5.14:windows-x86_64")

    // Windows SMTC：向系统音量浮层/锁屏发布媒体信息，并接收全局媒体键。
    implementation("io.github.selemba1000:JavaMediaTransportControls:0.0.3")

    testImplementation(kotlin("test"))
}

desktopModules {
    channel.set(providers.gradleProperty("desktopChannel").orElse("release"))
    versionCode.set(desktopVersionCode)
    versionName.set(desktopVersion)
    architecture.set("windows-x64")
    entrypoint.set("taotao-app.jar")
    releaseNote.set(providers.gradleProperty("desktopReleaseNote").orElse(""))
    rollout.set(providers.gradleProperty("desktopRollout").map(String::toInt).orElse(0))
}

// Kotlin Multiplatform 会在项目配置后才注册 desktopJar，延迟绑定以保留正确的任务依赖。
gradle.projectsEvaluated {
    desktopModules.sharedJars.from(project(":shared").tasks.named("desktopJar"))
}

val generatedVersionResources = layout.buildDirectory.dir("generated/version-resources")
val generateDesktopVersionResource by tasks.registering {
    outputs.dir(generatedVersionResources)
    doLast {
        generatedVersionResources.get().file("desktop-version.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("versionCode=$desktopVersionCode\nversionName=$desktopVersion\n")
        }
    }
}

sourceSets.main {
    resources.srcDir(generatedVersionResources)
}

tasks.processResources {
    dependsOn(generateDesktopVersionResource)
}

compose.desktop {
    application {
        mainClass = "com.taotao.music.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            // jlink 精简运行时必须显式带上桌面网络请求和 Preferences 会话存储模块。
            modules(
                "java.net.http",
                "java.prefs",
                "java.instrument",
                "java.management",
                "jdk.jfr",
                "jdk.security.auth",
                "jdk.unsupported",
            )
            packageName = "TaotaoMusic"
            packageVersion = desktopVersion
            description = "桃桃音乐 Windows 音乐播放器"
            vendor = "桃桃音乐"
            windows {
                iconFile.set(project.file("src/main/resources/taotao-music.ico"))
                menuGroup = "桃桃音乐"
                upgradeUuid = "48b21e48-0d77-4f6c-aac8-3e34c6b0f161"
                perUserInstall = true
                shortcut = true
                dirChooser = true
            }
        }
    }
}

val prepareDesktopRuntime by tasks.registering(Sync::class) {
    group = "distribution"
    description = "生成带 java.exe 的精简 Java 运行时"
    dependsOn("createDesktopRuntimeImage")
    from(layout.buildDirectory.dir("desktop-runtime-image"))
    into(layout.buildDirectory.dir("desktop-update/runtime"))
}

val createDesktopRuntimeImage by tasks.registering {
    group = "distribution"
    description = "用 JDK jlink 生成可供 launcher 派生应用进程的运行时"
    val output = layout.buildDirectory.dir("desktop-runtime-image")
    outputs.dir(output)
    doLast {
        val javaHome = File(System.getProperty("java.home"))
        val jlink = javaHome.resolve("bin/jlink.exe").takeIf(File::isFile)
            ?: javaHome.resolve("bin/jlink")
        require(jlink.isFile) { "JDK 中缺少 jlink：$jlink" }
        val destination = output.get().asFile
        project.delete(destination)
        project.exec {
            commandLine(
                jlink.absolutePath,
                "--module-path", javaHome.resolve("jmods").absolutePath,
                "--add-modules", listOf(
                    "java.net.http", "java.prefs", "java.instrument", "java.management",
                    "jdk.jfr", "jdk.security.auth", "jdk.unsupported",
                ).joinToString(","),
                "--strip-debug", "--no-man-pages", "--no-header-files",
                "--output", destination.absolutePath,
            )
        }
        require(destination.resolve("bin/java.exe").isFile) {
            "jlink runtime 缺少 java.exe，不能启动更新后的桌面应用"
        }
    }
}

/**
 * 没有 GraalVM 时用 JDK jpackage 生成可运行的 EXE 入口。
 * 两个入口共享 launcher 的精简 runtime，避免把两份 JRE 放进发布包。
 */
val windowsEntrypoints = layout.buildDirectory.dir("desktop-windows-entrypoints/merged")
val packageWindowsEntrypoints by tasks.registering {
    group = "distribution"
    description = "生成 launcher.exe 和 updater.exe（GraalVM 不可用时使用 jpackage）"
    dependsOn(":desktopLauncher:jar", ":desktopUpdater:jar")
    outputs.dir(windowsEntrypoints)
    doLast {
        require(System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            "Windows 入口只能在 Windows 主机构建"
        }
        val root = layout.buildDirectory.dir("desktop-windows-entrypoints").get().asFile
        val launcherInput = project(":desktopLauncher").layout.buildDirectory.dir("libs").get().asFile
        val updaterInput = project(":desktopUpdater").layout.buildDirectory.dir("libs").get().asFile
        val launcherDest = root.resolve("launcher-image")
        val updaterDest = root.resolve("updater-image")
        project.delete(root)
        launcherDest.mkdirs()
        updaterDest.mkdirs()
        exec {
            commandLine(
                "jpackage", "--type", "app-image", "--input", launcherInput.absolutePath,
                "--main-jar", "taotao-launcher.jar", "--name", "launcher",
                "--dest", launcherDest.absolutePath, "--app-version", desktopVersion,
            )
        }
        exec {
            commandLine(
                "jpackage", "--type", "app-image", "--input", updaterInput.absolutePath,
                "--main-jar", "taotao-updater.jar", "--name", "updater",
                "--dest", updaterDest.absolutePath, "--app-version", desktopVersion,
            )
        }
        val launcherImage = launcherDest.resolve("launcher")
        val updaterImage = updaterDest.resolve("updater")
        val merged = windowsEntrypoints.get().asFile
        project.copy { from(launcherImage); into(merged) }
        project.copy { from(updaterImage.resolve("updater.exe")); into(merged) }
        project.copy {
            from(updaterImage.resolve("app/updater.cfg"), updaterImage.resolve("app/taotao-updater.jar"))
            into(merged.resolve("app"))
        }
        require(merged.resolve("launcher.exe").isFile && merged.resolve("updater.exe").isFile) {
            "jpackage 未生成 launcher.exe/updater.exe"
        }
    }
}

val packageDesktopUpdateBundle by tasks.registering(Sync::class) {
    group = "distribution"
    description = "生成包含 launcher、updater、模块 JAR 与精简 JRE 的 Windows 更新包"
    dependsOn(
        tasks.named("generateDesktopManifest"),
        prepareDesktopRuntime,
        packageWindowsEntrypoints,
    )
    from(layout.buildDirectory.dir("desktop-update"))
    from(windowsEntrypoints)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into(layout.buildDirectory.dir("desktop-update-bundle"))
}

/**
 * 上传流程使用环境变量 DESKTOP_RELEASE_BASE_URL / ADMIN_SESSION_TOKEN。
 * 先逐个上传内容寻址对象，再提交清单；重复上传相同 sha256 是幂等的。
 *
 * ADMIN_SESSION_TOKEN 是**管理后台登录后签发的会话令牌**，不是静态密钥：
 * 打开管理后台 → 登录 → 从浏览器 DevTools 的
 * `localStorage.getItem("taotao_admin_token")` 取出，作为环境变量传入。
 * 会话 24 小时过期，过期后重新登录取一次即可。
 */
val publishDesktopRelease by tasks.registering {
    group = "publishing"
    description = "上传 Windows 模块并登记桌面发布"
    dependsOn(tasks.named("generateDesktopManifest"))
    doLast {
        val base = (System.getenv("DESKTOP_RELEASE_BASE_URL") ?: "").trimEnd('/')
        val token = System.getenv("ADMIN_SESSION_TOKEN") ?: ""
        require(base.startsWith("http")) { "需要设置 DESKTOP_RELEASE_BASE_URL" }
        require(token.isNotBlank()) { "需要设置 ADMIN_SESSION_TOKEN（管理后台登录后的会话令牌）" }
        val manifestFile = layout.buildDirectory.file("desktop-update/manifest.json").get().asFile
        @Suppress("UNCHECKED_CAST")
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<String, Any>
        val files = manifest["files"] as List<Map<String, Any>>
        val current = layout.buildDirectory.dir("desktop-update/current").get().asFile
        files.forEach { item ->
            val sha = item["sha256"].toString()
            val file = File(current, item["path"].toString())
            exec {
                commandLine(
                    "curl.exe", "--fail-with-body", "-sS", "-X", "POST",
                    "$base/api/v1/desktop/admin/artifacts?sha256=$sha",
                    "-H", "Authorization: Bearer $token", "--data-binary", "@${file.absolutePath}",
                )
            }
        }
        exec {
            commandLine(
                "curl.exe", "--fail-with-body", "-sS", "-X", "POST",
                "$base/api/v1/desktop/admin/releases", "-H", "Authorization: Bearer $token",
                "-H", "content-type: application/json", "--data-binary", "@${manifestFile.absolutePath}",
            )
        }
    }
}
