# Blender 保险箱开门工程

在 Blender 5.2.2 LTS 中通过 Blender Lab MCP 制作。

- `safe_opening.blend`：可编辑模型、原 PBR 材质、门铰链控制器、动作及预览相机。
- `safe_opening.glb`：包含 4 个 mesh 和 1 个门铰链动画的导出文件。
- 动作 `Safe_Open_And_Stay`：20 FPS，第 1 帧关门，第 17 帧打开 60°，之后保持打开到第 33 帧。
- 门、把手、锁具共同挂在 `Safe_Door_Hinge` 上；箱体不移动。

游戏使用闭合姿态的箱体/箱门 OBJ 和 Blender 实际导出的 65 个曲线采样，不增加 GLB 动画加载器依赖。原作者模型大小保留为上一版的 2 倍。

源资产为 [Simple Safe by avhatar](https://sketchfab.com/3d-models/simple-safe-2e308cb3fe1d4676beb43e75fdd27e8e)，CC BY 4.0；适配说明见项目 `ASSET_LICENSES.md`。
