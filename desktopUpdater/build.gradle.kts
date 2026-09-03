plugins {
    application
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation("io.sigpipe:jbsdiff:1.0")
}

application {
    mainClass.set("com.taotao.music.updater.UpdaterMain")
}

tasks.jar {
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    archiveFileName.set("taotao-updater.jar")
}

val nativeUpdater by tasks.registering(Exec::class) {
    group = "distribution"
    description = "使用 GraalVM native-image 生成 updater.exe"
    dependsOn(tasks.jar)
    val output = layout.buildDirectory.file("native/updater.exe")
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
