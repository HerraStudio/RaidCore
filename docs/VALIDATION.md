# RaidCore 合并验证

日期：2026-10-03（Asia/Shanghai）。

环境：Windows / Java 21 / Minecraft 1.21.1 / NeoForge 21.1.251，GWO Beta1.0 Fix0.5（内部版本 2.12.87）、LDLib2 2.2.40、Photon 2.2.7、PAL 1.1.6。

## 单元测试与正式构建

| 原模块 | 用例 | 失败 / 错误 / 跳过 |
|---|---:|---|
| Drag Inventory 2.6.1 | 181 | 0 / 0 / 0 |
| Tactical Actions 1.1.0 | 22 | 0 / 0 / 0 |
| Tactical Inventory 1.0.20 | 69 | 0 / 0 / 0 |
| 合计 | 272 | 0 / 0 / 0 |

33 个测试类。统一 `clean build` 成功；原生检查后再次执行无测试参数的 `clean build` 成功，使用 Gradle 的未改变输入缓存。发布 JAR 检查通过：一个 `raidcore` 模组描述、三个模块、三套 Mixin、全部关键资源与许可，无原生检查类及第三方二进制。

## 来源和资源一致性

391 条导入文件记录的原始文件 SHA256 与导入前一致；原项目没有修改。除三个初始化方法和事件归属外，原游戏逻辑源码保持。58 个原运行资源在源文件和最终 JAR 中逐字节相同，包括金条、保险箱、Photon 特效、地图箭头和动画。

记录：[来源一致性](source-parity.json)、[测试摘要](validation-results.json)、[原生日志摘要](runtime-validation.txt)。

## 独立服务端

`runServer -PraidCoreSmoke` 在 `run-server-smoke` 运行并自动正常停止，出现 `RAIDCORE_SERVER_SMOKE_PASS`。

- 仅加载 RaidCore，三个旧独立模组未加载。
- 原物品、方块、菜单和体力附件 ID 正确注册；库存与眼位 Mixin 实际应用。
- 玩家基础最大生命值为 100；体力附件可读取。
- 通过原 `tactical_inventory:storage` NBT 键写入并还原库存，金条的自定义名称及完整组件保持。
- `/raid` 和 `/tacticalitems` 命令实际注册。

## 独立客户端

`runClient -PraidCoreSmoke` 使用独立临时平坦世界，隐藏测试窗口并隔离真实键鼠；出现 `RAIDCORE_CLIENT_SMOKE_PASS`，Gradle 正常结束。

- 四份旧客户端配置加载；旧按键注册；100 生命值、体力同步、背包与探头 Payload 通道可用。
- 真实容器拖拽、库存与眼位 Mixin 应用；PAL 探头和趴下工厂实际注册。
- 真实 GWO 模型缓存加载金条与 Blender 的 draw / idle / inspect 动作；I 检视与右键触发、动作矩阵变化、回到待机、左手表现和服务端物品完整性通过。
- LDLib2 物品配置及战术背包实际打开；金条默认传说品质、管理员修改和恢复默认、尺寸变更重排与数量完整性通过。
- 真实左右探头输入通过原 Payload 同步至服务端；双键回正，玩家实体位置保持。测试等待原 GD656 渐近回正曲线完成，不修改动作代码。
- 原 M 按键实际打开并渲染 2.6.1 大地图。

本次无光影金条检查记录 GPU 1241 次、fallback 0、手部绘制 383 帧。截图已检查：[背包](images/raidcore-inventory.png)、[HUD](images/raidcore-hud.png)、[地图](images/raidcore-map.png)。地图截图为测试平坦世界，绿色底图对应草地。

本次验证覆盖合并注册、双端启动与上述交叉功能。没有对全部武器、光影和平台重跑历史完整矩阵；安卓和 Iris 光影不属于本次原生检查。原项目的历史记录保存在 `legacy/`。
