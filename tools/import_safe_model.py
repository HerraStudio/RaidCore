"""Convert the credited Sketchfab GLB into NeoForge's native static OBJ model.

Usage: python tools/import_safe_model.py path/to/simple_safe.glb
Only geometry, node transforms, normals and the original base-color PNG are used.
No artist textures are repainted or resampled.
"""
import hashlib
import json
import math
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/tactical_inventory"
IDENTITY = [[float(row == column) for column in range(4)] for row in range(4)]


def multiply(a, b):
    return [[sum(a[row][k] * b[k][column] for k in range(4))
             for column in range(4)] for row in range(4)]


def node_matrix(node):
    if "matrix" in node:
        return [[node["matrix"][column * 4 + row] for column in range(4)] for row in range(4)]
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    sx, sy, sz = node.get("scale", [1, 1, 1])
    tx, ty, tz = node.get("translation", [0, 0, 0])
    return [
        [(1 - 2*y*y - 2*z*z)*sx, (2*x*y - 2*z*w)*sy, (2*x*z + 2*y*w)*sz, tx],
        [(2*x*y + 2*z*w)*sx, (1 - 2*x*x - 2*z*z)*sy, (2*y*z - 2*x*w)*sz, ty],
        [(2*x*z - 2*y*w)*sx, (2*y*z + 2*x*w)*sy, (1 - 2*x*x - 2*y*y)*sz, tz],
        [0, 0, 0, 1],
    ]


def transform(matrix, point):
    return tuple(sum(matrix[row][k] * point[k] for k in range(3)) + matrix[row][3]
                 for row in range(3))


def normal_matrix(matrix):
    a, b, c = matrix[0][:3]
    d, e, f = matrix[1][:3]
    g, h, i = matrix[2][:3]
    cofactors = [[e*i-f*h, f*g-d*i, d*h-e*g],
                 [c*h-b*i, a*i-c*g, b*g-a*h],
                 [b*f-c*e, c*d-a*f, a*e-b*d]]
    determinant = a*cofactors[0][0] + b*cofactors[0][1] + c*cofactors[0][2]
    if abs(determinant) < 1e-12:
        raise ValueError("Singular model transform")
    return [[value/determinant for value in row] for row in cofactors]


