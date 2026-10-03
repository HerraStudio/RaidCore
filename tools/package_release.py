"""Package a verified RaidCore build without dependencies, caches or game saves."""
from pathlib import Path
import hashlib
import json
import shutil
import sys
import tomllib
import xml.etree.ElementTree as ET
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[1]
PROPERTIES = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
VERSION = PROPERTIES["mod_version"]
STEM = f"raidcore-1.21.1-neoforge-{VERSION}"
OUTPUT = ROOT / "releases" / f"v{VERSION}"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def native_log(kind, marker):
    paths = [ROOT / ".audit" / f"{kind}-smoke.log", ROOT / f"run-{kind}-smoke/logs/latest.log"]
    path = next((path for path in paths if path.is_file()), None)
    if path is None:
        raise RuntimeError(f"Missing {kind} smoke log; run the isolated native check first.")
    text = path.read_text(encoding="utf-8")
    assert marker in text and "[FATAL]" not in text and "Exception caught during firing event" not in text, path
    assert f"RaidCore {VERSION} initialized" in text, f"Native log is not from version {VERSION}"
    if path.parent.name == ".audit":
        assert "BUILD SUCCESSFUL" in text, path
    return text


reports = sorted((ROOT / "build/test-results/test").glob("TEST-*.xml"))
assert reports, "Build and run the unit tests first."
groups = {name: {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
          for name in ("Drag Inventory", "Tactical Actions", "Tactical Inventory")}
for report in reports:
    suite = ET.parse(report).getroot()
    name = suite.attrib["name"]
    group = "Drag Inventory" if name.startswith("dev.draginventory.") else (
        "Tactical Actions" if name.startswith("dev.herrastudio.tacticalactions.") else "Tactical Inventory")
    for key in groups[group]:
        groups[group][key] += int(suite.attrib.get(key, 0))
total = {key: sum(group[key] for group in groups.values()) for key in next(iter(groups.values()))}
assert total["tests"] > 0 and total["failures"] == total["errors"] == total["skipped"] == 0, total

client_log = native_log("client", "RAIDCORE_CLIENT_SMOKE_PASS")
server_log = native_log("server", "RAIDCORE_SERVER_SMOKE_PASS")
jar = ROOT / "build/libs" / (STEM + ".jar")
source_jar = ROOT / "build/libs" / (STEM + "-sources.jar")
assert jar.is_file() and source_jar.is_file()
with zipfile.ZipFile(jar) as archive:
    descriptor = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
    assert len(descriptor["mods"]) == 1 and descriptor["mods"][0]["modId"] == "raidcore"
    assert descriptor["mods"][0]["version"] == VERSION
    assert not any(name.endswith(".jar") or "/integration/" in name for name in archive.namelist())

parity_path = ROOT / "docs/source-parity.json"
parity = json.loads(parity_path.read_text(encoding="utf-8"))
assert parity["passed"] and parity["unselected_projects_imported"] == 0
summary = {"date": "2026-10-03", "timezone": "Asia/Shanghai", "version": VERSION,
           "environment": "Windows, Java 21, Minecraft 1.21.1, NeoForge 21.1.251",
           "unit_tests": {"groups": groups, "total": total, "suites": len(reports)},
           "client_native_pass": True, "dedicated_server_native_pass": True,
           "source_parity": {key: value for key, value in parity.items() if key != "permitted_bootstrap_and_event_owner_edits"},
           "jar_sha256": sha256(jar), "sources_jar_sha256": sha256(source_jar)}
module_validation = ROOT / 'docs/module-validation.json'
if module_validation.is_file():
    module_result = json.loads(module_validation.read_text(encoding='utf-8'))
    assert module_result['passed'] and module_result['current_jar_sha256'] == sha256(jar)
    summary['module_layout'] = module_result
(ROOT / "docs/validation-results.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
evidence = ["RaidCore native integration — 2026-10-03 (Asia/Shanghai)"]
for label, text in (("CLIENT", client_log), ("DEDICATED SERVER", server_log)):
    evidence.append("\n" + label)
    evidence.extend(line for line in text.splitlines() if "_PASS" in line or "GOLD_RENDER_COUNTS:" in line)
(ROOT / "docs/runtime-validation.txt").write_text("\n".join(evidence) + "\n", encoding="utf-8")

images = ROOT / "docs/images"
images.mkdir(exist_ok=True)
for old, new in (("gold-inventory.png", "raidcore-inventory.png"), ("raidcore-hud.png", "raidcore-hud.png"),
                 ("raidcore-map.png", "raidcore-map.png")):
    source = ROOT / "run-client-smoke" / old
    if source.is_file():
        shutil.copy2(source, images / new)

OUTPUT.mkdir(parents=True, exist_ok=True)
shutil.copy2(jar, OUTPUT / jar.name)
shutil.copy2(source_jar, OUTPUT / source_jar.name)
root_files = {".gitignore", ".gitattributes", "build.gradle", "settings.gradle", "gradle.properties",
              "gradlew", "gradlew.bat", "LICENSE", "README.md", "CHANGELOG.md", "ASSET_LICENSES.md",
              "THIRD_PARTY_NOTICES.md", "TEMPLATE_LICENSE.txt"}
root_dirs = {"gradle", "src", "docs", "model-source", "tools", "verification", "licenses", "libs"}
root_dirs.update({"ui", "inventory", "loot", "action", "player", "network", "api", "raid"})
project_zip = OUTPUT / f"RaidCore-{VERSION}-project.zip"
included = []
with zipfile.ZipFile(project_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for path in sorted(ROOT.rglob("*")):
        relative = path.relative_to(ROOT)
        if not path.is_file() or not (relative.as_posix() in root_files or relative.parts[0] in root_dirs):
            continue
        if "__pycache__" in relative.parts or path.suffix in {".pyc", ".blend1"}:
            continue
        if path.suffix == ".jar" and relative.as_posix() != "gradle/wrapper/gradle-wrapper.jar":
            continue
        if relative.parts[0] == "libs" and relative.as_posix() != "libs/README.md":
            continue
        assert not path.is_symlink(), path
        archive.write(path, "RaidCore/" + relative.as_posix())
        included.append(relative.as_posix())
with zipfile.ZipFile(project_zip) as archive:
    assert archive.testzip() is None
    assert "RaidCore/libs/gwo.jar" not in archive.namelist()
    assert "RaidCore/model-source/gold_bar/gold_bar_inspection.blend" in archive.namelist()
    assert "RaidCore/src/main/java/dev/herrastudio/raidcore/RaidCore.java" in archive.namelist()
    assert "RaidCore/inventory/src/main/java/dev/tactical/BagState.java" in archive.namelist()
    assert "RaidCore/network/src/main/java/dev/tactical/Packets.java" in archive.namelist()
    assert "RaidCore/loot/src/main/resources/assets/tactical_inventory/models/loot/gold_bar.glb" in archive.namelist()

notice = f"""RaidCore {VERSION} — 2026-10-03

合并：Drag Inventory 2.6.1 + Tactical Actions 1.1.0 + Tactical Inventory 1.0.20。
只需安装 {STEM}.jar；-sources.jar 与 project.zip 用于开发。
在客户端和服务器中移出原三个独立模组，再安装 RaidCore 并完整重启。
保留 GWO（内部版本 2.12.87）、LDLib2 2.2.40+（2.2.x）；客户端还需 Photon 2.2.7+（2.2.x）和 Player Animation Library 1.1.6+（MC 1.21.1）。
要求 Minecraft 1.21.1 / NeoForge 21.1.251 至 21.1.x / Java 21。
旧物品 ID、存档数据键、配置文件和按键名称保留。

验证：{total['tests']} 项单元测试全部通过；独立客户端与服务端原生检查通过。
完整源码包包含源码、文档、测试、模型工程和 Gradle Wrapper，不含第三方模组、缓存或游戏存档。
"""
(OUTPUT / "安装说明.txt").write_text(notice, encoding="utf-8")
artifacts = [OUTPUT / jar.name, OUTPUT / source_jar.name, project_zip, OUTPUT / "安装说明.txt"]
(OUTPUT / "SHA256SUMS.txt").write_text("".join(f"{sha256(path)}  {path.name}\n" for path in artifacts), encoding="utf-8")
print(json.dumps({"version": VERSION, "unit_tests": total, "source_zip_files": len(included),
                  "outputs": [{"file": str(path), "bytes": path.stat().st_size, "sha256": sha256(path)} for path in artifacts]},
                 ensure_ascii=False, indent=2))
