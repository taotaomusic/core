package io.autopatch.cli

import io.autopatch.core.GateConfig
import io.autopatch.emit.EmitConfig
import java.io.File
import kotlin.system.exitProcess

/**
 * `--allow` 未传时的默认允许热修包范围。
 *
 * 必须与 build-logic 里 HotfixInstrumentationPlugin 的 includedPackages 保持一致：
 * 插桩只落在 data / player / update 三个业务包，允许范围比它宽（或留空 = 不限制）
 * 时，闸门会放行从未插桩包（如 ui 层）里改出的方法，生成加载即拒的死补丁。
 * 两处是跨构建无法共享常量的重复定义，改一边必须同步另一边。
 */
private val DEFAULT_ALLOWED_PACKAGE_PREFIXES = listOf(
    "com/taotao/music/data/",
    "com/taotao/music/player/",
    "com/taotao/music/update/",
)

/**
 * 命令行入口。
 *
 * 用法：
 * ```
 * autopatch --base <baseClassesDir[,baseClassesDir2...]> --head <headClassesDir[,headClassesDir2...]> \
 *           --out <outDir> [--host com/taotao/music] [--allow com/taotao/music/data,com/taotao/music/player]
 * ```
 * base/head 是两份编译产物目录（release 变体的 kotlin-classes），支持逗号分隔多个
 * 模块目录 —— androidApp 与 shared 各一份，shared 的类不传进来就进不了 diff；
 * 同一内部名不允许出现在两个目录里（扫描合并时会抛错）。判定必须整包时以非零码
 * 退出并逐条打印原因；生成成功打印方法键与产物路径。
 */
fun main(rawArgs: Array<String>) {
    val args = parseArgs(rawArgs)
    val baseDirs = args.requiredList("base").map(::File)
    val headDirs = args.requiredList("head").map(::File)
    val out = File(args.required("out"))
    val hostPrefixes = args.list("host").ifEmpty { listOf("com/taotao/music") }
    val allowPrefixes = args.list("allow").ifEmpty { DEFAULT_ALLOWED_PACKAGE_PREFIXES }

    val request = CliRequest(
        baseDirs = baseDirs,
        headDirs = headDirs,
        outDir = out,
        gateConfig = GateConfig(allowedPackagePrefixes = allowPrefixes),
        emitConfig = EmitConfig(hostPackagePrefixes = hostPrefixes),
    )

    when (val result = AutopatchCli.run(request)) {
        is CliResult.Rejected -> {
            System.err.println("✗ 判定：必须发整包，不能热修。原因：")
            result.reasons.forEach { System.err.println("  - $it") }
            exitProcess(2)
        }
        is CliResult.Generated -> {
            println("✓ 已生成补丁类，覆盖 ${result.patchableKeys.size} 个方法：")
            result.patchableKeys.forEach { println("  - $it") }
            println("产物：")
            result.writtenFiles.forEach { println("  $it") }
            if (result.warnings.isNotEmpty()) {
                println("注意：")
                result.warnings.forEach { println("  ! $it") }
            }
        }
    }
}

private class ParsedArgs(private val map: Map<String, List<String>>) {
    fun required(name: String): String =
        map[name]?.firstOrNull() ?: error("缺少必填参数 --$name")

    /** 取逗号分隔（或重复旗标）的多值参数；一个值都没给时按缺参报错。 */
    fun requiredList(name: String): List<String> =
        list(name).ifEmpty { error("缺少必填参数 --$name") }

    fun list(name: String): List<String> =
        map[name]?.flatMap { it.split(",") }?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}

private fun parseArgs(raw: Array<String>): ParsedArgs {
    val map = HashMap<String, MutableList<String>>()
    var i = 0
    while (i < raw.size) {
        val token = raw[i]
        require(token.startsWith("--")) { "无法识别的参数：$token" }
        val name = token.removePrefix("--")
        require(i + 1 < raw.size) { "参数 --$name 缺少值" }
        map.getOrPut(name) { ArrayList() }.add(raw[i + 1])
        i += 2
    }
    return ParsedArgs(map)
}