def convert(path):
    raw = path.read_bytes()
    magic, version, length = struct.unpack_from("<4sII", raw)
    if magic != b"glTF" or version != 2 or length != len(raw):
        raise ValueError("Expected a complete GLB 2.0 file")
    offset, model, binary = 12, None, None
    while offset < length:
        size, chunk = struct.unpack_from("<II", raw, offset)
        data = raw[offset+8:offset+8+size]
        if chunk == 0x4e4f534a:
            model = json.loads(data)
        elif chunk == 0x004e4942:
            binary = data
        offset += 8 + size
    if model is None or binary is None:
        raise ValueError("Missing model or binary chunk")

    def accessor(index):
        item = model["accessors"][index]
        view = model["bufferViews"][item["bufferView"]]
        components = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[item["type"]]
        kind = {5126: "f", 5125: "I", 5123: "H", 5121: "B"}[item["componentType"]]
        pattern = "<" + kind * components
        stride = view.get("byteStride", struct.calcsize(pattern))
        start = view.get("byteOffset", 0) + item.get("byteOffset", 0)
        return [struct.unpack_from(pattern, binary, start + n*stride) for n in range(item["count"])]

    meshes = []
    door_hinge = None

    def visit(index, parent):
        nonlocal door_hinge
        node = model["nodes"][index]
        matrix = multiply(parent, node_matrix(node))
        if node.get("name") == "Safe1_Door":
            door_hinge = transform(matrix, (0, 0, 0))
        if "mesh" in node:
            for primitive in model["meshes"][node["mesh"]]["primitives"]:
                if primitive.get("mode", 4) != 4:
                    raise ValueError("Expected triangle geometry")
                attributes = primitive["attributes"]
                normals = normal_matrix(matrix)
                vertices = [transform(matrix, position) for position in accessor(attributes["POSITION"])]
                directions = []
                for normal in accessor(attributes["NORMAL"]):
                    n = [sum(normals[row][k]*normal[k] for k in range(3)) for row in range(3)]
                    size = math.sqrt(sum(value*value for value in n))
                    directions.append(tuple(value/size for value in n))
                meshes.append((node.get("name", f"part_{index}"), vertices, directions,
                               accessor(attributes["TEXCOORD_0"]), accessor(primitive["indices"])))
        for child in node.get("children", []):
            visit(child, matrix)

    for root in model["scenes"][model.get("scene", 0)]["nodes"]:
        visit(root, IDENTITY)
    # The author's front points south; make the Minecraft default front face north.
    positions = [(-v[0], v[1], -v[2]) for mesh in meshes for v in mesh[1]]
    minimum = [min(v[axis] for v in positions) for axis in range(3)]
    maximum = [max(v[axis] for v in positions) for axis in range(3)]
    # Twice the initial 0.96-block fit, centered over the same placement anchor.
    scale = 1.92 / max(maximum[axis]-minimum[axis] for axis in range(3))
    center = [(maximum[axis]+minimum[axis])/2 for axis in range(3)]
    translation = [0.5-center[0]*scale, 0.02-minimum[1]*scale, 0.5-center[2]*scale]
    target = ASSETS / "models/block"
    target.mkdir(parents=True, exist_ok=True)
    if door_hinge is None:
        raise ValueError("Missing the author's door hinge")
    hinge = [-door_hinge[0]*scale+translation[0], door_hinge[1]*scale+translation[1],
             -door_hinge[2]*scale+translation[2]]
    cosine, sine = math.cos(math.radians(-60)), math.sin(math.radians(-60))

    def close_point(point):
        x, y, z = (point[i]-hinge[i] for i in range(3))
        return [cosine*x+sine*z+hinge[0], y+hinge[1], -sine*x+cosine*z+hinge[2]]

    closed_meshes = []
    for index, (name, vertices, normals, uv, indices) in enumerate(meshes):
        fitted = [[-x*scale+translation[0], y*scale+translation[1], -z*scale+translation[2]] for x,y,z in vertices]
        directions = [[-x,y,-z] for x,y,z in normals]
        if index != 0:
            fitted = [close_point(point) for point in fitted]
            directions = [[cosine*x+sine*z,y,-sine*x+cosine*z] for x,y,z in directions]
        closed_meshes.append((name,fitted,directions,uv,indices))

    def write_obj(filename, selected):
        lines = ["# Simple Safe by avhatar, CC BY 4.0; see safe_credits.txt", "mtllib safe.mtl"]
        first, count = 1, 0
        for name, vertices, normals, uv, indices in selected:
            lines += [f"g {name}", "usemtl safe"]
            lines += ["v "+" ".join(f"{value:.8f}" for value in point) for point in vertices]
            lines += [f"vt {u:.8f} {1-v:.8f}" for u,v in uv]
            lines += ["vn "+" ".join(f"{value:.8f}" for value in normal) for normal in normals]
            for index in range(0,len(indices),3):
                face = [indices[index+n][0]+first for n in range(3)]
                lines.append("f "+" ".join(f"{value}/{value}/{value}" for value in face))
                count += 1
            first += len(vertices)
        (target/filename).write_text("\n".join(lines)+"\n",encoding="utf-8")
        return first-1,count

    first_index, triangles = write_obj("safe.obj",closed_meshes)
    write_obj("safe_body.obj",closed_meshes[:1])
    write_obj("safe_door.obj",closed_meshes[1:])
    (target / "safe.mtl").write_text("newmtl safe\nKa 0 0 0\nKd 1 1 1\nd 1\nmap_Kd #safe_color\n", encoding="utf-8")
    image_index = model["textures"][model["materials"][0]["pbrMetallicRoughness"]["baseColorTexture"]["index"]]["source"]
    image = model["images"][image_index]
    if image.get("mimeType") != "image/png":
        raise ValueError("Expected the author's unmodified PNG base color")
    view = model["bufferViews"][image["bufferView"]]
    png = binary[view.get("byteOffset", 0):view.get("byteOffset", 0)+view["byteLength"]]
    texture = ASSETS / "textures/block/safe_color.png"
    texture.parent.mkdir(parents=True, exist_ok=True)
    texture.write_bytes(png)
    bounds = {"min": [minimum[i]*scale+translation[i] for i in range(3)],
              "max": [maximum[i]*scale+translation[i] for i in range(3)]}
    closed_positions = [point for mesh in closed_meshes for point in mesh[1]]
    closed_bounds = {"min": [min(point[i] for point in closed_positions) for i in range(3)],
                     "max": [max(point[i] for point in closed_positions) for i in range(3)]}
    report = {"source": model["asset"].get("extras", {}), "source_sha256": hashlib.sha256(raw).hexdigest(),
              "mesh_parts": len(meshes), "vertices": first_index, "triangles": triangles,
              "bounds": bounds, "closed_bounds": closed_bounds, "door_hinge": hinge,"open_angle_degrees":60,
              "base_color_sha256": hashlib.sha256(png).hexdigest(),
              "changes": "GLB geometry converted to OBJ at twice the initial fit. Door, handle and lock reposed closed and split around the original hinge for a 60-degree opening animation. Original base-color PNG retained; Minecraft provides lighting."}
    (target / "safe_import.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    convert(Path(sys.argv[1]))
