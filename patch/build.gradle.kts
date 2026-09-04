import java.util.Properties
import java.util.zip.ZipFile

// 热修复补丁模块。
//
// 这个模块**不打进 APK**，只在需要发补丁时单独编译成 patch.dex。
//
// 关键约定是所有对宿主的依赖都用 compileOnly：补丁 DEX 里绝不能包含宿主已有的类。
// 一旦包含了，那个类会从补丁的类加载器里加载，与宿主的同名类是两个不同的 Class 对象，
// 传参时直接 ClassCastException。
plugins {
    kotlin("jvm")
}

/** 从 local.properties 取 SDK 路径。补丁编译与 d8 都要用到。 */
val sdkDirectory: String by lazy {
    Properties().apply {
        rootProject.file("local.properties").inputStream().use { load(it) }
    }.getProperty("sdk.dir") ?: error("local.properties 里缺少 sdk.dir")
}

/** 宿主编译产物所在目录，patch 代码按类型引用宿主类就靠它。 */
val hostClasses = rootProject.file("androidApp/build/tmp/kotlin-classes/release")
val sharedClasses = rootProject.file("shared/build/tmp/kotlin-classes/release")

/** 编译期需要 android.jar，但它由宿主提供，不能进补丁。 */
val androidJar: File by lazy { file("$sdkDirectory/platforms/android-35/android.jar") }

dependencies {
    compileOnly(files(hostClasses, sharedClasses))
    compileOnly(files(provider { androidJar }))
    compileOnly(kotlin("stdlib"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

/**
 * 把编译好的补丁类打成 DEX，再压成一个 zip（沿用 .apk 后缀，服务端按文件下发）。
 *
 * 用 SDK 自带的 d8。不走 AGP 是因为这个模块刻意不是 Android 模块 ——
 * 它只产出一个 dex，不需要资源、清单、签名那一整套。
 *
 * 用法：./gradlew :patch:buildPatch -PpatchVersion=1
 */
val buildPatch by tasks.registering {
    group = "热修复"
    description = "编译补丁并打包成可下发的补丁包"
    dependsOn(tasks.named("classes"))

    doLast {
        val buildTools = file("$sdkDirectory/build-tools").listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }
            ?: error("找不到 build-tools")
        val d8 = file("$buildTools/d8.bat").takeIf { it.exists() } ?: file("$buildTools/d8")
        check(d8.exists()) { "找不到 d8：$d8" }

        val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        val classFiles = classesDir.walkTopDown().filter { it.extension == "class" }.toList()
        check(classFiles.isNotEmpty()) {
            "补丁模块里没有编译产物。请先在 patch/src/main/kotlin 下写好 PatchEntryImpl"
        }

        val outputDir = layout.buildDirectory.dir("patch").get().asFile.apply { mkdirs() }
        outputDir.listFiles()?.forEach { it.delete() }

        // d8 需要 android.jar 做 library，否则引用 android.* 的类会报缺符号。
        // 宿主类作为 classpath 而不是输入：只是给 d8 解析引用用，不会进 dex。
        val arguments = buildList {
            add("--output"); add(outputDir.absolutePath)
            add("--min-api"); add("24")
            add("--lib"); add(androidJar.absolutePath)
            if (hostClasses.exists()) { add("--classpath"); add(hostClasses.absolutePath) }
            if (sharedClasses.exists()) { add("--classpath"); add(sharedClasses.absolutePath) }
            addAll(classFiles.map { it.absolutePath })
        }
        providers.exec {
            commandLine(listOf(d8.absolutePath) + arguments)
        }.standardOutput.asText.get().takeIf { it.isNotBlank() }?.let { logger.lifecycle(it) }

        val dex = file("$outputDir/classes.dex")
        check(dex.exists()) { "d8 没有产出 classes.dex" }

        val patchVersion = (project.findProperty("patchVersion") as String?)?.toIntOrNull() ?: 1
        val zip = file("$outputDir/patch-$patchVersion.apk")
        ant.withGroovyBuilder {
            "zip"("destfile" to zip.absolutePath, "compress" to true) {
                "fileset"("dir" to outputDir.absolutePath, "includes" to "classes*.dex")
            }
        }
        logger.lifecycle("补丁已生成：${zip.absolutePath}（${zip.length()} 字节，${classFiles.size} 个 class）")
    }
}

/**
 * 列出当前 release APK 里所有可用的方法键。
 *
 * 直接从产物里读而不是从源码推：只有实际插过桩的方法才可能被补丁接管，
 * 从 DEX 里找是唯一不会说谎的口径。DEX 的字符串池是明文的，直接搜就行。
 *
 * 用法：./gradlew :patch:printMethodKeys -Pfilter=TencentMusicApi
 */
val printMethodKeys by tasks.registering {
    group = "热修复"
    description = "列出 release APK 里可被补丁接管的方法键"

    doLast {
        val apk = rootProject.file("androidApp/build/outputs/apk/release/androidApp-release.apk")
        check(apk.exists()) { "先构建 release 包：./gradlew :androidApp:assembleRelease" }

        val filter = project.findProperty("filter") as String?
        val keys = sortedSetOf<String>()
        // 插桩生成的键形如 com/taotao/music/data/Xxx#method(...)Lyyy;
        // DEX 的字符串以 NUL 结尾，必须用它收边 —— 相邻字符串之间没有空格，
        // 只有长度前缀和结束符，不收边会把下一个字符串一起吞进来。
        val nul = Char(0)
        val pattern = Regex(
            "com/taotao/music/(?:data|player|update)/(?:[A-Za-z0-9_$]+/)*[A-Za-z0-9_$]+" +
                "#[A-Za-z0-9_$<>]+[(][^)$nul]*[)][^$nul]{0,80}",
        )
        ZipFile(apk).use { zip ->
            for (entry in zip.entries().toList()) {
                if (!entry.name.matches(Regex("classes[0-9]*[.]dex"))) continue
                val text = zip.getInputStream(entry).readBytes().toString(Charsets.ISO_8859_1)
                for (match in pattern.findAll(text)) keys += match.value
            }
        }
        val shown = if (filter.isNullOrBlank()) keys.toList() else keys.filter { it.contains(filter, true) }
        logger.lifecycle("共 ${keys.size} 个可接管方法" + if (filter.isNullOrBlank()) "" else "，匹配「$filter」${shown.size} 个")
        shown.forEach { logger.lifecycle("  $it") }
    }
}
