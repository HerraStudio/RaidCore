# Blender 保险箱开门工程

在 Blender 5.2.2 LTS 中通过 Blender Lab MCP 制作。

- `safe_opening.blend`：可编辑模型、原 PBR 材质、门铰链控制器、动作及预览相机。
- `safe_opening.glb`：包含 4 个 mesh 和 1 个门铰链动画的导出文件。
- 动作 `Safe_Open_And_Stay`：20 FPS，第 1 帧关门，第 17 帧打开 60°，之后保持打开到第 33 帧。
- 门、把手、锁具共同挂在 `Safe_Door_Hinge` 上；箱体不移动。

游戏使用闭合姿态的箱体/箱门 OBJ 和 Blender 实际导出的 65 个曲线采样，不增加 GLB 动画加载器依赖。原作者模型大小保留为上一版的 2 倍。

Iris 光影通过 `safe_color_n.png` / `safe_color_s.png` 读取原材质。运行 `python tools/prepare_safe_pbr.py` 可从本目录的 GLB 重建这两张 2048×2048 贴图；转换记录及来源 SHA256 位于 `safe_pbr.json`。遵循 [LabPBR 1.3](https://shaderlabs.org/wiki/LabPBR_Material_Standard)：法线 Y 从 OpenGL 转为 DirectX，粗糙度转换为平滑度，金属区域用原颜色作为反射 F0，油漆/锈蚀区域保留非金属属性。箱体与活动箱门共用同一材质图集。开启支持 LabPBR 的光影及其材质选项后生效。

源资产为 [Simple Safe by avhatar](https://sketchfab.com/3d-models/simple-safe-2e308cb3fe1d4676beb43e75fdd27e8e)，CC BY 4.0；适配说明见项目 `ASSET_LICENSES.md`。
