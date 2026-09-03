plugins {
    `kotlin-dsl`
}

dependencies {
    // AGP 的 Instrumentation API（AsmClassVisitorFactory）在这个构件里。
    // 版本要与根项目一致，见根 build.gradle.kts。
    compileOnly("com.android.tools.build:gradle:8.7.3")
    // ASM 用来改字节码。AGP 8.7 内部用的是 ASM 9.x，这里对齐大版本避免 ClassReader 版本报错。
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
}

gradlePlugin {
    plugins {
        create("hotfixInstrumentation") {
            id = "com.taotao.hotfix"
            implementationClass = "com.taotao.hotfix.HotfixInstrumentationPlugin"
        }
        create("desktopModules") {
            id = "com.taotao.desktop-modules"
            implementationClass = "com.taotao.desktop.DesktopModulesPlugin"
        }
    }
}
