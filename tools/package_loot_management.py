"""Package the tested loot-management build and its source without game saves or dependencies."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
PROPERTIES=dict(line.split("=",1) for line in (ROOT/"gradle.properties").read_text(encoding="utf-8").splitlines() if "=" in line)
VERSION=PROPERTIES["mod_version"]
STEM=f"raidcore-1.21.1-neoforge-{VERSION}"
OUTPUT=ROOT/"releases/loot-management"
OUTPUT.mkdir(parents=True,exist_ok=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


counts={key:0 for key in ["tests","failures","errors","skipped"]}
for report in (ROOT/"build/test-results/test").glob("TEST-*.xml"):
    attrs=ET.parse(report).getroot().attrib
    for key in counts: counts[key]+=int(attrs.get(key,0))
assert counts["tests"]>0 and counts["failures"]==counts["errors"]==counts["skipped"]==0,counts
native={}
for name,markers in {
    "client":["RAIDCORE_LOOT_MANAGEMENT_PASS","RAIDCORE_LOOT_PICKUP_PRIORITY_PASS","RAIDCORE_MODDED_BLOCK_LOOT_PASS"],
    "server":["RAIDCORE_LOOT_SERVER_PASS","RAIDCORE_SERVER_SMOKE_PASS"],
}.items():
    log=(ROOT/f".audit/loot-management-{name}.log").read_text(encoding="utf-8")
    assert "BUILD SUCCESSFUL" in log and f"RaidCore {VERSION} initialized" in log
    assert "Exception caught during firing event" not in log and "[FATAL]" not in log
    assert all(marker in log for marker in markers)
    native[name]=[line.strip() for line in log.splitlines() if any(marker in line for marker in markers)]
    if name=="client": assert "doomsday_decoration:acrate_2" in "\n".join(native[name])
    (ROOT/f"docs/loot-management-{name}-validation.txt").write_text("\n".join(native[name])+"\n",encoding="utf-8")

jar=ROOT/f"build/libs/{STEM}.jar"
with zipfile.ZipFile(jar) as archive:
    names=archive.namelist()
    for required in ["dev/tactical/loot/LootService.class","dev/tactical/loot/BlockLootData.class",
                     "dev/tactical/loot/client/LootAdminScreen.class","dev/tactical/mixin/LootKeysMixin.class"]:
        assert required in names,required
    assert not any("/integration/" in name or name.endswith(".jar") or name.startswith("net/mcreator/") for name in names)
    assert "assets/minecraft/models/block/stone.json" not in names
    assert archive.testzip() is None
target=OUTPUT/f"{STEM}-loot-management.jar"
shutil.copyfile(jar,target)
source_jar=OUTPUT/f"{STEM}-loot-management-sources.jar"
shutil.copyfile(ROOT/f"build/libs/{STEM}-sources.jar",source_jar)

summary={
    "date":"2026-10-05","timezone":"Asia/Shanghai","version":VERSION,"unit_tests":counts,
    "native":native,"modded_block":"doomsday_decoration:acrate_2",
    "original_search_ui_preserved":True,"default_mod_block_catalog":True,"client_and_server_update_required":True,
    "artifacts":{path.name:digest(path) for path in [target,source_jar]},
}
(ROOT/"docs/loot-management-results.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")

source_zip=OUTPUT/f"{STEM}-loot-management-project.zip"
excluded={".git",".gradle",".audit","build","releases",".idea","__pycache__","node_modules"}
with zipfile.ZipFile(source_zip,"w",zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for folder,dirs,files in os.walk(ROOT):
        dirs[:]=[name for name in dirs if name not in excluded and name!="run" and not name.startswith("run-")]
        for name in files:
            path=Path(folder)/name
            if path.is_symlink() or name.endswith((".blend1",".iml",".log")): continue
            if name.endswith(".jar") and path.relative_to(ROOT).as_posix()!="gradle/wrapper/gradle-wrapper.jar": continue
            archive.write(path,"RaidCore/"+path.relative_to(ROOT).as_posix())

summary["artifacts"][source_zip.name]=digest(source_zip)
(OUTPUT/"validation.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
(OUTPUT/"SHA256SUMS.txt").write_text("\n".join(f"{value}  {name}" for name,value in summary["artifacts"].items())+"\n",encoding="ascii")
(OUTPUT/"安装与使用.txt").write_text(
    "搜刮管理扩展版\n\n客户端和服务端一起替换旧 RaidCore JAR，然后重启。保留现有 GWO、LDLib2、Photon 和 PAL；末日装饰继续使用已有独立模组。\n\n"
    "管理员使用 /tacticalloot，或从背包顶部进入“搜刮管理”。搜索 doomsday_decoration:acrate_2，开启允许 F 搜刮，填写容器名称、系数并保存。"
    "物品基础爆率在原物品配置页设置，并开启加入搜刽物品池。准星对准容器后按 F 使用原搜刮界面。\n\n"
    "例如：基础 25%、系数 4，判断概率 100%。未生成的容器首次搜刮应用新规则；已有内容保留，重复打开不刷新。\n"
    "详细说明在项目 docs/LOOT_MANAGEMENT.md，完整项目包不含游戏实例、缓存或第三方依赖 JAR。\n",encoding="utf-8")
print(json.dumps({"version":VERSION,"tests":counts,"artifact":str(target),"modded_block":summary["modded_block"]},ensure_ascii=False))
