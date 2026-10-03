# Tactical Inventory 项目交接

交接日期：2026-10-03（Asia/Shanghai）。面向接手开发者和下一轮开发任务。

## 1. 先看这几项

1. 主项目位于 `E:\Codex\2026-09-21\zh\work\tactical-inventory`，当前源码、构建和桌面安装版本为 **1.0.20**。
2. 本地功能已推进到稀有度配置、搜打撤、保险箱破译和金条检视；**GitHub 仍是 v1.0.13**。大量修改及新文件未提交，不要重置工作区或用远端覆盖本地。
3. 最近的金条贴图/PBR 修复已在 Windows 的无光影和 Sundial 实例检查。69 项单元测试通过。**安卓环境尚未验证，不能把桌面验证当作移动端兼容承诺。**
4. 用户随后提供了一份 ZalithLauncher 安卓启动日志。已读到依赖错误和 CustomSkinLoader 缺类，尚未修复；它没有进入金条渲染阶段。
5. 动画必须继续用 **Blender MCP** 制作；保留原模型、材质、可编辑 `.blend` 和实际导出的 GLB 动作。

## 2. 路径、版本与交付物

| 用途 | 位置 / 状态 |
|---|---|
| 源码根目录 | `E:\Codex\2026-09-21\zh\work\tactical-inventory` |
| 本地游戏实例 | `D:\MineCraft\.minecraft\versions\1.21.1-NeoForge_21.1.251` |
| 已安装模组 | 上述实例 `mods\tactical-inventory-1.21.1-neoforge-1.0.20.jar` |
| 本地交付目录 | 项目 `releases\v1.0.20` |
| 源码包 | `releases\v1.0.20\tactical-inventory-1.0.20-project.zip` |
| 构建依赖 | `libs\gwo.jar`，当前为用户实际 GWO Beta1.0 Fix0.5 |
| Blender | `D:\Blender\blender.exe`，5.2.2 LTS |
| 金条原始输入 | `D:\下载\fine_gold_bar.glb` |
| 临时验证 / 日志 | `.audit\`，Git 忽略，不能作为公开发布内容 |

当前安装包 SHA256：

```text
9c641c9dd2ba235311b84e3f35e9d76314abec4c446dec8cfa43d4cf174a64b0
```

v1.0.20 源码包 SHA256：

```text
95a4cc9fd5c4770a86ac0df130e081d6af3f0e6c0bf8818eecb540df5a48fa5b
```

本交接文档晚于上述源码包生成；文档及后续编辑不在该已归档 ZIP 内。重新发布时重新生成包和校验，不要把旧校验用于新文件。

## 3. 技术栈与实际依赖

| 组件 | 当前配置 |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| NeoForge 编译版本 | 21.1.251 |
| 模组声明范围 | NeoForge `[21.1.251,21.2)` |
| ModDev Gradle | 2.0.147 |
| 模组 ID | `tactical_inventory` |
| GWO | 内部版本 `2.12.87`，当前本地 JAR 是 Beta1.0 Fix0.5 |
| LDLib2 | 编译 2.2.40；声明 `[2.2.40,2.3)`，**双端必需** |
| Photon | 编译 2.2.7；声明 `[2.2.7,2.3)`，客户端必需 |
| Iris 实测 | 1.8.14-beta.1+mc1.21.1 |
| Sodium 实测 | 0.8.12+mc1.21.1 |
| 光影实测 | 用户的 `Sundial-Lite-main.zip` 及其设置 |

`libs\gwo.jar` 当前 SHA256 为 `5185db48f31ece2495ed78b1a0f899a29009f8432529820de7361989a65789c8`。它是本地第三方依赖，不能提交或放进源码交付包。LDLib2 与 Photon 由 Gradle 获取。

## 4. 已完成的功能及需要保持的行为

### 背包、拖拽和搜刮

- E 打开 LDLib2 战术背包，军蓝磨砂背景；胸挂、五个口袋、背包分别显示容量长条。
- 网格为连续 28×28 布局，没有格缝，保留细分隔线；背包底色 `#223A54`。物品 hover 不弹原版 tooltip。
- 多格物品按完整矩形处理，锚点来自根格左上角，不能用按下的子格替代根格。
- 浮动图标按鼠标减去整体偏移移动，没有跟手框、数量或多余背景。原槽保留图标并叠加灰色遮罩。
- 高光按整体根格及占格显示，绿表示可放、红表示不可放；预览与实际放置共用校验。
- 被覆盖物品自动回填到来源或目标可用区域，支持自动旋转和回溯；整笔可行才提交，不移动无关物品。
- 数量、名称、弹药、附件和其他组件必须保留；非法请求、过期修订和无法完整放下的操作不得产生复制或删除。
- 原版背包储物区没有额外容量。迁移或重新配置后放不下的物品进入“待整理”，该区只可取出。
- 胸挂独立小袋的放置限制继续保留，视觉连续网格不等于取消小袋规则。

