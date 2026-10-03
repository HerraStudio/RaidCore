# RaidCore 1.0.0

[GitHub 仓库](https://github.com/HerraStudio/RaidCore) · [提交问题](https://github.com/HerraStudio/RaidCore/issues)

把三个现有模组整合为一个 Minecraft 1.21.1 NeoForge 项目、一个 `raidcore` 模组入口和一个 JAR。

后续开发集中在 RaidCore。原 [DragInventory](https://github.com/HerraStudio/DragInventory)、[TacticalActions](https://github.com/HerraStudio/TacticalActions)、[tactical-inventory](https://github.com/HerraStudio/tactical-inventory) 仓库作为历史归档保留。

| 原项目 | 纳入版本 | 功能 |
|---|---|---|
| Tactical Inventory | 1.0.20，完整本地工作区 | 多格背包、搜刮、稀有度与尺寸配置、搜打撤、保险箱破译、金条检视 |
| Drag Inventory | 2.6.1，原仓库 v2.6.1 标签 | 拖拽、快捷栏与枪械 HUD、体力、100 基础生命值、口袋轮盘、标点、方位条、大地图与小地图、快速切枪 |
| Tactical Actions | 1.1.0，完整本地工作区 | GD656 上半身探头、服务端射击眼位同步、趴下、地面蹲伏与跳跃规则 |

源码按 `ui/`、`inventory/`、`loot/`、`action/`、`player/`、`network/`、`api/`、`raid/` 功能目录组织，共享一个 Gradle 构建和一个 JAR。Java 包名保留，事件订阅统一归属 `raidcore`；统一入口调用各功能注册方法。[模块目录与职责](docs/MODULES.md)。

## 安装与升级

客户端和服务器都安装 `raidcore-1.21.1-neoforge-1.0.0.jar`。在同一实例中移出原来的 Drag Inventory、Tactical Inventory、Tactical Actions JAR，再安装 RaidCore 并完整重启。

| 依赖 | 版本 | 安装侧 |
|---|---|---|
| Java | 21 | 双端 |
| NeoForge | 21.1.251 至 21.1.x | 双端 |
| GWO | 内部版本 2.12.87，本次使用 Beta1.0 Fix0.5 | 双端 |
| LDLib2 | 2.2.40+，2.2.x | 双端 |
| Photon | 2.2.7+，2.2.x | 客户端 |
| Player Animation Library | 1.1.6+，Minecraft 1.21.1 NeoForge 版 | 客户端 |

旧物品、方块、菜单、附件、网络资源与存档数据键继续使用原命名空间，因此已有 `tactical_inventory:gold_bar`、保险箱、战术库存和 `draginventory:stamina` 不需要改名。原物品配置与对局世界数据继续读取。升级前按通常方式备份重要世界。

以下配置文件继续使用原文件名及原有设置：

- `config/draginventory-compass-client.toml`
- `config/draginventory-map-client.toml`
- `config/draginventory-switch-client.toml`
- `config/tacticalactions-peek-client.toml`

旧按键名称也保留，现有 `options.txt` 中的改键可继续使用。Q/E 探头与原版 Q 丢弃、E 背包的默认冲突沿用原项目规则，按自己的配置分配键位。其它自研配套模组和第三方模组保持独立安装。

## 构建

准备 [本地 GWO 依赖](libs/README.md)，然后使用 JDK 21：

```powershell
.\gradlew.bat clean build
```

成品位于 `build/libs/`。构建会执行三个项目的全部单元测试，并检查 JAR 的唯一模组描述、功能资源、Mixin 完整性、许可证以及测试代码和第三方二进制排除情况。源码 JAR 位于相同目录；包含 Blender 工程与开发文档的完整源码包位于本地交付目录。

独立原生集成检查：

```powershell
.\gradlew.bat runClient -PraidCoreSmoke
.\gradlew.bat runServer -PraidCoreSmoke
```

集成检查使用独立 `run-client-smoke` / `run-server-smoke`，不进入正式 JAR。具体结果见 [验证记录](docs/VALIDATION.md)。历史验证代码保存在 `verification/`，原项目说明和历史记录保存在 `docs/legacy/`。

## 开发定位

- `src/main/java/dev/herrastudio/raidcore/RaidCore.java`：唯一入口。
- `ui/`：背包界面、HUD、地图、方位条、轮盘与渲染。
- `inventory/`：库存、占格、放置规则与物品配置。
- `loot/`：搜刮、保险箱、破译与战利品资产。
- `action/`：探头、趴下、蹲伏、快速切枪与操作限制。
- `player/`：生命值、体力与玩家附件。
- `network/`：Payload 与同步桥接。
- `api/`：对外联动门面、数据类型、监听器与提供者接口。
- `raid/`：对局、撤离、结算、计时与特效。
- `model-source/`：保险箱和金条的可编辑 Blender 工程及导出模型。
- `tools/`：原有 Blender MCP 和素材转换工具。

[合并说明](docs/MERGE.md) · [来源清单](docs/MERGE_SOURCES.json) · [交接文档](docs/HANDOFF.md)

代码采用 MIT，模型保持 CC BY 4.0。原有 [素材署名](ASSET_LICENSES.md)、[第三方代码说明](THIRD_PARTY_NOTICES.md)、模板许可和各项目 MIT 许可均随 JAR 分发。
