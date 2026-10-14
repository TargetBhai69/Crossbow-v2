import json, sys, zipfile

CLASS_PREFIX = "io/grayray75/mods/fpsdisplay/FpsTick"
ENTRY = "io.grayray75.mods.fpsdisplay.FpsTick"

fps_path, mod_path, out_path = sys.argv[1:4]
mod = zipfile.ZipFile(mod_path)
fps = zipfile.ZipFile(fps_path)
mod_classes = [n for n in mod.namelist() if n.startswith(CLASS_PREFIX) and n.endswith(".class")]
if not mod_classes:
    sys.exit("ERROR: no compiled classes found in " + mod_path)

meta = json.loads(fps.read("fabric.mod.json").decode("utf-8"))
client = meta.setdefault("entrypoints", {}).setdefault("client", [])
if ENTRY not in client:
    client.append(ENTRY)
meta.setdefault("depends", {}).setdefault("fabric-events-interaction-v0", "*")

with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as out:
    for item in fps.infolist():
        if item.filename == "fabric.mod.json":
            out.writestr("fabric.mod.json", json.dumps(meta, indent=2))
        else:
            out.writestr(item, fps.read(item.filename))
    for n in mod_classes:
        out.writestr(n, mod.read(n))
print("Merged", len(mod_classes), "class(es) into", out_path)
