"""Apply the verified smoke resource to an existing release, preserving every other entry."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RESOURCE = "assets/tactical_inventory/fx/evacuation_smoke.fx"
KEY = b"\x08\x00\x0dcompositeMode\x00\x07"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    baseline, output = args.baseline.resolve(), args.output.resolve()
    assert baseline != output, "Preserve the original release."
    resource = (ROOT / "raid/src/main/resources" / RESOURCE).read_bytes()
    fixed_nbt = gzip.decompress(resource)
    assert fixed_nbt.count(KEY + b"VANILLA") == 1
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(baseline) as source:
        assert not any(n.endswith((".SF", ".RSA", ".DSA")) for n in source.namelist()), "Signed JAR is unsupported."
        original_nbt = gzip.decompress(source.read(RESOURCE))
        assert original_nbt.count(KEY + b"INHERIT") == 3
        assert fixed_nbt.replace(KEY + b"VANILLA", KEY + b"INHERIT") == original_nbt
        with zipfile.ZipFile(output, "w") as target:
            for entry in source.infolist():
                target.writestr(entry, resource if entry.filename == RESOURCE else source.read(entry.filename))
    with zipfile.ZipFile(baseline) as source, zipfile.ZipFile(output) as target:
        assert source.namelist() == target.namelist()
        changed = [n for n in source.namelist() if source.read(n) != target.read(n)]
        assert changed == [RESOURCE], changed
        classes = [n for n in source.namelist() if n.endswith(".class")]
        assert all(source.read(n) == target.read(n) for n in classes)
        assert target.testzip() is None
    manifest = {
        "baseline": str(baseline), "baseline_sha256": sha256(baseline),
        "artifact": str(output), "artifact_sha256": sha256(output),
        "changed_entries": changed, "unchanged_production_classes": len(classes),
        "descriptor_and_network_unchanged": True,
        "smoke_resource_sha256": hashlib.sha256(resource).hexdigest(),
    }
    output.with_suffix(".json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
