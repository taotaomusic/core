package io.autopatch.cli

import io.autopatch.core.GateConfig
import io.autopatch.emit.EmitConfig
import java.io.File
import kotlin.system.exitProcess

/**
 * 命令行入口。
 *
 * 用法：
 * ```
 * autopatch --base <baseClassesDir> --head <headClassesDir> --out <outDir> \
 *           [--host com/taotao/music] [--allow com/taotao/music/data,com/taotao/music/player]
 * ```
 * base/head 是两份编译产物目录（release 变体的 kotlin-classes）。
 * 判定必须整包时以非零码退出并逐条打印原因；生成成功打印方法键与产物路径。
 */
fun main(rawArgs: Array<String>) {
    val args = parseArgs(rawArgs)
    val base = File(args.required("base"))
    val head = File(args.required("head"))
    val out = File(args.required("out"))
    val hostPrefixes = args.list("host").ifEmpty { listOf("com/taotao/music") }
    val allowPrefixes = args.list("allow")

    val request = CliRequest(
        baseDir = base,
        headDir = head,
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