### 物品稀有度和占格配置

入口：背包顶部“物品配置”或 `/tacticalitems`。管理员权限等级 ≥2 可保存，普通玩家只读预览。

| 稀有度 | 色值 |
|---|---|
| 普通 | `#FFFFFF` |
| 精良 | `#4CAF50` |
| 稀有 | `#2196F3` |
| 史诗 | `#9C27B0` |
| 传说 | `#FFC107` |

- 菜单支持名称/ID 搜索、宽高各 1–6 格、实时占格与模型预览、旋转预览、保存、恢复默认。
- 物品边框、名称和内部背景光效按稀有度显示；未揭示搜刽物品不泄露稀有度。
- 按世界保存和同步；草稿不提前改变库存。服务端检查权限、ID、尺寸及修订号。
- GWO 型号用 `GunData.contentId` 区分，精确型号优先于基础物品规则。
- 尺寸与拖拽/服务端放置共用 `Rules.size`。更新后保留合法根位置，冲突安全重排，放不下存入待整理。
- 服务端和客户端配置快照分开，不能在集成服务器里互相污染。
- 世界数据文件：主维度 `data\tactical_inventory_item_profiles.dat`。

### 搜打撤

- `/raid` 管理命令配置大厅、出生点、方形撤离范围；`/raid join` 进图，撤离/死亡后结算。
- 默认连续停留 20 秒，离开或更换撤离点重置计时；顶部撤离计时有动效。
- Photon 特效资源：`tactical_inventory:evacuation_smoke`。
- 对局死亡仅保留近战武器，其余物资和装备掉落，覆盖 `keepInventory`；丢失容量的保留近战进入待整理。
- 配置未完成不自动开始。断线/重启中断未完成会话，重登不继承撤离进度。

### 保险箱与破译

- `tactical_inventory:safe`，27 格；使用 Simple Safe 模型，世界尺寸曾按用户要求放大一倍。
- 放置默认关闭，开启后永久敞开，退出搜刮或其他玩家访问不关门。
- 当前关闭箱入口是 F 破译；成功后播放约 16 tick / 0.8 秒开门动作，再进入搜刮。
- LDLib2 破译：空格/左键判定；成功 3 次，绿区 60°→45°→30°、逐渐加速；失误 3 次失败，冷却 3 秒。
- 服务端生成种子，用 LDLib2 `@DescSynced` 字段同步，按实时时钟重算；客户端不提交权威结果。
- 期间锁移动、武器/道具使用及 GWO 动作入口，附近机械噪音；受伤抖动且有 35% 中断概率。
- 奖励只生成一次且在箱内；满箱待存奖励有持久化保护，拆放已解锁箱不能刷新奖励。

### 金条与高品质战利品检视

- ID：`tactical_inventory:gold_bar`；默认传说金色、1×1、最大堆叠 1。品质和尺寸仍能由管理员覆盖/恢复。
- 创造栏或 `/give @s tactical_inventory:gold_bar` 获取；保险箱破译奖励有独立 25% 金条概率。
- 用户最终指定的是 **Fine Gold Bar / Incg5764**，不是最初的 Glimmer Gold bars。
- 使用一根模型，188 个导出顶点、228 个三角形，长度拟合为 26 cm，原材质为 2048×2048。
- 持有后拿出/待机，GWO 当前检视键（默认 I）或右键空处触发约 2.6 秒检视。重复输入不无限重启。
- 使用玩家自己的皮肤双手；左右主手兼容。打开菜单、切物品、死亡或断线停止。
- 金条是独立战利品，不注册为枪械，不写枪械内容 ID、弹药或附件。检视是客户端表现，不修改服务端物品。
- GWO 复用：`GltfModelCache`、`AnimationPose` / `GunAnimationCache`、`BufferedGltfModelRenderer.renderBoneMatrices`；无法走 GPU 时用 GWO 三角形降级。
- 手臂来自 Minecraft 玩家皮肤渲染，与 Blender 手腕曲线同步；不是完整复用 GWO 枪械手臂状态机。

## 5. 代码定位

以下路径均相对于源码根目录 `src/main/java/dev/tactical`。

