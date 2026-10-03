"""Package the Blender MCP export for GWO. Geometry/actions stay unchanged; maps live separately."""
import copy
import json
import struct
from pathlib import Path

project=Path(__file__).resolve().parent.parent
source=project/'model-source/gold_bar/gold_bar_animated.glb'
raw=source.read_bytes(); length=struct.unpack_from('<I',raw,12)[0]
doc=json.loads(raw[20:20+length]); blob=raw[28+length:]
assert {a['name'] for a in doc['animations']}=={'idle','draw','inspect'}
image_views={image['bufferView'] for image in doc.get('images',[]) if 'bufferView' in image}
old_views=doc['bufferViews']; new_views=[]; remap={}; payload=bytearray()
for index,view in enumerate(old_views):
    if index in image_views: continue
    while len(payload)%4: payload.append(0)
    row=copy.deepcopy(view); row['buffer']=0; row['byteOffset']=len(payload)
    payload.extend(blob[view.get('byteOffset',0):view.get('byteOffset',0)+view['byteLength']])
    remap[index]=len(new_views); new_views.append(row)
for accessor in doc['accessors']:
    if 'bufferView' in accessor: accessor['bufferView']=remap[accessor['bufferView']]
doc['bufferViews']=new_views; doc['buffers']=[{'byteLength':len(payload)}]
shifted=set()
for animation in doc['animations']:
    for sampler in animation['samplers']:
        index=sampler['input']
        if index in shifted: continue
        shifted.add(index); accessor=doc['accessors'][index]; view=doc['bufferViews'][accessor['bufferView']]
        start=view['byteOffset']+accessor.get('byteOffset',0);stride=view.get('byteStride',4)
        first=struct.unpack_from('<f',payload,start)[0]
        times=[]
        for i in range(accessor['count']):
            value=struct.unpack_from('<f',payload,start+i*stride)[0]-first
            struct.pack_into('<f',payload,start+i*stride,value);times.append(value)
        accessor['min']=[min(times)];accessor['max']=[max(times)]
doc.pop('images',None); doc.pop('textures',None); doc.pop('samplers',None)
doc['materials']=[{'name':'gold_bar','doubleSided':True,'pbrMetallicRoughness':{'baseColorFactor':[1,1,1,1],'metallicFactor':1,'roughnessFactor':.25}}]
for mesh in doc['meshes']:
    for primitive in mesh['primitives']: primitive['material']=0
doc['asset']['extras']={'creator':'Incg5764','source':'Fine Gold Bar','license':'CC BY 4.0','animation_authoring':'Blender Lab MCP / HerraStudio'}
while len(payload)%4: payload.append(0)
text=json.dumps(doc,separators=(',',':')).encode('utf-8'); text+=b' '*((-len(text))%4)
output=project/'src/main/resources/assets/tactical_inventory/models/loot/gold_bar.glb'
output.parent.mkdir(parents=True,exist_ok=True)
output.write_bytes(struct.pack('<4sII',b'glTF',2,12+8+len(text)+8+len(payload))+struct.pack('<II',len(text),0x4E4F534A)+text+struct.pack('<II',len(payload),0x004E4942)+payload)
print(json.dumps({'model_bytes':output.stat().st_size,'nodes':[n.get('name') for n in doc['nodes']],
 'clips':[{'name':a['name'],'channels':len(a['channels'])} for a in doc['animations']]},indent=2))
