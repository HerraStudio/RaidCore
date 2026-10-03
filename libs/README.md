# 本地编译依赖

构建 RaidCore 需要自己的 Minecraft 1.21.1 NeoForge 版 GWO JAR。
把它复制为本目录的 `gwo.jar`。本次使用 Beta1.0 Fix0.5，内部版本 `2.12.87`。

本次依赖 SHA256：`5185db48f31ece2495ed78b1a0f899a29009f8432529820de7361989a65789c8`。

LDLib2 2.2.40、Photon 2.2.7 和 Player Animation Library 1.1.6 由 Gradle 获取。
这些依赖单独安装，不嵌入 RaidCore JAR。`gwo.jar` 不提交，也不进入源码交付包。
