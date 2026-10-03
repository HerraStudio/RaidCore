# RaidCore 项目交接

日期：2026-10-03（Asia/Shanghai）。

## 当前项目

- 根目录：`E:/Codex/RaidCore`，版本 1.0.0，Mod ID `raidcore`。
- GitHub：`https://github.com/HerraStudio/RaidCore`，公开仓库，默认分支 `main`；后续维护统一在此进行。
- 原 `HerraStudio/DragInventory`、`HerraStudio/TacticalActions`、`HerraStudio/tactical-inventory` 作为历史归档保留，入口说明链接到 RaidCore。
- 仅整合用户选择的三个模组：Drag Inventory 2.6.1、Tactical Actions 1.1.0、Tactical Inventory 1.0.20。
- 一个 Gradle 构建、一个 `@Mod`、一个正式 JAR；188 个原 Java 文件和 1 个统一入口。
- 源码来源、每个导入文件 hash 与兼容策略见 `MERGE.md` / `MERGE_SOURCES.json`。
- 原三个本地项目工作区完整保留；Tactical Inventory 与 Tactical Actions 的未提交开发也已纳入。

## 交付和验证

交付目录：`releases/v1.0.0/`。包含成品 JAR、源码 JAR、完整项目 ZIP、安装说明和 SHA256。

272 项单元测试全部通过；独立客户端和服务端原生检查通过。具体范围、截图和日志摘要见 `VALIDATION.md`。原生检查源码使用独立 `src/integration`，不进入正式 JAR；旧项目的检查代码保存在 `verification/`。

## 开发约定

- 全部功能事件订阅归属 `RaidCore.MOD_ID`。三个旧主类现在是 `register` 初始化模块，不可恢复独立 `@Mod`。
- 保留 `tactical_inventory` / `draginventory` / `tacticalactions` 资源和存档命名空间；改名时必须单独设计迁移。
- 保留原四份配置文件名与按键名称；`TacticalActions.MOD_ID` 仅是兼容资源命名空间。
- `libs/gwo.jar` 是本地第三方依赖，不提交或打入源码交付包。其他依赖由 Gradle 获取。
- 模型工程、素材许可和 Blender MCP 工具均保留；修改动画前阅读对应 `model-source/` 说明与旧交接文档。
- 正式构建：`.\gradlew.bat clean build`。客户端 / 服务端原生检查：`runClient` / `runServer -PraidCoreSmoke`。服务器检查需要在独立测试目录中使用已接受的 EULA。
- 打包：完成构建与原生检查后执行 `python tools/package_release.py`；该工具要求有效测试报告、原生 PASS 和来源一致性记录。

## 安装

把成品 JAR 用于客户端和服务器，移出原三份独立 JAR，保留 GWO、LDLib2；客户端还需 Photon 和 PAL，完整重启。原游戏实例尚未替换模组。

本次没有改写对局时长、输入规则、弹道或其它配套模组。后续联动或游戏逻辑修改应作为明确的新改动实施。
