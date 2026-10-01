plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "io.autopatch"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // ASM 读/写字节码。大版本对齐主仓 build-logic（9.7），避免 ClassReader 版本报错。
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-tree:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.autopatch.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
