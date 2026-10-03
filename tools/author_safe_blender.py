"""Run through Blender Lab MCP with PROJECT_ROOT and MODEL_SOURCE supplied.

Creates an authoring collection while retaining the scene's original objects. Writes
an editable .blend, an animated GLB, closed OBJ parts and the actual Blender curve.
"""
import bpy
import json
import math
from pathlib import Path
from mathutils import Matrix, Vector

project = Path(PROJECT_ROOT)
progress_file = project / ".audit/blender_author_progress.txt"
def checkpoint(text):
    progress_file.write_text(text, encoding="utf-8")
checkpoint("connected")
source = Path(MODEL_SOURCE)
assets = project / "src/main/resources/assets/tactical_inventory/models/block"
output = project / "model-source/safe"
assets.mkdir(parents=True, exist_ok=True)
output.mkdir(parents=True, exist_ok=True)
window = bpy.context.window_manager.windows[0]
scene = bpy.context.scene
previous_scene = window.scene.name
original_objects = list(scene.objects)
for original in original_objects:
    original.hide_set(True)
    original.hide_render = True
if True:
    scene.render.fps = 20
    scene.frame_start = 1
    scene.frame_end = 33
    before = set(bpy.data.objects)
    if True:
        bpy.ops.import_scene.gltf(filepath=str(source))
    checkpoint("imported")
    imported = [obj for obj in bpy.data.objects if obj not in before]
    meshes = [obj for obj in imported if obj.type == "MESH"]
    source_hinge = next(obj for obj in imported if obj.name.startswith("Safe1_Door"))
    door_meshes = set(obj for obj in source_hinge.children_recursive if obj.type == "MESH")
    if len(meshes) != 4 or len(door_meshes) != 3:
        raise RuntimeError("Expected the author's body, door, handle and lock")
    rotation = Matrix.Rotation(math.pi, 4, "Z")
    points = [rotation @ obj.matrix_world @ vertex.co for obj in meshes for vertex in obj.data.vertices]
    low = Vector([min(point[axis] for point in points) for axis in range(3)])
    high = Vector([max(point[axis] for point in points) for axis in range(3)])
    size = 1.92 / max(high-low)
    center = (low+high)/2
    offset = Vector((.5-center.x*size, -.5-center.y*size, .02-low.z*size))
    fit = Matrix.Translation(offset) @ Matrix.Diagonal((size,size,size,1)) @ rotation
    hinge_location = fit @ source_hinge.matrix_world.translation
    close_rotation = Matrix.Translation(hinge_location) @ Matrix.Rotation(math.radians(-60),4,"Z") @ Matrix.Translation(-hinge_location)
    
    model_collection = bpy.data.collections.new("Safe_Model")
    scene.collection.children.link(model_collection)
    for obj in meshes:
        world = fit @ obj.matrix_world
        if obj in door_meshes:
            world = close_rotation @ world
        mesh = obj.data.copy()
        mesh.transform(world)
        obj.data = mesh
        obj.parent = None
        obj.matrix_world = Matrix.Identity(4)
        for collection in list(obj.users_collection):
            collection.objects.unlink(obj)
        model_collection.objects.link(obj)
        obj.name = "Safe_Body" if obj not in door_meshes else {"Object_8":"Safe_Door","Object_11":"Safe_Handle","Object_14":"Safe_Lock"}.get(obj.name.split('.')[0],"Safe_DoorPart")
    
    hinge = bpy.data.objects.new("Safe_Door_Hinge",None)
    hinge.empty_display_type = "PLAIN_AXES"
    hinge.empty_display_size = .15
    model_collection.objects.link(hinge)
    hinge.location = hinge_location
    scene.view_layers[0].update()
    for obj in door_meshes:
        obj.parent = hinge
        obj.matrix_parent_inverse = hinge.matrix_world.inverted()
    for obj in imported:
        if obj not in meshes:
            bpy.data.objects.remove(obj,do_unlink=True)
    
    hinge.rotation_mode = "XYZ"
    for frame in range(1,18):
        t = (frame-1)/16
        hinge.rotation_euler.z = math.radians(60*(1-(1-t)**3))
        hinge.keyframe_insert(data_path="rotation_euler",index=2,frame=frame)
    hinge.animation_data.action.name = "Safe_Open_And_Stay"
    for layer in hinge.animation_data.action.layers:
        for strip in layer.strips:
            for bag in strip.channelbags:
                for curve in bag.fcurves:
                    curve.extrapolation = "CONSTANT"
                    for point in curve.keyframe_points:
                        point.interpolation = "BEZIER"
                        point.handle_left_type = point.handle_right_type = "AUTO_CLAMPED"
    
    checkpoint("animation_keys_created")
    samples = []
    for index in range(65):
        position = 1+index*.25
        frame = int(position)
        scene.frame_set(frame,subframe=position-frame)
        samples.append(math.degrees(hinge.rotation_euler.z))
    scene.frame_set(1)
    
    def export_obj(filename, selected):
        graph = bpy.context.evaluated_depsgraph_get()
        lines = ["# Simple Safe by avhatar, CC BY 4.0. Animated in Blender; see safe_credits.txt.","mtllib safe.mtl"]
        next_index, triangles = 1,0
        all_points = []
        for obj in selected:
            evaluated = obj.evaluated_get(graph)
            mesh = evaluated.to_mesh(preserve_all_data_layers=True,depsgraph=graph)
            mesh.calc_loop_triangles()
            matrix = evaluated.matrix_world
            normals = matrix.to_3x3().inverted().transposed()
            uv = mesh.uv_layers.active.data
            vertices, coordinates, directions, faces, lookup = [],[],[],[],{}
            for triangle in mesh.loop_triangles:
                face = []
                for loop_id in triangle.loops:
                    point = matrix @ mesh.vertices[mesh.loops[loop_id].vertex_index].co
                    direction = (normals @ mesh.corner_normals[loop_id].vector).normalized()
                    tex = uv[loop_id].uv
                    key = tuple(round(value,8) for value in (*point,*tex,*direction))
                    if key not in lookup:
                        lookup[key] = next_index+len(vertices)
                        vertices.append((point.x,point.z,-point.y))
                        coordinates.append(tuple(tex))
                        directions.append((direction.x,direction.z,-direction.y))
                    face.append(lookup[key])
                faces.append(face)
            lines += [f"g {obj.name}","usemtl safe"]
            lines += ["v "+" ".join(f"{value:.8f}" for value in point) for point in vertices]
            lines += [f"vt {u:.8f} {v:.8f}" for u,v in coordinates]
            lines += ["vn "+" ".join(f"{value:.8f}" for value in direction) for direction in directions]
            lines += ["f "+" ".join(f"{value}/{value}/{value}" for value in face) for face in faces]
            next_index += len(vertices)
            triangles += len(faces)
            all_points += vertices
            evaluated.to_mesh_clear()
        (assets/filename).write_text("\n".join(lines)+"\n",encoding="utf-8")
        return {"triangles":triangles,"bounds":{"min":[min(p[i] for p in all_points) for i in range(3)],"max":[max(p[i] for p in all_points) for i in range(3)]}}
    
    body = [obj for obj in meshes if obj not in door_meshes]
    parts = sorted(door_meshes,key=lambda obj:obj.name)
    checkpoint("writing_obj")
    closed = export_obj("safe.obj",body+parts)
    export_obj("safe_body.obj",body)
    export_obj("safe_door.obj",parts)
    curve = {"authoring":"Blender "+bpy.app.version_string,"action":"Safe_Open_And_Stay","fps":20,
             "start_frame":1,"end_frame":17,"duration_ticks":16,"hold_open":True,
             "hinge":[hinge_location.x,hinge_location.z,-hinge_location.y],"angles_degrees":samples}
    (assets/"safe_door_animation.json").write_text(json.dumps(curve,indent=2)+"\n",encoding="utf-8")
    
    for obj in scene.objects: obj.select_set(False)
    for obj in meshes+[hinge]: obj.select_set(True)
    bpy.context.view_layer.objects.active = hinge
    checkpoint("exporting_glb")
    if True:
        bpy.ops.export_scene.gltf(filepath=str(output/"safe_opening.glb"),export_format="GLB",use_selection=True,
                export_animations=True,export_frame_range=True,export_force_sampling=True,export_skins=False)
    checkpoint("glb_exported")
    
    # Preview setup lives in its own collection and is excluded from the model export.
    studio = bpy.data.collections.new("Preview_Studio")
    scene.collection.children.link(studio)
    camera_data = bpy.data.cameras.new("Safe_Preview_Camera")
    camera = bpy.data.objects.new("Safe_Preview_Camera",camera_data)
    studio.objects.link(camera)
    camera.location = (3.3,3.2,2.35)
    target = Vector((.5,-.7,.72))
    camera.rotation_euler = (target-camera.location).to_track_quat("-Z","Y").to_euler()
    camera_data.type = "ORTHO"
    camera_data.ortho_scale = 2.55
    scene.camera = camera
    for name, location, energy, size in [
            ("Safe_Key",(1.5,2.5,4),650,4),
            ("Safe_Fill",(-3,1,2),400,3),
            ("Safe_Rim",(1,-4,3),800,3)]:
        data = bpy.data.lights.new(name,"AREA"); data.energy=energy; data.shape="DISK"; data.size=size
        lamp = bpy.data.objects.new(name,data); studio.objects.link(lamp); lamp.location=location
        lamp.rotation_euler=(target-lamp.location).to_track_quat("-Z","Y").to_euler()
    scene.world = bpy.data.worlds.new("Safe_Studio_World")
    scene.world.color = (.035,.05,.07)
    try: scene.render.engine="BLENDER_EEVEE"
    except TypeError: scene.render.engine="BLENDER_EEVEE_NEXT"
    scene.render.resolution_x=960; scene.render.resolution_y=720; scene.render.resolution_percentage=100
    scene.view_settings.view_transform="AgX"
    scene["asset_credit"]="Simple Safe by avhatar / CC BY 4.0"
    scene["source"]="https://sketchfab.com/3d-models/simple-safe-2e308cb3fe1d4676beb43e75fdd27e8e"
    scene["behavior"]="Starts closed, opens once in 16 ticks, then stays open permanently"
    scene.frame_set(1)
    for area in window.screen.areas:
        if area.type=="VIEW_3D":
            area.spaces.active.region_3d.view_perspective="CAMERA"
    bpy.data.libraries.write(str(output/"safe_opening.blend"),{scene},path_remap="RELATIVE",compress=True)
    checkpoint("blend_saved")
    result={"scene":scene.name,"preserved_scene":previous_scene,"blend":str(output/"safe_opening.blend"),
            "glb":str(output/"safe_opening.glb"),"closed_geometry":closed,"animation":curve}
