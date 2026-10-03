# network

Payload、网络编解码、收发和选槽同步桥接。

源码位于 `src/main/java`，资源位于 `src/main/resources`，对应单元测试位于 `src/test/java`（按需添加）。

由根目录 Gradle 的 main/test 源集统一编译并打入 RaidCore 的单个 JAR；Java 包名与资源命名空间保持兼容。
启动入口及模组描述文件由项目根目录 `src/main` 统一管理。模块说明见 `../docs/MODULES.md`。
