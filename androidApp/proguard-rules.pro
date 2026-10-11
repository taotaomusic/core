# ===== 桃桃音乐 R8 规则 =====
#
# 本包与热修复插桩（build-logic，data/player/update 三包）共存，边界三条：
#   1. 不混淆——补丁键是「类名#方法名#签名」精确匹配，改名即全体系失配；
#   2. 不内联——R8 把方法体并进调用方后，补丁替换的原方法已不存在；
#   3. 插桩三包整包 keep——R8 不得删除任何被插桩的类与方法。
# R8 仍照常做：未用代码收缩（第三方库树为主，占体积大头）、资源收缩、
# 非内联类优化。第三方库自带 consumer rules 照常生效。
#
# 交付取向（与 AGENTS.md 可追踪原则一致）：不加混淆时崩溃堆栈可读，
# CrashReporter 落盘日志无需 mapping.txt 即可定位。

# ---- 热修复共存三条件 ----
-dontobfuscate
-dontinline
-keep class com.taotao.music.data.** { *; }
-keep class com.taotao.music.player.** { *; }
-keep class com.taotao.music.update.** { *; }

# ---- JNI 绑定：libtaotao_crypto.so 按「类名+方法名」注册/查找 native 方法，
#      改名或删除都会 UnsatisfiedLinkError，整域保留。 ----
-keep class com.taotao.music.crypto.** { *; }

# ---- 热修复运行时：加载器在 Application 启动即运行，可能与设备上遗留的
#      旧补丁包交互，防御性保留。 ----
-keep class com.taotao.music.hotfix.** { *; }

# ---- 悟空 IM SDK：外部 SDK 自带本地消息库与长连接栈，可能经反射访问实体
#      与回调，防御性整包保留（体积由主收缩兜底）。 ----
-keep class com.xinbida.wukongim.** { *; }
-dontwarn com.xinbida.wukongim.**

# ---- 枚举 values()/valueOf() 反射兜底（显式声明，覆盖所有枚举） ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
