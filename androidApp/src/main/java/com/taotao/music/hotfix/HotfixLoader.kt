package com.taotao.music.hotfix

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 加载补丁并把分发器挂到目标类上。
 *
 * 只用 `DexClassLoader` 起一个**独立**的类加载器，不去动宿主的 `dexElements` ——
 * 那条路要求 DEX 只读（Android 14 起）、依赖 vdex/oat 生成（Android 16 上已知有问题），
 * 而且必须重启才生效。这里补丁类和宿主类是两套 Class 对象，靠 [PatchDispatcher]
 * 在方法入口分发，所以赋值完立刻生效。
 *
 * 在 `Application.attachBaseContext` 里调用：越早越好，晚于第一次业务调用就白打了。
 * 整个过程被 try 包住，任何异常都退回未打补丁的状态 —— 热修复失败只该让补丁失效，
 * 不能让应用起不来。
 */
object HotfixLoader {
    private const val TAG = "Hotfix"

    /**
     * 插桩阶段在每个可热修类里注入的静态字段名。
     *
     * 用 `$$` 开头是为了不可能和业务代码里的字段撞名。Kotlin 的字符串模板会把 `$`
     * 当插值起始符，所以这里必须转义。
     */
    const val DISPATCHER_FIELD = "\$\$patchDispatcher"

    /**
     * 尝试加载已安装的补丁。
     *
     * @param installedVersionCode 宿主的 versionCode，与补丁登记的目标版本严格相等才加载。
     * @return 生效的补丁版本号；没有补丁或加载失败返回 0。
     */
    fun apply(context: Context, installedVersionCode: Long): Int {
        val store = HotfixStore(context)
        val version = store.patchVersion()
        if (version == 0) return 0

        // 上一次加载后没能平稳跑起来（计数没被清零），说明这个补丁有问题，直接弃用。
        if (store.attempts() > 0) {
            Log.w(TAG, "补丁 $version 上次加载后应用未能正常运行，已弃用")
            store.disable(version, "加载后启动失败")
            store.note("补丁 $version 上次加载后应用没能平稳运行，已自动回滚")
            patchFile(context, version).delete()
            return 0
        }

        // 宿主升级后旧补丁必须失效：它是针对旧代码生成的，方法签名可能已经不存在。
        if (store.targetVersionCode() != installedVersionCode) {
            Log.i(TAG, "补丁 $version 是给版本 ${store.targetVersionCode()} 的，当前 $installedVersionCode，跳过")
            store.disable(version, "宿主版本已变更")
            store.note("补丁 $version 是给版本 ${store.targetVersionCode()} 的，本机已升到 $installedVersionCode，已失效")
            patchFile(context, version).delete()
            return 0
        }

        val file = patchFile(context, version)
        if (!file.isFile || file.length() == 0L) {
            store.disable(version, "补丁文件缺失")
            store.note("补丁 $version 的文件不见了，已弃用")
            return 0
        }

        return try {
            store.beginAttempt()
            load(context, file)
            Log.i(TAG, "补丁 $version 已生效")
            store.note("补丁 $version 启动时加载成功")
            version
        } catch (error: Throwable) {
            // 这里必须捕获 Throwable：补丁类里可能抛 NoSuchMethodError、VerifyError 之类。
            val reason = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "补丁 $version 加载失败", error)
            store.disable(version, reason)
            store.note("补丁 $version 启动时加载失败：$reason")
            file.delete()
            0
        }
    }

    /**
     * 加载一个刚下载好的补丁，立即生效，不必等重启。
     *
     * 这是 Robust 式分发相比替换 DEX 的主要好处。返回是否成功。
     */
    fun applyNow(context: Context, patchVersion: Int): Boolean {
        val file = patchFile(context, patchVersion)
        return try {
            load(context, file)
            Log.i(TAG, "补丁 $patchVersion 已即时生效")
            true
        } catch (error: Throwable) {
            Log.e(TAG, "补丁 $patchVersion 即时加载失败", error)
            HotfixStore(context).disable(patchVersion, error.message ?: error.javaClass.simpleName)
            file.delete()
            false
        }
    }

    private fun load(context: Context, file: File) {
        // Android 14（API 34）起，动态加载的 dex 必须是**只读**的，否则构造类加载器时
        // 直接抛 SecurityException。安装时已经设过一次，这里再兜一次底：旧版本装下的
        // 补丁文件可能还是可写的，升级后第一次启动加载它会失败。
        //
        // 判断只读而不是无条件调用：setReadOnly 在已只读的文件上返回 true，但多一次
        // 系统调用没必要，而且失败时要能区分"本来就设不上"和"刚才没设成"。
        if (file.canWrite() && !file.setReadOnly()) {
            error("补丁文件无法设为只读，Android 14+ 拒绝加载可写的 dex")
        }
        // optimizedDirectory 传应用私有目录：Android 8 起这个参数被忽略，但传 null
        // 在部分低版本 ROM 上会落到 /data/dalvik-cache 取不到写权限。
        val optimized = File(context.filesDir, "hotfix/odex").apply { mkdirs() }
        val loader = DexClassLoader(file.absolutePath, optimized.absolutePath, null, context.classLoader)
        val entry = loader.loadClass(PatchEntry.ENTRY_CLASS)
            .getDeclaredConstructor()
            .newInstance() as PatchEntry
        val dispatcher = entry.dispatcher()

        var applied = 0
        for (target in entry.targets()) {
            // 目标类用**宿主**的加载器取：要改的是宿主那份类的静态字段。
            val hostClass = runCatching { context.classLoader.loadClass(target) }.getOrNull()
            if (hostClass == null) {
                Log.w(TAG, "补丁声明的目标类不存在：$target")
                continue
            }
            val field = runCatching { hostClass.getDeclaredField(DISPATCHER_FIELD) }.getOrNull()
            if (field == null) {
                // 插桩没覆盖到这个类。补丁生成阶段应该拦住，运行时再报一次便于定位。
                Log.w(TAG, "目标类未插桩，跳过：$target")
                continue
            }
            field.isAccessible = true
            field.set(null, dispatcher)
            applied++
        }
        check(applied > 0) { "补丁没有命中任何目标类" }
    }

    /** 补丁文件位置。按版本号分文件，回滚时直接删掉对应那个。 */
    fun patchFile(context: Context, patchVersion: Int): File =
        File(context.filesDir, "hotfix/patch-$patchVersion.apk")
}
