"""Convert the author's GLTF maps to Iris's LabPBR sibling textures without repainting."""
from pathlib import Path
from PIL import Image

textures=Path(__file__).resolve().parent.parent/'loot/src/main/resources/assets/tactical_inventory/textures/item'
normal=Image.open(textures/'gold_bar_normal.png').convert('RGB')
roughness=Image.open(textures/'gold_bar_roughness.png').convert('L')
metallic=Image.open(textures/'gold_bar_metallic.png').convert('L')
assert normal.size==roughness.size==metallic.size
opaque=Image.new('L',normal.size,255);zero=Image.new('L',normal.size,0)
# LabPBR normals: XY from the original OpenGL normal map, full AO and flat height.
Image.merge('RGBA',(normal.getchannel('R'),normal.getchannel('G'),opaque,opaque)).save(textures/'gold_bar_color_n.png')
# Artist roughness is perceptual; LabPBR smoothness is its inverse. G=231 is gold.
smoothness=roughness.point(lambda value:255-value)
f0=metallic.point(lambda value:231 if value>=128 else 10)
Image.merge('RGBA',(smoothness,f0,zero,zero)).save(textures/'gold_bar_color_s.png')
print('IRIS_LABPBR_MAPS_READY: 2048 px, original normal XY, inverse roughness, gold conductor=231, no emissive')