| 领域 | 入口 / 关键文件 |
|---|---|
| 物品、方块、菜单注册 | `Tactical.java` |
| 尺寸、容量、槽位过滤 | `Rules.java` |
| 持久化库存与尺寸更新迁移 | `BagState.java` |
| 菜单、修订和权威操作 | `BagMenu.java`、`Packets.java` |
| 原子替换 / 回填 | `BagPlacement.java`、`PlacementPlanner.java`、`Grid.java` |
| 背包 UI、布局、整体锚点 | `client/BagScreen.java`、`BagLayout.java`、`WholeItemDrag.java` |
| 搜刮容器、布局和搜索状态 | `loot/LootSession.java`、`LootPacking.java`、`client/LootView.java` |
| 稀有度、型号 key、侧别快照 | `profile/Rarity.java`、`ItemProfile.java`、`ItemProfiles.java` |
| 配置保存、权限和网络 | `profile/ProfileSavedData.java`、`ProfileService.java`、`ProfilePackets.java` |
| 可视化配置 / 预览 | `profile/client/ProfileScreen.java`、`ProfileCatalog.java`、`ItemVisuals.java` |
| 对局与结算 | `raid/`，管理入口 `RaidCommands.java` |
| 保险箱状态和开门 | `loot/SafeBlock.java`、`SafeBlockEntity.java`、`SafeDoorMotion.java`、`client/SafeRenderer.java` |
| 破译服务与界面 | `crack/CrackService.java`、`CrackRules.java`、`CrackPackets.java`、`crack/client/CrackScreen.java` |
| 破译限制入口 | `mixin/CrackInputLockMixin.java`、`CrackGwoLockMixin.java` |
| 战利品定义与动作选择 | `loot/InspectableLootItem.java`、`LootInspectMotion.java` |
| GWO 模型/PBR 绘制 | `client/LootItemRenderer.java` |
| 检视输入 / 皮肤手臂 / 取消 | `client/LootInspectClient.java` |
| 模型/渲染/菜单客户端注册 | `client/ClientEvents.java` |

资源入口：

- `src/main/resources/assets/tactical_inventory/models/loot/gold_bar.glb`
- `src/main/resources/assets/tactical_inventory/models/item/gold_bar.json`
- `src/main/resources/assets/tactical_inventory/textures/item/gold_bar*.png`
- `src/main/resources/data/tactical_inventory/loot_table/chests/safe_decrypted.json`
- `src/main/resources/tactical_inventory.mixins.json`

库存存于玩家持久化 NBT `tactical_inventory:storage`；网格 entry ID ≥100，area 0 胸挂、1 背包、2 待整理；搜刮独立 area 3、物品 ID ≤−2。不要重定义这些编号而不做迁移。

## 6. 金条材质问题与验证边界

用户曾反馈“金条像没贴图，光影下也不反光”。原模型基础颜色较平坦，很多细节在法线图；单独声明法线/粗糙度/金属度资源不足以保证 Iris 正确绑定。

v1.0.20 已补齐：

- 光影 albedo：`gold_bar_color.png`。
- Iris 同名法线：`gold_bar_color_n.png`，沿用原法线 XY，提供 LabPBR AO/高度通道。
- Iris 同名镜面：`gold_bar_color_s.png`，粗糙度转平滑度、金导体编码 231、无自发光。
- 原始独立 normal / roughness / metallic 等贴图保留。
- GUI 和无光影 albedo：`gold_bar_vanilla_color.png`，由 Blender MCP 材质烘焙。

开光影时 `LootItemRenderer` 依据 Iris 与 render pass 使用实时 PBR；GUI/无光影用烘焙图。勿将烘焙高光当作实时反射，也勿只用 GPU 计数宣称所有光影视觉均已验收。

Windows 检查确认 Iris 使用真实 normal/specular holder，不是默认单色图，GPU 绘制无降级。用户实际新版本的主观视觉验收、其他光影包、安卓 GL 转译后端均不应默认已通过。

参考截图与证据：`docs/images/gold-held.png`、`gold-item-preview.png`、`gold-shader-held.png`、`gold-shader-inspect.png`，以及 `docs/gold-runtime-validation.txt`。

## 7. Blender MCP 与资产来源

| 资产 | 来源 / 许可 | 可编辑文件 |
|---|---|---|
| 保险箱 | avhatar / Simple Safe，CC BY 4.0 | `model-source/safe/safe_opening.blend` |
| 金条 | Incg5764 / Fine Gold Bar，CC BY 4.0 | `model-source/gold_bar/gold_bar_inspection.blend` |

完整署名和改动在 `ASSET_LICENSES.md`，该文件随 JAR 分发。代码 MIT 不替代模型许可。最初 Glimmer 模型的 NC 许可不适用于当前已替换的 Fine Gold Bar，但不能把其旧素材混回发布包。

