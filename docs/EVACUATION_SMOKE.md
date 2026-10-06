# 撤离烟雾遮挡修复

2026-10-05，Minecraft 1.21.1 / NeoForge 21.1.251 / Photon 2.2.7。

默认 `evacuation_smoke.fx` 的活动粒子渲染器原来设置 `compositeMode = INHERIT`；本机 Photon 全局配置为 `LATE`。这个路径只用半透明方块阶段之前的深度快照，最后再合成烟雾；在半透明阶段绘制的模型即使贴图完全不透明，也不会进入那个快照。开启 Iris 光影时，Photon 改用光影深度，因此两条路径表现不同。

依据：[Photon FXCompositeMode](https://github.com/Low-Drag-MC/Photon/blob/1.21/src/main/java/com/lowdragmc/photon/client/gameobject/emitter/renderpipeline/FXCompositeMode.java)、[OpaqueDepthCapture](https://github.com/Low-Drag-MC/Photon/blob/1.21/src/main/java/com/lowdragmc/photon/client/gameobject/emitter/renderpipeline/OpaqueDepthCapture.java)；本机依赖 JAR 的字节码与相应行为也已核对。

修复只把活动粒子渲染器的合成模式改为 `VANILLA`，使用当前场景深度。解压后的 NBT 除这个枚举值外全部字节一致；原绿色、烟雾材质、发射数量、寿命、位置、循环、深度测试与深度写入设置均保持。禁用的拖尾配置未改。光影路径仍由 Photon 管理。

## 原生回归

`RaidCoreEvacuationSmoke` 在独立 `run-evacuation-smoke` 目录、新建平坦世界和隐藏窗口中运行。场景包含完整墙面、烟雾和固定镜头，分别检查墙不存在、烟雾被墙完全挡住、烟雾位于墙前；统计截图中央区域绿色像素。墙后必须为零，另外两种情况必须可见，并检查运行时重载、唯一实例与清理。

额外的 `src/evacuationSmoke/resources` 仅在 `-PevacuationTranslucentWall` 时加载：石头保持完全不透明的原贴图，通过 `minecraft:translucent` 渲染，模拟不透明建模素材走半透明通道的情况。资源和检查代码都不进入发布 JAR。未进入用户的多人服务器地图。

| 条件 | 修复前墙后绿色像素 | 修复后墙后绿色像素 | 修复后开放 / 墙前绿色像素 |
|---|---:|---:|---:|
| Iris + Sodium，光影关闭，Fancy | 8047 | 0 | 11319 / 59709 |
| Iris + Sodium，光影关闭，Fabulous | 14059 | 0 | 7642 / 25216 |
| Iris + Sodium，Sundial-Lite 开启，Fancy | 未重跑 | 0 | 12291 / 56276 |

普通石头墙的原版及 Iris 关闭光影基线也通过，说明问题取决于模型的渲染通道。Iris 使用本机 1.8.14-beta.1，Sodium 0.8.12，Sundial-Lite 使用既有独立材质检查中的光影包。

复现与修复命令：

```powershell
.\gradlew.bat runClient -PraidCoreEvacuationSmoke -PevacuationTranslucentWall -PevacuationLabel=translucent-baseline
.\gradlew.bat runClient -PraidCoreEvacuationSmoke -PevacuationTranslucentWall -PevacuationStrict=true -PevacuationLabel=translucent-fixed
.\gradlew.bat runClient -PraidCoreEvacuationSmoke -PevacuationTranslucentWall -PevacuationStrict=true -PevacuationShaders=true -PevacuationLabel=shaders-fixed
```

最后一条需要在独立实例中启用 Iris 光影。测试默认全局 `fx_composite_mode = "LATE"`，从而验证特效自己的 `VANILLA` 覆盖确实生效。发布构建不带上述参数。

`VANILLA` 沿用原版粒子与水、玻璃、云的遮挡次序，烟雾也会被写入深度的水面/玻璃遮住；这次选择优先保证撤离标识被地图几何正确遮挡。自定义撤离 FX 继续使用其自身资源配置。

证据：[结果](evacuation-smoke-results.json)、[无光影修复前](images/evacuation-smoke-before.png)、[无光影修复后](images/evacuation-smoke-after.png)、[墙前](images/evacuation-smoke-foreground.png)、[光影开启](images/evacuation-smoke-shaders.png)。

## 1.0.4 客户端修复版

`releases/evacuation-smoke-fix/raidcore-1.21.1-neoforge-1.0.4-smoke-fix.jar` 以本机正在使用的正式 1.0.4 为基础，只替换已验证的烟雾资源；349 个生产类以及模组描述、网络协议、其余资源全部字节一致。客户端替换旧 RaidCore JAR 并重启即可，服务端 1.0.4 可以继续配合使用。

可通过 `tools/package_smoke_hotfix.py <原 JAR> <修复 JAR>` 复现打包。当前工作区还在开发后续版本，因此此次客户端修复版直接使用已发布的 1.0.4 作为基线；当前工作区另行执行 `build`，313 项单元测试和发布内容检查通过。原生检查中的 `RaidEffects.class` 与 1.0.4 相同。
