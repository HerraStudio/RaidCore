# RaidCore 项目交接

日期：2026-10-06（Asia/Shanghai）。

## 当前项目

- 根目录：`E:/Codex/RaidCore`，版本 1.1.0（搜刮管理与 Core Loop M1），Mod ID `raidcore`。
- GitHub：`https://github.com/HerraStudio/RaidCore`，公开仓库，默认分支 `main`；后续维护统一在此进行。
- 原 `HerraStudio/DragInventory`、`HerraStudio/TacticalActions`、`HerraStudio/tactical-inventory` 作为历史归档保留，入口说明链接到 RaidCore。
- 仅整合用户选择的三个模组：Drag Inventory 2.6.1、Tactical Actions 1.1.0、Tactical Inventory 1.0.20。
- 一个 Gradle 构建、一个 `@Mod`、一个正式 JAR；生产文件与测试数量见 `MODULES.md`。
- 生产源码、测试与资源按根目录 `ui/`、`inventory/`、`loot/`、`action/`、`player/`、`network/`、`api/`、`raid/` 组织；模块职责和路径索引见 `MODULES.md` / `module-layout.json`。
- 源码来源、每个导入文件 hash 与兼容策略见 `MERGE.md` / `MERGE_SOURCES.json`。
- 原三个本地项目在合并、GitHub 归档和目录清理后已移入回收站；其源码和本地开发已纳入 RaidCore。

## 交付和验证

1.1.0 同时包含本聊天完成的游戏内搜刮管理及撤离烟雾遮挡修复。管理页默认列出所有模组方块，末日装饰 `doomsday_decoration:acrate_2` 已在实际模组实例中验证配置、F提示、原搜刮界面、共享库存和持久化。物品基础爆率位于原配置页，容器使用独立乘数；物品配置消息升级到版本2，需双端更新。功能与验证见 `LOOT_MANAGEMENT.md` / `EVACUATION_SMOKE.md`。

1.1.0 完成 Core Loop M1：共享RaidMatch的ID、成员与30分钟时钟；加入等待后统一开局，默认2人和2个不同出生点；终结幂等、禁止结束成员重入、全员结束后清理状态并保留结果。schema 2保留旧数据读取，重启中断在途局。317项单元测试及独立专用服+两个实际客户端的自动化4局验证通过，旧客户端/服务端烟雾回归通过。说明、修改清单、配置方法及验证范围见 `CORE_LOOP_M1.md`。下一阶段为M2物资提交与M3场景恢复；真人PVP和工厂地图完整闭环尚待验收。

1.0.4 接入 Raid 对局倒计时：默认30分钟，由服务端 tick 推进，小地图下方显示真实剩余时间；归零结算“行动超时”并自动返回大厅。管理员 `/raid duration [分钟]` 查看或修改未来入场时长，旧世界配置默认30分钟。客户端进入普通世界不再自动启动计时；手动 `/map timer` 保留，外部计时 Provider 优先级保留。300项单元测试与独立客户端/服务端检查通过，说明和证据见 `RAID_TIMER.md`。

1.0.3 修复保险箱光影材质：原 GLB 法线与粗糙度/金属度转换为 LabPBR `_n` / `_s`，脚本 `tools/prepare_safe_pbr.py` 可重建，来源记录 `model-source/safe/safe_pbr.json`。287 项单元测试、正式构建和用户当前 Sundial 配置的独立 `runClient -PraidCoreSafeSmoke` 检查通过；两张图全部 GPU 像素、箱门动画、搜刮与持久化已核验。记录 `safe-material-validation.txt` / `safe-material-results.json`。全部生产类与 1.0.2 相同，本次未重跑服务端及弹孔原生检查。成品 JAR、源码 JAR、安装说明和 SHA256 在 `releases/v1.0.3/`；游戏实例尚未自动替换。

历史完整交付目录：`releases/v1.0.2/`。包含成品 JAR、源码 JAR、完整项目 ZIP、安装说明和 SHA256。

287 项单元测试全部通过；独立客户端和服务端原生检查通过。具体范围、截图和日志摘要见 `VALIDATION.md`。原生检查源码使用独立 `src/integration`，不进入正式 JAR；旧项目的检查代码保存在 `verification/`。

## 开发约定

- 全部功能事件订阅归属 `RaidCore.MOD_ID`。三个旧主类现在是 `register` 初始化模块，不可恢复独立 `@Mod`。
- 各模块通过根 Gradle 的 main / test 源集一次编译。Java 包名保留，模块间可使用原类型直接引用；模组描述和 Mixin 配置在根 `src/main/resources`。
- 保留 `tactical_inventory` / `draginventory` / `tacticalactions` 资源和存档命名空间；改名时必须单独设计迁移。
- 保留原四份配置文件名与按键名称；`TacticalActions.MOD_ID` 仅是兼容资源命名空间。
- `libs/gwo.jar` 是本地第三方依赖，不提交或打入源码交付包。其他依赖由 Gradle 获取。
- 模型工程、素材许可和 Blender MCP 工具均保留；修改动画前阅读对应 `model-source/` 说明与旧交接文档。
- 正式构建：`.\gradlew.bat clean build`。客户端 / 服务端原生检查：`runClient` / `runServer -PraidCoreSmoke`。服务器检查需要在独立测试目录中使用已接受的 EULA。
- 打包：完成构建与原生检查后执行 `python tools/package_release.py`；该工具要求有效测试报告、原生 PASS 和来源一致性记录。

## 安装

把成品 JAR 用于客户端和服务器，移出原三份独立 JAR，保留 GWO、LDLib2；客户端还需 Photon 和 PAL，完整重启。原游戏实例已经使用 RaidCore；升级时替换旧 RaidCore JAR。

1.0.1 在 ui / network 中加入 GWO 入射角弹孔：孔口锚定真实命中点，破损沿子弹在表面上的前进方向延伸；1.0.2 关闭弹孔小面片混排，修复重叠三角碎块。独立原生检查 `runClient -PraidCoreDecalSmoke` 的 21 个实际弹孔通过；具体实现、截图和协议见 GWO_DECALS.md。没有改写对局时长、输入规则、弹道碰撞或其它配套模组。后续联动或游戏逻辑修改应作为明确的新改动实施。
