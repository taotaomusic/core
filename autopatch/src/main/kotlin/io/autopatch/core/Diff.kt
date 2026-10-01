package io.autopatch.core

/** 两次扫描之间，某个方法发生的变化种类。 */
enum class MethodChangeKind { CHANGED, ADDED, REMOVED }

data class MethodChange(
    val kind: MethodChangeKind,
    val key: String,
    val ownerInternalName: String,
    val name: String,
    val descriptor: String,
    /** head 侧是否可插桩（REMOVED 的方法取 base 侧口径）。 */
    val instrumentable: Boolean,
)

/** 某个类的字段集合发生了增删（热修无法给已安装的类增删字段）。 */
data class FieldChange(
    val ownerInternalName: String,
    val added: Set<String>,
    val removed: Set<String>,
)

data class DiffResult(
    val methodChanges: List<MethodChange>,
    val fieldChanges: List<FieldChange>,
    val addedClasses: Set<String>,
    val removedClasses: Set<String>,
) {
    val changedMethods: List<MethodChange> get() = methodChanges.filter { it.kind == MethodChangeKind.CHANGED }
    val addedMethods: List<MethodChange> get() = methodChanges.filter { it.kind == MethodChangeKind.ADDED }
    val removedMethods: List<MethodChange> get() = methodChanges.filter { it.kind == MethodChangeKind.REMOVED }
}

/**
 * 求 base → head 的差集。
 *
 * base 是「线上那个版本」的编译产物，head 是修好之后的。只比较两边都能读到的类的
 * 方法指纹；类/方法/字段的增删单独记账，交给 [HotfixGate] 判定是否越过热修边界。
 */
fun diff(base: Scan, head: Scan): DiffResult {
    val methodChanges = ArrayList<MethodChange>()
    val fieldChanges = ArrayList<FieldChange>()

    val addedClasses = head.classes.keys - base.classes.keys
    val removedClasses = base.classes.keys - head.classes.keys

    // 只在两边都存在的类里比方法；新增/删除整类单独记在 addedClasses/removedClasses。
    for ((internalName, baseClass) in base.classes) {
        val headClass = head.classes[internalName] ?: continue

        val added = headClass.fields - baseClass.fields
        val removed = baseClass.fields - headClass.fields
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            fieldChanges += FieldChange(internalName, added, removed)
        }

        val allKeys = baseClass.methods.keys + headClass.methods.keys
        for (key in allKeys) {
            val b = baseClass.methods[key]
            val h = headClass.methods[key]
            when {
                b != null && h != null -> if (b.fingerprint != h.fingerprint) {
                    methodChanges += MethodChange(
                        MethodChangeKind.CHANGED, key, internalName, h.name, h.descriptor, h.instrumentable,
                    )
                }
                b == null && h != null -> methodChanges += MethodChange(
                    MethodChangeKind.ADDED, key, internalName, h.name, h.descriptor, h.instrumentable,
                )
                b != null && h == null -> methodChanges += MethodChange(
                    MethodChangeKind.REMOVED, key, internalName, b.name, b.descriptor, b.instrumentable,
                )
            }
        }
    }

    return DiffResult(methodChanges, fieldChanges, addedClasses, removedClasses)
}
