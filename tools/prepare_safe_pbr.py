"""Convert the safe's original GLB material to Iris LabPBR 1.3 sibling maps.

Usage: python tools/prepare_safe_pbr.py [path/to/safe.glb]
Requires Pillow. Geometry, UVs and the existing base-color PNG are untouched.
"""
import hashlib
import io
import json
import struct
import sys
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
TEXTURES = ROOT / "loot/src/main/resources/assets/tactical_inventory/textures/block"


def prepare(source):
    raw = source.read_bytes()
    if struct.unpack_from("<4sII", raw) != (b"glTF", 2, len(raw)):
        raise ValueError("Expected a complete GLB 2.0 file")
    chunks = {}
    offset = 12
    while offset < len(raw):
        size, kind = struct.unpack_from("<II", raw, offset)
        chunks[kind] = raw[offset + 8:offset + 8 + size]
        offset += size + 8
    model = json.loads(chunks[0x4E4F534A])
    binary = chunks[0x004E4942]
    if len(model["materials"]) != 1:
        raise ValueError("Expected the safe's single shared material")
    material = model["materials"][0]
    pbr = material["pbrMetallicRoughness"]
    if (pbr.get("metallicFactor", 1) != 1 or pbr.get("roughnessFactor", 1) != 1
            or material["normalTexture"].get("scale", 1) != 1):
        raise ValueError("Unexpected material factors; review the conversion")

    def image_bytes(binding):
        if binding.get("texCoord", 0) != 0 or binding.get("extensions"):
            raise ValueError("Expected untransformed TEXCOORD_0 textures")
        image = model["images"][model["textures"][binding["index"]]["source"]]
        if image.get("mimeType") != "image/png":
            raise ValueError("Expected the author's embedded PNG texture")
        view = model["bufferViews"][image["bufferView"]]
        start = view.get("byteOffset", 0)
        return binary[start:start + view["byteLength"]]

    color_bytes = image_bytes(pbr["baseColorTexture"])
    if color_bytes != (TEXTURES / "safe_color.png").read_bytes():
        raise ValueError("Source material does not match the runtime safe UV atlas")
    normal_bytes = image_bytes(material["normalTexture"])
    packed_bytes = image_bytes(pbr["metallicRoughnessTexture"])
    color = Image.open(io.BytesIO(color_bytes))
    normal = Image.open(io.BytesIO(normal_bytes)).convert("RGB")
    packed = Image.open(io.BytesIO(packed_bytes)).convert("RGB")
    if normal.size != color.size or packed.size != color.size:
        raise ValueError("Expected matching source texture sizes without resampling")

    # glTF/OpenGL Y+ normals -> LabPBR/DirectX Y-. No AO or height in this source.
    full = Image.new("L", color.size, 255)
    zero = Image.new("L", color.size, 0)
    Image.merge("RGBA", (normal.getchannel("R"), ImageOps.invert(normal.getchannel("G")),
                         full, full)).save(TEXTURES / "safe_color_n.png")
    # glTF G is perceptual roughness, B is metalness. Preserve the artist's wear.
    # Generic conductor 255 uses albedo as F0, retaining both steel and brass colors.
    # Dielectric paint/rust uses 4% F0. B/A disable porosity, SSS and emission.
    smoothness = ImageOps.invert(packed.getchannel("G"))
    reflectance = packed.getchannel("B").point(lambda value: 255 if value >= 128 else 10)
    Image.merge("RGBA", (smoothness, reflectance, zero, zero)).save(TEXTURES / "safe_color_s.png")

    report = {
        "source": source.relative_to(ROOT).as_posix() if source.is_relative_to(ROOT) else source.name,
        "source_sha256": hashlib.sha256(raw).hexdigest(),
        "source_normal_sha256": hashlib.sha256(normal_bytes).hexdigest(),
        "source_metallic_roughness_sha256": hashlib.sha256(packed_bytes).hexdigest(),
        "base_color_sha256": hashlib.sha256(color_bytes).hexdigest(),
        "texture_size": list(color.size),
        "format": "LabPBR 1.3",
        "normal_channels": "R=source X, G=255-source Y, B=255 (no AO), A=255 (flat height)",
        "specular_channels": "R=255-source roughness, G=255 for source metalness>=128 else 10, B=0, A=0",
        "outputs": {name: hashlib.sha256((TEXTURES / name).read_bytes()).hexdigest()
                    for name in ("safe_color_n.png", "safe_color_s.png")},
    }
    (ROOT / "model-source/safe/safe_pbr.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / "model-source/safe/safe_opening.glb")
