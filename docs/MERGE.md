# 合并范围与兼容策略

本次按用户选择，仅整合截图中的三个模组；部位伤害、GWO 拾取、战术灯、精细方块弹道、爆头兼容和按键锁定未纳入。

## 来源

- Drag Inventory：原仓库 `https://github.com/HerraStudio/DragInventory` 的 `v2.6.1` 标签，提交 `48382c334a72eb6d5a62826b2187b62962474f68`。本地两份旧源码仍为 2.3.2，未采用。原 2.6.1 标签的 `build.gradle` / README 已为 2.6.1，但其原始 `neoforge.mods.toml` 仍写 2.5.7；RaidCore 改由统一版本属性生成描述文件。
- Tactical Inventory：`E:/Codex/2026-09-21/zh/work/tactical-inventory`，1.0.20；使用全部本地修改和未跟踪文件，不以旧远端覆盖。
- Tactical Actions：`E:/Codex/2026-09-25/ba/TacticalActions`，1.1.0；使用全部本地修改和未跟踪文件。

各导入文件的原始 SHA256、来源及目标路径在 `MERGE_SOURCES.json`。原项目工作区没有修改。

## 统一入口

唯一 `@Mod` 为 `dev.herrastudio.raidcore.RaidCore`，Mod ID 为 `raidcore`。该入口调用三个模块的 `register` 方法，完成物品、方块、菜单、体力附件、配置和网络注册。所有自动事件订阅改为归属 `raidcore`，客户端订阅的侧别保持原样。

三份 Mixin 配置在统一描述文件中各声明一次，原包名与 Mixin 顺序保持。正式 JAR 中只有一个 `META-INF/neoforge.mods.toml`，原三个模组的独立描述文件没有合并进入成品。

## 兼容保留

加载器身份与存档/资源身份分开：`raidcore` 是加载器身份，`tactical_inventory`、`draginventory`、`tacticalactions` 是原功能保留的资源命名空间。

这使原玩家战术库存 NBT、物品与方块 ID、体力附件 ID、世界 SavedData 文件、数据包资源、动画资源、网络 Payload ID 和按键名称继续可用。四份现有配置文件名保持，无需在合并时重建用户设置。

`TacticalActions.MOD_ID` 保留为旧资源命名空间常量；它不再用于事件订阅的归属，也不再代表独立加载的模组。

描述文件明确禁止与三个旧独立模组同时加载，以防重复类、注册和 Mixin。其它配套模组继续独立安装。本次没有改变原三个模组的游戏规则，也没有新增对局时长或地图计时联动规则。

## 验证与交付

统一 `clean build` 编译全部代码并执行三个项目的既有单元测试。发布内容检查覆盖单一入口、三套 Mixin 类、原模型与动画、Photon 特效、地图箭头、许可，以及第三方和测试二进制排除。

原生集成验证源集为 `src/integration`。历史检查源码保存在 `verification/{hud,actions,inventory}`，不会被默认编译。执行结果单独记录在 `VALIDATION.md`，历史截图和记录保留在 `legacy/`，不作为本次新验证的证据。
