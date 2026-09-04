plugins {
    application
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

application {
    mainClass.set("com.taotao.music.launcher.LauncherMain")
}

tasks.jar {
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    archiveFileName.set("taotao-launcher.jar")
}

val nativeLauncher by tasks.registering(Exec::class) {
    group = "distribution"
    description = "使用 GraalVM native-image 生成小型 launcher.exe"
    dependsOn(tasks.jar)
    val output = layout.buildDirectory.file("native/launcher.exe")
    outputs.file(output)
    doFirst {
        output.get().asFile.parentFile.mkdirs()
        val configured = providers.gradleProperty("nativeImagePath").orNull
            ?: System.getenv("NATIVE_IMAGE_PATH")
            ?: "native-image"
        commandLine(
            configured,
            "--no-fallback",
            "-H:+UnlockExperimentalVMOptions",
            "-H:-UnlockExperimentalVMOptions",
            "-H:Name=${output.get().asFile.absolutePath.removeSuffix(".exe")}",
            "-jar",
            tasks.jar.get().archiveFile.get().asFile.absolutePath,
        )
    }
}
