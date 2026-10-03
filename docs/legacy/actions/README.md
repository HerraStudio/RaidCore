# Herra Tactical Actions

一个面向 Minecraft NeoForge 1.21.1 的战术动作 Mod，提供左右探头和趴下动作。

## 功能

- **Q**：左探头（按住）
- **E**：右探头（按住）
- **Z**：趴下/起身（切换）
- **Shift 蹲下**：正常行走状态下，仅在脚下有方块碰撞面支撑时生效；跳跃、下落时不允许主动蹲下。空中蹲输入不缓存，蹲跳和空中按蹲后落地均保持站立，须在地面松开再按蹲键才能重新蹲下。
- 蹲下按空格：先站立一个游戏刻（约 0.05 秒），再起跳；短按空格也会执行。头顶空间不足时不强行站起或起跳。
- 疾跑时可直接按 Shift，立即停止疾跑并蹲下。游泳、飞行、骑乘、攀爬和自定义趴下保留原有操作。
- 探头直接移植 GD656Peek 0.1.1 的改良上半身姿态、15°幅度、0.2 秒配置以及原进入、回正和左右反向曲线。角度和侧移保留原来的独立动画轨道，不另设计动作。
- 删除旧版 Tactical Actions 探头。只让躯干、头和双臂执行 GD656 姿态；不执行 GD656 的 `player.move` 和旧整模型侧移，人物坐标、碰撞箱和双腿保持原位。
- 原本会移动人物的 0.3 格横移用于虚拟视点。相机和射击眼位随原侧移曲线偏移，接近实体墙面时限制视点，避免穿墙观察。
- 保留 GD656 第一/第三人称镜头倾斜公式，原版手部不添加额外自定义动画。
- GWO 枪械保留原有开镜、开火、换弹、切枪动作。第一人称独立枪械渲染跟随同一 GD656 镜头曲线，第三人称原生持枪姿态后添加 GD656 双臂姿态，避免被 GWO 覆盖。
- 服务端同步探头方向，并按原曲线计算虚拟眼位；GWO 原生开火从探出的眼位生成子弹，人物实体本身不侧移。
- 保留 Z 趴下及原有蹲、跳、疾跑规则。
- 可配置按住/切换探头，并沿用附近掩体的自动探头采样；沿用你原实例的配置，默认仅 GWO 开镜时可触发，蹲下和普通站立不自动触发。手动 Q/E 优先，双键回正。

## 安装

1. 安装 Minecraft 1.21.1、NeoForge 21.1.251。
2. 安装 [Player Animation Library](https://modrinth.com/mod/playeranim)，版本至少为 `1.1.6+mc.1.21.1`。
3. 将 `tactical-actions-1.21.1-neoforge-1.1.0.jar` 放进 `mods` 文件夹，移走旧版 Tactical Actions JAR，避免重复加载。

Player Animation Library 的 Mod ID 是 `player_animation_library`，本 Mod 已声明为客户端必需依赖。GWO 是可选适配，已针对 `GWO-Beta1.0-Fix-0.5.jar` 验证原生渲染及开火路径。

多人游戏要同步探头姿态和 GWO 射击眼位，服务端和客户端都需安装新版 Tactical Actions；只有客户端安装时提供本地表现。不会额外改变实体受击碰撞箱。

探头设置：`config/tacticalactions-peek-client.toml`。默认 `toggleInput=false`，按住 Q/E；`true` 为按同方向再次回正。自动探头可用 `autoPeekWhileCrouching`、`autoPeekWhileAiming`、`autoPeekWhileStanding` 分别控制。

## 构建

```powershell
.\gradlew.bat clean test build --no-daemon
```

生成文件位于 `build/libs/`：

- `tactical-actions-1.21.1-neoforge-1.1.0.jar`
- `tactical-actions-1.21.1-neoforge-1.1.0-sources.jar`

## 视觉回归检查

输入优先级为：正常跳跃/腾空禁止主动蹲伏，蹲伏禁止开始疾跑，站立时允许疾跑；探头是独立的上半身姿态叠加，兼容蹲伏与普通移动，Z 趴下时优先播放趴下。低矮空间仍保留原版被动压低身体以防穿墙。

先用开发客户端在项目的 `run` 目录创建名为 `New World` 的超平坦测试世界，再执行：

```powershell
.\gradlew.bat runClient -PvisualTest --no-daemon
```

测试会在这个独立世界布置固定地面和网格墙，触发真实输入处理，保存第一人称、第三人称前后及侧面的动作截图到 `run/visual-test/screenshots`。日志在 `run/visual-test/sequence.log`。该模式会修改测试世界，不可使用重要存档；它拒绝在项目 `run` 目录之外执行。

`src/visualTest` 不包含在发布 JAR 中。正常启动不会自动触发动作或修改世界。

## 注意事项

Q 和 E 会覆盖原版默认的丢弃物品、打开背包按键。可以在 Minecraft 的按键设置中重新绑定原版功能或本 Mod 按键。

蹲下规则在客户端输入进入原版移动逻辑前处理，通过原版潜行状态数据包同步，无需另加网络协议；服务器上其他未安装本 Mod 的玩家不会被强制采用这些输入规则。

探头方向已增加服务端同步。Z 趴下仍沿用原版项目的本地表现，不新增其服务端动画协议。

## 许可证

MIT。GD656Peek 原作者 Minecraft_GD656 的署名及 MIT 条款见 `THIRD_PARTY_NOTICES.md`。
