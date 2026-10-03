import bpy
from pathlib import Path
from mathutils import Vector

project=Path(PROJECT_ROOT)
scene=bpy.context.scene;mesh=bpy.data.objects['gold_bar'];scene.frame_set(1)
scene.render.engine='CYCLES';scene.cycles.samples=16;scene.cycles.device='CPU'
scene.world.use_nodes=True
background=scene.world.node_tree.nodes.get('Background');background.inputs['Color'].default_value=(.38,.38,.38,1);background.inputs['Strength'].default_value=.75
for name,pos,power,size in [('Gold_Bake_Key',(.7,-.7,1.3),45,.9),('Gold_Bake_Fill',(-.9,.5,.8),22,1.2),('Gold_Bake_Rim',(.7,.9,.4),16,.7)]:
    obj=bpy.data.objects.get(name)
    if obj is None:
        light=bpy.data.lights.new(name,'AREA');obj=bpy.data.objects.new(name,light);scene.collection.objects.link(obj)
    light=obj.data;light.energy=power;light.shape='DISK';light.size=size;obj.location=pos
    obj.rotation_euler=(-obj.location).to_track_quat('-Z','Y').to_euler()
    obj.hide_render=False
image=bpy.data.images.new('Gold_Vanilla_Baked',2048,2048,alpha=False)
image.colorspace_settings.name='sRGB'
material=mesh.data.materials[0];nodes=material.node_tree.nodes
target=nodes.new('ShaderNodeTexImage');target.name='Vanilla_Bake_Target';target.image=image
for node in nodes:node.select=False
target.select=True;nodes.active=target
for obj in bpy.context.selected_objects:obj.select_set(False)
mesh.select_set(True);mesh.hide_render=False;bpy.context.view_layer.objects.active=mesh
scene.render.bake.use_selected_to_active=False;scene.render.bake.margin=12
bpy.ops.object.bake(type='COMBINED',use_clear=True)
image.filepath_raw=str(project/'src/main/resources/assets/tactical_inventory/textures/item/gold_bar_vanilla_color.png')
image.file_format='PNG';image.save()
nodes.remove(target)
result={'status':'combined_material_baked','resolution':2048,'path':image.filepath_raw}