Blender 使用官方 Blender Lab MCP 1.0.3，本机端点 `127.0.0.1:9876`。桥接 `tools/blender_mcp_client.py` 发送 NUL 分隔的 JSON execute 请求。在本次交接检查时未发现 Blender 进程；接手时先确认 Blender 和 MCP 服务已启动。

金条脚本：

1. `tools/import_gold_bar.py`：从原 GLB 提取一根模型、拟合尺寸、保留 UV、转换材质通道。
2. `tools/author_gold_blender.py`：通过 MCP 在 Blender 中创建拿出/待机/检视动作。
3. `tools/refine_gold_blender.py`：独立手腕通道，避免手臂整块随金条翻转。
4. `tools/bake_gold_blender.py`：在 Blender 中烘焙 GUI/无光影材质。
5. `tools/prepare_gold_runtime.py`：保留真实动画抽样，去掉重复嵌入贴图，并将起始时刻归零。
6. `tools/prepare_gold_pbr.py`：生成 Iris LabPBR `_n` / `_s`。

Blender 脚本读取调用方传入的 `PROJECT_ROOT`；`author` 应在干净任务场景运行，`refine` 依赖现有 `loot_root`、`gold_bar`、`hand_right`、`hand_left`，不要在已精修工程上反复执行造成重复 NLA track。

动作记录：40 fps；idle 1.5 s、draw 0.35 s、inspect 2.6 s。编辑工程、完整动画 GLB、输入单模型 GLB、来源 hash 及元数据都在 `model-source/gold_bar/`。

**已踩过的 Blender 问题：**在 MCP 执行中创建/序列化第二个 Scene 曾触发 Blender 5.2.2 访问冲突。恢复后采用当前场景、独立集合、保留启动对象并 `save_as_mainfile`，保存成功。不要重走删除活动场景/切场景/序列化新 Scene 的路径，也不要覆盖用户未保存的工作。

## 8. 构建、验证和打包

在源码根目录、JDK 21 环境执行：

```powershell
.\gradlew.bat clean build
```

产物在 `build\libs`。当前测试总数为 69。文档变更无需重跑整套游戏测试；涉及数据、网络、渲染或动画的变更应运行对应检查。

独立原生检查：

| 参数 | 测试源码 | 实例目录 |
|---|---|---|
| `-PbagSmoke` | `src/smoke/java` | `run-ui-smoke` |
| `-PraidSmoke` | `src/raidSmoke/java` | `run-raid-smoke` |
| `-PsafeSmoke` | `src/safeSmoke/java` | `run-safe-smoke` |
| `-PcrackSmoke` | `src/crackSmoke/java` | `run-crack-smoke` |
| `-PprofileSmoke` | `src/profileSmoke/java` | `run-profile-smoke` |
| `-PgoldSmoke` | `src/goldSmoke/java` | `run-gold-smoke` |

示例：`.\gradlew.bat runClient -PgoldSmoke`。首次独立实例需在自己的 `options.txt` 写 `onboardAccessibility:true`，可加 `lang:zh_cn`、`tutorialStep:none`。测试窗口会隐藏并屏蔽真实输入，创建独立临时世界。

测试目录已回收，不要误以为现有截图来自仍运行的测试客户端。Shader 检查需向独立测试实例准备用户已有 Iris、Sodium 和光影包；不要修改真实游戏设置或分发这些第三方内容。

**原生参数会把测试源码加入 main source set。每次原生检查后，正式发布必须再次无参数 `clean build`，并检查 JAR 不含 `GoldSmoke` / `ProfileSmoke` / 其他 Smoke 类。**

现有本地打包脚本 `.audit/package_gold_release.py`：检查 69 项测试和模型/贴图，生成 v1.0.20 JAR、完整项目 ZIP、说明与 SHA256。后续版本不能照抄其固定版本号/测试计数。`git ls-files` 可能列出已删除旧模型，打包时要确认文件确实存在。

公开包排除 `libs/gwo.jar`、其他模组 JAR、光影 ZIP、授权内容、游戏存档、`.audit`、缓存和日志。`run/` 原目录不是测试临时目录，不能统一删除。清理自己创建的 `run-*` 时核对绝对路径、重解析点和进程占用，采用回收站。

## 9. Git 与发布状态

2026-10-03 已通过远端只读查询核对：

