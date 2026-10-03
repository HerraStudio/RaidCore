"""Execute in Blender Lab MCP. Author actual GLB actions; preserve existing objects.

PROJECT_ROOT is supplied by the MCP launch script. Run in the task's fresh Blender
session; original startup objects are hidden and only selected gold objects export.
"""
import bpy
import json
import math
from pathlib import Path
from mathutils import Euler, Matrix, Vector, Quaternion

project=Path(PROJECT_ROOT)
source=project/'model-source/gold_bar/gold_bar_source.glb'
output=project/'model-source/gold_bar'
assets=project/'loot/src/main/resources/assets/tactical_inventory/models/loot'
assets.mkdir(parents=True,exist_ok=True)
original=list(bpy.context.scene.objects)
for obj in original:
    obj.select_set(False); obj.hide_set(True); obj.hide_render=True
before=set(bpy.data.objects)
bpy.ops.import_scene.gltf(filepath=str(source))
imported=[obj for obj in bpy.data.objects if obj not in before]
meshes=[obj for obj in imported if obj.type=='MESH']
assert len(meshes)==1
mesh=meshes[0]
collection=bpy.data.collections.new('Gold_Loot_Inspection')
bpy.context.scene.collection.children.link(collection)
world=mesh.matrix_world.copy()
mesh.data=mesh.data.copy(); mesh.data.transform(world)
mesh.parent=None; mesh.matrix_world=Matrix.Identity(4); mesh.name='gold_bar'
for owner in list(mesh.users_collection): owner.objects.unlink(mesh)
collection.objects.link(mesh)
root=bpy.data.objects.new('loot_root',None); root.empty_display_type='PLAIN_AXES'
collection.objects.link(root); mesh.parent=root
hands=[]
for name in ('hand_right','hand_left'):
    hand=bpy.data.objects.new(name,None); hand.parent=root; collection.objects.link(hand); hands.append(hand)
scene=bpy.context.scene; scene.render.fps=40; scene.frame_start=1; scene.frame_end=105
conversion=Matrix.Rotation(-math.pi/2,4,'X').to_quaternion()
def gltf_translation(v): return (v[0],-v[2],v[1])
def gltf_rotation(degrees):
    q=Euler(tuple(math.radians(a) for a in degrees),'XYZ').to_quaternion()
    return conversion.inverted() @ q @ conversion
actions=[]
for name,keys in (
    ('idle',[(1,(0,0,0),(0,0,0)),(31,(.001,.002,0),(.5,-.8,.4)),(61,(0,0,0),(0,0,0))]),
    ('draw',[(1,(0,-.34,.15),(-22,12,8)),(9,(0,.012,-.008),(3,-2,-1)),(15,(0,0,0),(0,0,0))]),
    ('inspect',[(1,(0,0,0),(0,0,0)),(17,(-.08,.09,-.14),(-12,-20,-12)),
                (35,(-.06,.10,-.13),(-8,35,-6)),(55,(.005,.07,-.11),(65,120,3)),
                (73,(.04,.06,-.09),(-12,200,-8)),(89,(.02,.04,-.055),(-9,330,3)),(105,(0,0,0),(0,360,0))])):
    root.animation_data_create(); root.animation_data.action=bpy.data.actions.new(name)
    root.rotation_mode='QUATERNION'
    last=None
    for frame,translation,rotation in keys:
        root.location=gltf_translation(translation); q=gltf_rotation(rotation)
        if last is not None and q.dot(last)<0: q.negate()
        root.rotation_quaternion=q; last=q.copy()
        root.keyframe_insert(data_path='location',frame=frame)
        root.keyframe_insert(data_path='rotation_quaternion',frame=frame)
    action=root.animation_data.action
    for layer in action.layers:
        for strip in layer.strips:
            for bag in strip.channelbags:
                for curve in bag.fcurves:
                    for key in curve.keyframe_points:
                        key.interpolation='BEZIER'; key.handle_left_type='AUTO_CLAMPED'; key.handle_right_type='AUTO_CLAMPED'
    track=root.animation_data.nla_tracks.new(); track.name=name
    strip=track.strips.new(name,1,action); strip.action_frame_start=1; strip.action_frame_end=keys[-1][0]
    strip.blend_type='REPLACE'; strip.extrapolation='NOTHING'
    actions.append(action)
root.animation_data.action=None
root.location=(0,0,0); root.rotation_quaternion=(1,0,0,0)
for obj in bpy.context.selected_objects: obj.select_set(False)
for obj in [mesh,root,*hands]: obj.hide_set(False); obj.hide_render=False; obj.select_set(True)
bpy.context.view_layer.objects.active=root
scene.frame_set(1)
# Real Blender-exported sampled animation tracks, not a hand-written runtime curve.
result_export=bpy.ops.export_scene.gltf(filepath=str(output/'gold_bar_animated.glb'),export_format='GLB',use_selection=True,
    export_materials='EXPORT',export_animations=True,export_animation_mode='NLA_TRACKS',export_nla_strips=True,
    export_frame_range=False,export_force_sampling=True,export_optimize_animation_size=False,
    export_cameras=False,export_lights=False,export_yup=True,export_copyright='Fine Gold Bar by Incg5764, CC BY 4.0; animations by HerraStudio')
assert 'FINISHED' in result_export
metadata={'authoring':'Blender Lab MCP','blender':bpy.app.version_string,'fps':40,
          'clips':{'idle':1.5,'draw':.35,'inspect':2.6},'frames':{'idle':[1,61],'draw':[1,15],'inspect':[1,105]},
          'preserved_original_objects':len(original),'mesh_vertices':len(mesh.data.vertices),'source':'Fine Gold Bar by Incg5764 / CC BY 4.0'}
(output/'animation_authoring.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
# Leave the actual inspect action available in Blender for direct editing/preview.
for track in root.animation_data.nla_tracks: track.mute=True
root.animation_data.action=next(a for a in actions if a.name=='inspect')
scene.frame_set(35)
for area in bpy.context.screen.areas:
    if area.type=='VIEW_3D':
        region=area.spaces.active.region_3d; region.view_location=(0,0,0); region.view_distance=.65
# Save the active scene; creating or serializing another Scene is unsafe in this Blender build.
bpy.ops.wm.save_as_mainfile(filepath=str(output/'gold_bar_inspection.blend'),check_existing=False)
result=metadata
