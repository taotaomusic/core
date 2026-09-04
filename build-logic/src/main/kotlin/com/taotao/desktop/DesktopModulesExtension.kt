package com.taotao.desktop

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Windows 模块化发布插件的配置。插件负责拆分运行时 JAR 并生成内容寻址清单。 */
abstract class DesktopModulesExtension @Inject constructor(objects: ObjectFactory) {
    val outputDirectory: DirectoryProperty = objects.directoryProperty()
    val sharedJars: ConfigurableFileCollection = objects.fileCollection()
    val channel: Property<String> = objects.property(String::class.java)
    val versionCode: Property<Int> = objects.property(Int::class.java)
    val versionName: Property<String> = objects.property(String::class.java)
    val architecture: Property<String> = objects.property(String::class.java)
    val entrypoint: Property<String> = objects.property(String::class.java)
    val releaseNote: Property<String> = objects.property(String::class.java)
    val rollout: Property<Int> = objects.property(Int::class.java)
}
