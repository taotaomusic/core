package com.taotao.desktop

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import java.io.File
import java.security.MessageDigest

/**
 * 生成 Windows 客户端的模块目录和发布清单。
 *
 * 应用 JAR、共享模块和运行时依赖保持独立文件；发布系统可据此复用未变化的对象，
 * 而不必每次重新上传整个 uber-JAR。
 */
class DesktopModulesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("desktopModules", DesktopModulesExtension::class.java)
        extension.outputDirectory.convention(project.layout.buildDirectory.dir("desktop-update"))
        extension.channel.convention("release")
        extension.architecture.convention("windows-x64")
        extension.entrypoint.convention("taotao-app.jar")
        extension.releaseNote.convention("")
        extension.rollout.convention(0)

        val prepare = project.tasks.register("prepareDesktopModules", Sync::class.java) {
            group = "distribution"
            description = "按 core/framework/natives 目录规则生成 Windows 模块 JAR"
            dependsOn(project.tasks.named("jar"))
            into(extension.outputDirectory.map { it.dir("current") })
            from(project.tasks.named("jar")) {
                rename { "taotao-app.jar" }
            }
            from(extension.sharedJars)
            from(project.configurations.named("runtimeClasspath")) {
                include("*.jar")
                eachFile { path = name }
            }
            duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
        }

        project.tasks.register("generateDesktopManifest") {
            group = "distribution"
            description = "为 Windows 模块生成包含 SHA-256 的发布清单"
            dependsOn(prepare)
            val manifestFile = extension.outputDirectory.map { it.file("manifest.json") }
            inputs.dir(extension.outputDirectory.map { it.dir("current") })
            outputs.file(manifestFile)
            doLast {
                val current = extension.outputDirectory.get().asFile.resolve("current")
                val files = current.listFiles().orEmpty()
                    .filter(File::isFile)
                    .sortedBy(File::getName)
                    .map { file ->
                        val category = when {
                            file.name.contains("ffmpeg", ignoreCase = true) ||
                                file.name.contains("javacpp", ignoreCase = true) -> "natives"
                            file.name == "taotao-app.jar" || file.name.startsWith("shared") -> "core"
                            else -> "framework"
                        }
                        DesktopManifestFile(file.name, category, file.length(), sha256(file))
                    }
                require(files.isNotEmpty()) { "模块目录为空，无法生成 Windows 清单" }
                require(files.any { it.path == extension.entrypoint.get() }) {
                    "entrypoint 不在模块目录中：${extension.entrypoint.get()}"
                }
                val json = buildString {
                    appendLine("{")
                    appendLine("  \"channel\": \"${escape(extension.channel.get())}\",")
                    appendLine("  \"versionCode\": ${extension.versionCode.get()},")
                    appendLine("  \"versionName\": \"${escape(extension.versionName.get())}\",")
                    appendLine("  \"architecture\": \"${escape(extension.architecture.get())}\",")
                    appendLine("  \"entrypoint\": \"${escape(extension.entrypoint.get())}\",")
                    appendLine("  \"releaseNote\": \"${escape(extension.releaseNote.get())}\",")
                    appendLine("  \"rollout\": ${extension.rollout.get()},")
                    appendLine("  \"files\": [")
                    files.forEachIndexed { index, file ->
                        val comma = if (index + 1 == files.size) "" else ","
                        appendLine("    {\"path\": \"${escape(file.path)}\", \"category\": \"${file.category}\", \"size\": ${file.size}, \"sha256\": \"${file.sha256}\"}$comma")
                    }
                    appendLine("  ]")
                    appendLine("}")
                }
                manifestFile.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(json)
                }
            }
        }
    }

    private data class DesktopManifestFile(val path: String, val category: String, val size: Long, val sha256: String)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun escape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }
}
