import bpy
import math
import json
from pathlib import Path
from mathutils import Vector, Quaternion, Euler

project=Path(PROJECT_ROOT)
output=project/'model-source/gold_bar'
root=bpy.data.objects['loot_root']; mesh=bpy.data.objects['gold_bar']
hands=[bpy.data.objects['hand_right'],bpy.data.objects['hand_left']]
conversion=Quaternion((1,0,0),-math.pi/2)
def to_blender_vector(v): return (v[0],-v[2],v[1])
def to_blender_quaternion(q): return conversion.inverted() @ q @ conversion
root.animation_data.action=None
for track in root.animation_data.nla_tracks: track.mute=False
new_actions={}
for index,hand in enumerate(hands):
    hand.parent=None; hand.matrix_world.identity(); hand.rotation_mode='QUATERNION'
    sign=1 if index==0 else -1
    grip=Vector((sign*.112,-.065,.01))
    for track in root.animation_data.nla_tracks:
        name=track.name; action=track.strips[0].action; end=int(track.strips[0].action_frame_end)
        for other in root.animation_data.nla_tracks: other.mute=True
        root.animation_data.action=action
        hand.animation_data_create(); hand.animation_data.action=bpy.data.actions.new(name+'_hand_'+str(index))
        for frame in range(1,end+1,2):
            bpy.context.scene.frame_set(frame)
            # Sample the actual Blender item curve, then author an independently oriented wrist.
            item_q=conversion @ root.rotation_quaternion @ conversion.inverted()
            position=Vector((root.location.x,root.location.z,-root.location.y))
            delta=position + item_q @ grip - grip
            fraction=(frame-1)/max(1,end-1)
            reach=math.sin(fraction*math.pi) if name=='inspect' else 0
            if index==1:
                delta.z+=.035*reach; delta.y-=.025*reach
            wrist=Euler((math.radians(8*reach),0,math.radians(sign*7*reach)),'XYZ').to_quaternion()
            hand.location=to_blender_vector(delta); hand.rotation_quaternion=to_blender_quaternion(wrist)
            hand.keyframe_insert(data_path='location',frame=frame); hand.keyframe_insert(data_path='rotation_quaternion',frame=frame)
        if (end-1)%2!=0:
            bpy.context.scene.frame_set(end); hand.location=(0,0,0); hand.rotation_quaternion=(1,0,0,0)
            hand.keyframe_insert(data_path='location',frame=end);hand.keyframe_insert(data_path='rotation_quaternion',frame=end)
        action_hand=hand.animation_data.action
        for layer in action_hand.layers:
            for strip in layer.strips:
                for bag in strip.channelbags:
                    for curve in bag.fcurves:
                        for point in curve.keyframe_points:
                            point.interpolation='BEZIER';point.handle_left_type='AUTO_CLAMPED';point.handle_right_type='AUTO_CLAMPED'
        hand_track=hand.animation_data.nla_tracks.new();hand_track.name=name
        hand_strip=hand_track.strips.new(name,1,action_hand);hand_strip.action_frame_start=1;hand_strip.action_frame_end=end
        hand_strip.blend_type='REPLACE';hand_strip.extrapolation='NOTHING'
        new_actions[index,name]=action_hand
    hand.animation_data.action=None
root.animation_data.action=None
for obj in [root,*hands]:
    for track in obj.animation_data.nla_tracks: track.mute=False
    obj.location=(0,0,0);obj.rotation_quaternion=(1,0,0,0)
for obj in bpy.context.selected_objects:obj.select_set(False)
for obj in [root,mesh,*hands]:obj.select_set(True)
bpy.context.view_layer.objects.active=root
bpy.context.scene.frame_set(1)
bpy.ops.export_scene.gltf(filepath=str(output/'gold_bar_animated.glb'),export_format='GLB',use_selection=True,
    export_materials='EXPORT',export_animations=True,export_animation_mode='NLA_TRACKS',export_nla_strips=True,
    export_frame_range=False,export_force_sampling=True,export_optimize_animation_size=False,
    export_cameras=False,export_lights=False,export_yup=True,export_copyright='Fine Gold Bar by Incg5764, CC BY 4.0; animations by HerraStudio')
for obj in [root,*hands]:
    for track in obj.animation_data.nla_tracks:track.mute=True
root.animation_data.action=next(track.strips[0].action for track in root.animation_data.nla_tracks if track.name=='inspect')
for index,hand in enumerate(hands):hand.animation_data.action=new_actions[index,'inspect']
bpy.context.scene.frame_set(35)
bpy.ops.wm.save_as_mainfile(filepath=str(output/'gold_bar_inspection.blend'),check_existing=False)
metadata=json.loads((output/'animation_authoring.json').read_text(encoding='utf-8'))
metadata['hands']='Independent Blender-authored wrist tracks follow sampled grip positions; no rigid arm flipping'
(output/'animation_authoring.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
result={'status':'refined_and_saved','clips':list(metadata['clips']),'independent_hands':2}

