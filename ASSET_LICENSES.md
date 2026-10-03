# Third-party model credits

## Simple Safe

**Simple Safe** by **avhatar** is licensed under **Creative Commons Attribution 4.0 International (CC BY 4.0)**.

- Model: https://sketchfab.com/3d-models/simple-safe-2e308cb3fe1d4676beb43e75fdd27e8e
- Creator: https://sketchfab.com/avhatar
- License: https://creativecommons.org/licenses/by/4.0/

Adaptations for Tactical Inventory: scaled the supplied GLB twice the initial 0.96-block fit and oriented its front north. In Blender 5.2.2, reposed the door closed, parented the door/handle/lock to the original hinge, and authored a 60-degree opening animation which holds open. Exported closed OBJ parts, an editable Blender project, an animated GLB and actual Blender curve samples. The original base-color PNG is included without repainting or resizing. Minecraft supplies standard lighting; GLB PBR metallic/roughness and normal-map shading are not reproduced by the vanilla renderer.

Covered assets: `assets/tactical_inventory/models/block/safe*.obj`, `safe.mtl`, `safe_import.json`, `safe_credits.txt`, `safe_door_animation.json`, `assets/tactical_inventory/textures/block/safe_color.png`, and model/texture content in `model-source/safe/`.

The model and texture retain CC BY 4.0 licensing. The project's MIT license applies to its own code and does not replace this asset license. No endorsement by the original creator is implied.

## Fine Gold Bar

**Fine Gold Bar** by **Incg5764 (@incg5764)** is licensed under **Creative Commons Attribution 4.0 International (CC BY 4.0)**.

- Model: https://sketchfab.com/3d-models/fine-gold-bar-0d1ff6b3e6d045cba78889caf7f5bc6e
- Creator: https://sketchfab.com/incg5764
- License: https://creativecommons.org/licenses/by/4.0/

Adaptations: extracted one of the three supplied bars, centered it and fitted it to 26 cm; preserved its mesh, UVs and 2048 px source material maps. Converted the base-color JPEG losslessly to PNG, separated the GLTF packed roughness/metallic channels, and provided LabPBR `_n` / `_s` maps for Iris (gold conductor code 231). Blender Lab MCP authored the draw, idle and inspect actions, including independently oriented wrist tracks. Blender also baked a combined material texture for inventory and non-shader rendering. Original PBR albedo/normal/roughness/metallic remain available for live shader lighting. The runtime GLB retains Blender's sampled animations; its duplicated embedded images are replaced by the separate texture resources.

Covered assets: `assets/tactical_inventory/models/loot/gold_bar.glb`, `assets/tactical_inventory/textures/item/gold_bar*.png`, and model/texture content in `model-source/gold_bar/`. The editable Blender project retains hidden default startup objects. These assets retain CC BY 4.0; the project's own code is MIT. No endorsement is implied.