- 仓库：[HerraStudio/tactical-inventory](https://github.com/HerraStudio/tactical-inventory)，公开。
- 本地 main 与远端 main：`b72f7724ec99bf3cc2f10d116f15579bf3736d6a`，初版 v1.0.13。
- GitHub 最新 Release 仍是 [v1.0.13](https://github.com/HerraStudio/tactical-inventory/releases/tag/v1.0.13)。
- v1.0.14–v1.0.20 的功能和大量素材尚在工作区，未提交/推送；本地 `releases/` 被 Git 忽略。

后续如要发布，先检查所有 modified/untracked 文件，保留其他任务已有修改，过滤第三方及私有内容，再提交、推送和创建版本。不要因为仓库远端旧就从远端覆盖完整本地实现。本次“写交接文档”没有提交或重新发布 Release。

## 10. 最新安卓日志：明确待处理

用户最后上传 `D:\下载\latest_game.log`，读取时记录的是 **Android / ZalithLauncher 2.6.1 / MobileGlues / NeoForge 21.1.240**。交接时该文件已经不在原路径，以下依据前一轮实际读取的记录；下一步诊断需恢复日志或取得新启动日志，不要假称仍可读取原文件。

明确记录：

```text
draginventory requires neoforge [21.1.251,21.2); actual 21.1.240
draginventory requires ldlib2 [2.2.40,2.3); actual [MISSING]
NoClassDefFoundError: customskinloader/fake/itf/IFakeIResourceManager$V1
```

另有 `sodium_extra` 的 `accesstransformer.cfg` 不存在提示。它是否另行致命尚未确定，不能代替前面的依赖和缺类分析。

建议接手顺序：

1. 先对齐 Minecraft 1.21.1 的 NeoForge 版本，至少满足相关模组声明的 21.1.251；补齐对应 NeoForge 版 LDLib2 2.2.x。
2. 核对安卓实例 CustomSkinLoader 的模组 JAR 与 bootstrap/启动配置是否匹配。缺类的具体原因尚未定位，不要直接归咎于金条渲染。
3. 再检查 Sodium Extra 与当前 Sodium/NeoForge/移动端启动器的组合。
4. 能进主菜单、世界以后，再检查 GWO / Iris / MobileGlues 的能力与金条 fallback。

当前未对安卓实例安装、删改模组或修改启动配置，未制作安卓兼容修复版。不要仅降低 `neoforge.mods.toml` 的最低版本就宣称兼容，也不要把缺失类的问题通过伪造空接口掩盖。

日志带启动参数、账号和路径信息；后续引用时只保留错误片段，不公开完整原日志或输出 accessToken。

## 11. 其他相关项目，避免改错仓库

| 项目 | 已定位源码 / 当前桌面安装 |
|---|---|
| Drag Inventory | `E:\Codex\2026-09-23\jiang\work\drag-inventory` 的 build.gradle 是 **2.3.2**；桌面已安装 **2.6.1**，两者不一致，最新源码位置待确认 |
| 不完整方块碰撞 | `E:\Codex\2026-09-30\gwo-precise-blocks`；安装 1.0.3 |
| 爆头兼容 | `E:\Codex\2026-10-02\gwo-killicon-headshot`；安装 1.0.0，另有 [独立仓库](https://github.com/HerraStudio/gwo-gd656-headshot) |
| Tactical Actions | 桌面安装 1.1.0；本次未定位源码根目录，不能假定在本仓库 |

轮盘、标点、GWO 切枪优化、GD656 探头、不完整方块穿透等历史任务属于相关项目；不能从本仓库的 1.0.20 推断它们的最新源码状态。

历史关键偏好：轮盘要保留长按 Alt 打开与松开确认切换、左键选取、右键取消、滚轮方向同快捷栏；GD656 探头沿用其动画，但不将玩家整体位移；不完整方块按实际碰撞形状通过空隙，实体伤害须同时有效。修改前先定位相关最新代码和配置。

## 12. 接手检查清单

- 阅读本文件、`README.md`、`CHANGELOG.md` 和对应功能文档；检查 `git status`，不丢本地改动。
- 确认目标平台是桌面还是安卓，目标实例与依赖版本明确；不要混用两份日志。
- 最近功能继续开发时重点检查金条的实际视觉、材质绑定、检视取消和服务端物品完整性。
- 复现用户问题后再修改；保持拖拽原子性、完整物品锚点、永久开启保险箱和管理员权威配置。
- 图形/动作修改保留 Blender MCP 工程与原许可；每次正式打包排除 Smoke 和私有第三方文件。
- 交付时说明改了什么、验证了什么、仍有什么实际限制；需重启加载新 JAR 时明确告诉用户。

配套文档：`docs/ITEM_PROFILES.md`、`LOOT_MODELS.md`、`SAFE_CRACK.md`、`RAIDS.md`、`VALIDATION.md`。
