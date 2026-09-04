# Windows 客户端第三方组件说明

桌面客户端使用以下开源组件。发布便携 JAR、EXE 或 MSI 时，应一并保留各组件随发行包提供的许可证与版权声明；版本以 `desktopApp/build.gradle.kts` 和 Gradle 解析结果为准。

| 组件 | 用途 | 许可证/上游 |
| --- | --- | --- |
| JavaCV / JavaCPP | FFmpeg 的 JVM 封装与 native 加载 | Apache License 2.0；[JavaCV](https://github.com/bytedeco/javacv)、[JavaCPP](https://github.com/bytedeco/javacpp) |
| FFmpeg | 音频流与本地文件解码 | 以所解析发行包附带的 LGPL/GPL 声明为准；[FFmpeg Legal](https://ffmpeg.org/legal.html) |
| JavaMediaTransportControls | Windows 系统媒体控件和媒体键 | MIT；[项目仓库](https://github.com/Selemba1000/JavaMediaTransportControls) |
| jbsdiff | Windows 更新器的 BSDIFF 补丁应用 | MIT；[项目仓库](https://github.com/ben-manes/jbsdiff) |

更新服务端的 `bsdiff-wasm` 仅用于生成补丁，不会打进桌面客户端发行包；Windows 更新器使用 Courgette 时，Courgette 可执行文件及其许可证必须随发行包单独提供。

本文件不替代上游许可证。构建或重新分发时请核对实际依赖版本及其 transitive 组件，并将对应 `LICENSE`/`NOTICE` 文件放入发布包。
