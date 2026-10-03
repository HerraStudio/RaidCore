"""Extract one author-provided gold bar, preserving geometry/UV and converting PBR channel packing.

Accepts a complete GLB only. Produces a standalone Blender input, GWO texture maps
and provenance. Downloaded GLB metadata is treated as data, never executed.
"""
import argparse
import copy
import hashlib
import io
import json
import math
import struct
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('--project', type=Path, default=Path(__file__).resolve().parent.parent)
parser.add_argument('--mesh-index', type=int, default=0)
args = parser.parse_args()
blob = args.source.read_bytes()
assert struct.unpack_from('<4sII', blob) == (b'glTF', 2, len(blob)), 'Incomplete GLB'
length, kind = struct.unpack_from('<II', blob, 12)
assert kind == 0x4E4F534A
doc = json.loads(blob[20:20+length])
offset = 20+length
bin_length, bin_type = struct.unpack_from('<II', blob, offset)
assert bin_type == 0x004E4942 and offset+8+bin_length == len(blob)
binary = blob[offset+8:]
mesh_index = args.mesh_index
primitive = doc['meshes'][mesh_index]['primitives'][0]
formats = {5126:'f',5125:'I',5123:'H',5121:'B'}
components = {'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
def values(index):
    accessor = doc['accessors'][index]; view = doc['bufferViews'][accessor['bufferView']]
    fmt = '<'+formats[accessor['componentType']]*components[accessor['type']]
    base = view.get('byteOffset',0)+accessor.get('byteOffset',0)
    stride = view.get('byteStride',struct.calcsize(fmt))
    return [struct.unpack_from(fmt,binary,base+i*stride) for i in range(accessor['count'])]

positions = values(primitive['attributes']['POSITION'])
center = [(min(p[a] for p in positions)+max(p[a] for p in positions))/2 for a in range(3)]
# Fit one of the author's three identical bars to 26 cm, retaining the actual UVs and normals.
fit = .26/max(max(p[a] for p in positions)-min(p[a] for p in positions) for a in range(3))
positions = [tuple((p[a]-center[a])*fit for a in range(3)) for p in positions]
rotate_long_axis = max(range(3),key=lambda a:max(p[a] for p in positions)-min(p[a] for p in positions))==2
if rotate_long_axis: positions=[(p[2],p[1],-p[0]) for p in positions]
target = {'asset':{'version':'2.0','generator':'Tactical Inventory / Fine Gold Bar by Incg5764 (CC BY 4.0)'},
          'scene':0,'scenes':[{'nodes':[0]}], 'nodes':[{'name':'gold_bar','mesh':0}],
          'meshes':[{'name':'gold_bar','primitives':[]}], 'accessors':[],'bufferViews':[],
          'buffers':[{'byteLength':0}], 'materials':[], 'images':[], 'textures':[]}
out = bytearray()
def view(raw, target_type=None):
    while len(out)%4: out.append(0)
    index=len(target['bufferViews']); record={'buffer':0,'byteOffset':len(out),'byteLength':len(raw)}
    if target_type is not None: record['target']=target_type
    target['bufferViews'].append(record); out.extend(raw); return index
def accessor(data, component_type, shape, element_target=None):
    fmt='<'+formats[component_type]*components[shape]
    idx=len(target['accessors']); row={'bufferView':view(b''.join(struct.pack(fmt,*v) for v in data),element_target),
        'componentType':component_type,'count':len(data),'type':shape}
    if shape=='VEC3': row.update(min=[min(v[a] for v in data) for a in range(3)],max=[max(v[a] for v in data) for a in range(3)])
    target['accessors'].append(row); return idx
attributes={}
for name,index in primitive['attributes'].items():
    data=positions if name=='POSITION' else values(index)
    if rotate_long_axis and name in ('NORMAL','TANGENT'):
        data=[(v[2],v[1],-v[0],*v[3:]) for v in data]
    attributes[name]=accessor(data,doc['accessors'][index]['componentType'],doc['accessors'][index]['type'],34962)
indices=accessor(values(primitive['indices']),doc['accessors'][primitive['indices']]['componentType'],'SCALAR',34963)
target['meshes'][0]['primitives']=[{'attributes':attributes,'indices':indices,'material':0,'mode':4}]
material=copy.deepcopy(doc['materials'][primitive['material']])
texture_dir=args.project/'src/main/resources/assets/tactical_inventory/textures/item'
source_dir=args.project/'model-source/gold_bar'
texture_dir.mkdir(parents=True,exist_ok=True); source_dir.mkdir(parents=True,exist_ok=True)
map_files={}
def image_at(texture_index, name):
    image=doc['images'][doc['textures'][texture_index]['source']]; v=doc['bufferViews'][image['bufferView']]
    raw=binary[v.get('byteOffset',0):v.get('byteOffset',0)+v['byteLength']]
    target['images'].append({'mimeType':image['mimeType'],'bufferView':view(raw)})
    target['textures'].append({'source':len(target['images'])-1})
    path=texture_dir/(name+'.png')
    if image['mimeType']=='image/png': path.write_bytes(raw)
    else:
        # Lossless format conversion for Minecraft; no repainting or resizing.
        with Image.open(io.BytesIO(raw)) as converted: converted.convert('RGB').save(path)
    map_files[path.name]=hashlib.sha256(path.read_bytes()).hexdigest()
    return len(target['textures'])-1,raw
pbr=material['pbrMetallicRoughness']
pbr['baseColorTexture']['index'],color=image_at(pbr['baseColorTexture']['index'],'gold_bar_color')
pbr['metallicRoughnessTexture']['index'],packed=image_at(pbr['metallicRoughnessTexture']['index'],'gold_bar_metallic_roughness')
material['normalTexture']['index'],normal=image_at(material['normalTexture']['index'],'gold_bar_normal')
target['materials']=[material]
with Image.open(io.BytesIO(packed)) as maps:
    maps=maps.convert('RGB')
    for channel,name in ((1,'gold_bar_roughness'),(2,'gold_bar_metallic')):
        # GLTF packs roughness in G and metallic in B; GWO expects independent grayscale maps.
        output=texture_dir/(name+'.png'); maps.getchannel(channel).save(output)
        map_files[output.name]=hashlib.sha256(output.read_bytes()).hexdigest()
def write_glb(path, data, payload):
    data['buffers'][0]['byteLength']=len(payload)
    while len(payload)%4: payload.append(0)
    text=json.dumps(data,separators=(',',':')).encode('utf-8')
    text+=b' '*((-len(text))%4)
    file=struct.pack('<4sII',b'glTF',2,12+8+len(text)+8+len(payload))+struct.pack('<II',len(text),0x4E4F534A)+text+struct.pack('<II',len(payload),0x004E4942)+payload
    path.write_bytes(file)
write_glb(source_dir/'gold_bar_source.glb',target,out)
provenance={'title':'Fine Gold Bar','creator':'Incg5764 (@incg5764)','url':'https://sketchfab.com/3d-models/fine-gold-bar-0d1ff6b3e6d045cba78889caf7f5bc6e',
 'license':'CC BY 4.0','license_url':'https://creativecommons.org/licenses/by/4.0/',
 'download_sha256':hashlib.sha256(blob).hexdigest(),'source_mesh':doc['meshes'][mesh_index]['name'],
 'vertices':len(positions),'triangles':len(values(primitive['indices']))//3,
 'dimensions_m':[max(p[a] for p in positions)-min(p[a] for p in positions) for a in range(3)],
 'texture_sha256':map_files}
(source_dir/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',encoding='utf-8')
print(json.dumps(provenance,indent=2))
