package com.taotao.music.hotfix

/**
 * 补丁的入口。
 *
 * 每个补丁 DEX 里必须有且只有一个实现类，全名固定为 [ENTRY_CLASS] —— 加载器按这个名字
 * 反射实例化，所以补丁和宿主之间只靠这一个约定耦合。
 *
 * [targets] 告诉加载器这个补丁涉及哪些类，加载器据此把 [PatchDispatcher] 赋给
 * 对应类的静态字段。返回类的全名（`com.taotao.music.data.TencentMusicApi` 这种形式）。
 */
interface PatchEntry {
    fun targets(): List<String>

    fun dispatcher(): PatchDispatcher

    companion object {
        /** 补丁入口类的固定全名。生成补丁的工具必须按这个名字产出。 */
        const val ENTRY_CLASS = "com.taotao.music.hotfix.generated.PatchEntryImpl"
    }
}
