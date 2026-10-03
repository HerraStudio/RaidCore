"""Check feature folders and optionally compare runtime JAR contents before/after a move."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8")
root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--baseline-jar", type=Path)
args = parser.parse_args()
layout = json.loads((root / "docs/module-layout.json").read_text(encoding="utf-8"))
modules = [entry["name"] for entry in layout["modules"]]
assert modules == ["ui", "inventory", "loot", "action", "player", "network", "api", "raid"]
core = root / "src/main/java"
java_roots = [core] + [root / module / "src/main/java" for module in modules]
resource_roots = [root / "src/main/resources"] + [root / module / "src/main/resources" for module in modules]
annotations = []
java_files = []
for source_root in java_roots:
    for path in source_root.rglob("*.java"):
        java_files.append(path)
        text = path.read_text(encoding="utf-8")
        package = re.search(r"^package\s+([^;]+);", text, re.M).group(1)
        assert path.relative_to(source_root).as_posix() == package.replace(".", "/") + "/" + path.name, path
        if re.search(r"^\s*@Mod\(", text, re.M):
            annotations.append(path.relative_to(root).as_posix())
assert annotations == [layout["core"]], annotations
resources = {}
for source_root in resource_roots:
    for path in source_root.rglob("*"):
        if not path.is_file():
            continue
        name = path.relative_to(source_root).as_posix()
        assert name not in resources, f"Duplicate runtime resource: {name}"
        resources[name] = path
for row in layout["relocations"]:
    path = root / row["to"]
    assert path.is_file() and not (root / row["from"]).is_file(), row
    if row["source_set"] == "main":
        assert hashlib.sha256(path.read_bytes()).hexdigest() == row["sha256"], f"Runtime source changed during move: {path}"

result = {"passed": True, "modules": modules, "main_java_files": len(java_files),
          "runtime_resource_files": len(resources), "single_mod_entry": annotations[0],
          "duplicate_resource_paths": 0, "java_package_paths_preserved": True}
if args.baseline_jar:
    properties = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text(encoding="utf-8").splitlines() if "=" in line)
    jar_path = root / "build/libs" / ("raidcore-1.21.1-neoforge-" + properties["mod_version"] + ".jar")
    with zipfile.ZipFile(args.baseline_jar) as old, zipfile.ZipFile(jar_path) as current:
        old_names = {entry.filename for entry in old.infolist() if not entry.is_dir()}
        names = {entry.filename for entry in current.infolist() if not entry.is_dir()}
        assert old_names == names, {"missing": sorted(old_names - names), "extra": sorted(names - old_names)}
        changed = [name for name in sorted(names) if old.read(name) != current.read(name)]
        assert not changed, f"Runtime contents changed: {changed}"
        result["runtime_jar_entry_payloads_byte_identical"] = len(names)
        result["compiled_classes_byte_identical"] = sum(name.endswith(".class") for name in names)
        result["baseline_jar_sha256"] = hashlib.sha256(args.baseline_jar.read_bytes()).hexdigest()
        result["current_jar_sha256"] = hashlib.sha256(jar_path.read_bytes()).hexdigest()
    (root / "docs/module-validation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
