# RaidCore 1.1.0 — 搜刮管理与共享对局

[GitHub 仓库](https://github.com/HerraStudio/RaidCore) · [提交问题](https://github.com/HerraStudio/RaidCore/issues)

把三个现有模组整合为一个 Minecraft 1.21.1 NeoForge 项目、一个 `raidcore` 模组入口和一个 JAR。

后续开发集中在 RaidCore。原 [DragInventory](https://github.com/HerraStudio/DragInventory)、[TacticalActions](https://github.com/HerraStudio/TacticalActions)、[tactical-inventory](https://github.com/HerraStudio/tactical-inventory) 仓库作为历史归档保留。

| 原项目 | 纳入版本 | 功能 |
|---|---|---|
| Tactical Inventory | 1.0.20，完整本地工作区 | 多格背包、搜刮、稀有度与尺寸配置、搜打撤、保险箱破译、金条检视 |
| Drag Inventory | 2.6.1，原仓库 v2.6.1 标签 | 拖拽、快捷栏与枪械 HUD、体力、100 基础生命值、口袋轮盘、标点、方位条、大地图与小地图、快速切枪 |
| Tactical Actions | 1.1.0，完整本地工作区 | GD656 上半身探头、服务端射击眼位同步、趴下、地面蹲伏与跳跃规则 |

源码按 `ui/`、`inventory/`、`loot/`、`action/`、`player/`、`network/`、`api/`、`raid/` 功能目录组织，共享一个 Gradle 构建和一个 JAR。Java 包名保留，事件订阅统一归属 `raidcore`；统一入口调用各功能注册方法。[模块目录与职责](docs/MODULES.md)。

游戏内搜刮管理：通过背包“搜刮管理”或管理员 `/tacticalloot` 配置已安装模组的任意方块为搜刮容器，包括末日装饰 `doomsday_decoration:acrate_2` 等无原生库存的装饰方块。容器名称和概率系数按类型保存；物品基础爆率在原稀有度/大小页面设置。准星显示 `F 搜刮` 和名称，按 F 使用原搜刮界面。[使用、数据兼容与验证](docs/LOOT_MANAGEMENT.md)。

1.0.2 的 GWO 入射角弹孔：孔口锚定真实命中点，崩边和擦痕沿子弹在表面上的前进方向延伸，孔口仅轻微椭圆化。非线性网格按碰撞表面边缘缩放，重叠弹孔按完整图层叠放。保留 GWO 原材质、光照、淡出和数量限制。[效果、协议与验证](docs/GWO_DECALS.md)。

## 安装与升级

客户端和服务器都安装 `raidcore-1.21.1-neoforge-1.1.0.jar`。升级时替换旧 RaidCore JAR；初次安装时移出原来的 Drag Inventory、Tactical Inventory、Tactical Actions JAR，再安装 RaidCore 并完整重启。

1.1.0 包含游戏内搜刮管理和撤离烟雾遮挡修复。物品配置网络消息已增加基础爆率与掉落池字段，客户端和服务器需要一起更新。[下载最新版](https://github.com/HerraStudio/RaidCore/releases/latest)。

1.1.0 完成 Core Loop M1 共享对局生命周期：`/raid join` 加入等待，管理员 `/raid start` 统一开局；玩家共享对局 ID 和30分钟时钟，个人撤离、死亡和结算独立。一人结束后其余人继续，已结束成员不能重入本局；全员结束后释放对局状态。默认至少2人及2个不同出生点，管理员 `/raid minplayers 1` 可单人调试。[M1说明、验证与后续范围](docs/CORE_LOOP_M1.md)。

1.0.3 补齐保险箱原材质的 LabPBR 法线与金属反射贴图，支持 Iris 及启用了 LabPBR 材质的光影包。颜色、磨损区域、箱门动画与存档数据保持兼容。[材质转换与源文件](model-source/safe/README.md)。

客户端升级到 1.0.2 可修复重叠和方向；服务端 1.0.1 或更新即可提供真实入射方向。旧于 1.0.1 的服务器或客户端继续显示 GWO 原来的圆形弹孔，新增方向通道支持回退。

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
.\gradlew.bat runClient -PraidCoreDecalSmoke
.\gradlew.bat runClient -PraidCoreDecalOverlapSmoke
.\gradlew.bat runClient -PraidCoreSafeSmoke
```

集成检查使用独立 `run-client-smoke` / `run-server-smoke` / `run-decal-smoke` / `run-decal-overlap-smoke`，不进入正式 JAR。具体结果见 [验证记录](docs/VALIDATION.md)。历史验证代码保存在 `verification/`，原项目说明和历史记录保存在 `docs/legacy/`。

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

项目许可证采用当前仓库的 GNU AGPL v3，模型保持 CC BY 4.0。原有 [素材署名](ASSET_LICENSES.md)、[第三方代码说明](THIRD_PARTY_NOTICES.md)、模板许可和各项目 MIT 许可均随 JAR 分发。
