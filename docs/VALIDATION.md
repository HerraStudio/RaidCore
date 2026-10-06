# RaidCore 合并验证

## 1.1.0 发布检查（2026-10-06）

当前源码执行 `clean build`，317项单元测试和发布JAR检查通过。搜刮管理的独立客户端与专用服务器原生检查已通过，客户端加载末日装饰1.1.3及GWO Pickup1.0.1，实测板条箱注册、命名、概率、F交互、原界面、库存保存/共享/不重复生成和捡枪优先级。撤离烟雾已验证无光影Fancy/Fabulous遮挡以及Iris光影路径。共享对局及历史材质/弹孔的验证范围分别保留在下列记录中；本次发布没有重跑所有历史矩阵。[搜刮验证](LOOT_MANAGEMENT.md) · [烟雾验证](EVACUATION_SMOKE.md)。

## 1.1.0 Core Loop M1 共享对局（2026-10-05）

317项单元测试与独立专用服务器、两个实际客户端的自动化4局检查通过。验证共享ID/时钟、等待冻结、不同出生点、每tick仅推进一次、非管理员权限、个人撤离后继续、死亡复活和确认、禁止重入、批量新局、整体停止、共同超时及断线清理；旧客户端和专用服务器烟雾检查回归通过。实际联机测试使用独立平坦世界、脚本驱动两个客户端；真人PVP、工厂场景恢复与带出物资提交尚未完成。详见 [CORE_LOOP_M1.md](CORE_LOOP_M1.md)。

## 1.0.4 Raid 对局倒计时（2026-10-05）

300项单元测试、正式构建与独立客户端/服务端原生检查通过。小地图实际显示 `30:00`，真实快照驱动更新；2秒测试局在第40个 tick 自动超时、返回大厅并显示结算，完整30分钟边界由单元测试覆盖。配置与每局时长保存、旧存档默认30分钟、修改默认值不重置当前局、再次入场及断线清理通过。验证范围、截图和日志见 [RAID_TIMER.md](RAID_TIMER.md)。

## 1.0.3 保险箱光影材质修复 — 2026-10-05

287 项单元测试及正式 `clean build` 通过。使用用户当前 Iris 1.8.14-beta.1、Sodium 0.8.12、Sundial-Lite 配置，在独立 `run-safe-smoke` 实例检查：保险箱的两张 2048×2048 PBR 贴图实际进入 Iris GPU 图集，每张全部 4194304 个像素与资源一致；箱体和活动箱门共用正确材质，开门中间帧、保持打开、搜刮界面、存储持久化与破坏掉落通过。原颜色 PNG、几何与 UV 不变，发布 JAR 的全部生产类与 1.0.2 字节一致。日志见 [safe-material-validation.txt](safe-material-validation.txt)，数字及 JAR SHA256 见 [safe-material-results.json](safe-material-results.json)，截图见 [闭合](images/safe-material-closed.png) / [打开](images/safe-material-open.png)。材质编码依据 [LabPBR 标准](https://shaderlabs.org/wiki/LabPBR_Material_Standard)，转换工具与来源记录见 `model-source/safe/`。

本次检查覆盖当前 Sundial 配置；原生参数为 `runClient -PraidCoreSafeSmoke`，需在独立实例准备 Iris、Sodium 和光影包。1.0.3 修改材质资源，服务端生产类与 1.0.2 相同；本次没有重跑服务端和弹孔原生矩阵，历史记录继续保留。

## 1.0.2 重叠与溅射方向修复

287 项单元测试通过。21 个实际弹孔重新核验溅射沿子弹表面前进方向延伸、孔口锚点、六个朝向和半砖 / 边缘限制。新增墙面与地面各六个密集命中的原生对照，使用同一组弹孔在测试 JVM 中分别启用旧面片排序与修复排序；旧状态复现三角碎块，修复后固定镜头两次截图逐像素一致，且换视角保持完整图层。原生数字见 [decal-overlap-results.txt](decal-overlap-results.txt)，截图及实现说明见 [GWO_DECALS.md](GWO_DECALS.md)。客户端其它功能和独立服务端检查通过；网络格式沿用 1.0.1。

## 1.0.1 新增弹孔验证

287 项单元测试通过（原有 272 项 + 新增 15 项）；独立客户端、专用服务器及 GWO 弹孔原生检查全部通过。21 个实际弹孔逐个核验孔口锚点、非对称拖尾、六个朝向、半砖与边缘空间；孔口不再处于整个变形区域的中心。详见 [GWO_DECALS.md](GWO_DECALS.md)、[decal-geometry.tsv](decal-geometry.tsv) 和 [validation-results.json](validation-results.json)。

1.0.1 与原发布 JAR 比较，399 个旧条目字节不变；旧条目仅统一入口、新模组描述和已由仓库更新的 LICENSE 有变化。新增功能类和 Mixin 另列于 [module-validation.json](module-validation.json)。以下保留 1.0.0 的合并检查范围和历史记录。

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

## 功能模块目录重组

源码、测试与资源已按根目录 ui / inventory / loot / action / player / network / api / raid 分目录，根 Gradle 仍统一编译。调整后 `clean build` 成功，272 项测试全部通过；189 个生产 Java 文件完整，59 个运行资源没有重复路径。

调整前后 JAR 的 402 个文件内容全部逐字节一致，其中 335 个编译类相同；完整 JAR SHA256 均为 `4f1b70d55bd2e9947e24a40e58081cd38359441ddab139c62d848071d1c86c73`。下列客户端/服务端原生记录所验证的 JAR 与本次产物一致。[模块验证结果](module-validation.json)。

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
