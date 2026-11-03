# 热更新模块随 AAR 下发给宿主的 R8 keep 规则。
#
# -dontobfuscate / -dontoptimize 是全局构建行为，由宿主 androidApp/proguard-rules.pro
# 统一声明；这里只保留插桩域与热修运行时自身的类。
#
# update 包：补丁键是「类名#方法名#签名」逐字节精确匹配，R8 不得改名或删除
# 被插桩的类与方法（与 data/player 两包的 keep 边界同源，见 AGENTS.md）。
-keep class com.taotao.music.update.** { *; }

# hotfix 包：加载器在 Application 启动即运行，可能与设备上遗留的旧补丁包交互，
# 防御性整包保留；跨类加载器反射赋值 $$patchDispatcher 也要求 public 成员不被裁剪。
-keep class com.taotao.music.hotfix.** { *; }
