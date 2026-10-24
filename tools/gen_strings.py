"""Generates res/values/strings.xml (English), values-fr and values-ar from tools/strings_data.py.
Usage: python3 tools/gen_strings.py   (also verifies that every R.string.* used in Kotlin exists)"""
import re, sys, pathlib
from xml.sax.saxutils import escape

root = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "tools"))
from strings_data import S  # noqa: E402

def esc(s: str) -> str:
    s = escape(s).replace("'", "\\'").replace('"', '\\"')
    s = re.sub(r"%(?!\d\$[sd])", "%%", s)
    s = s.replace("\n", "\\n")
    if s.startswith("@") or s.startswith("?"):
        s = "\\" + s
    return s

for idx, folder in enumerate(["values", "values-fr", "values-ar"]):
    d = root / "app/src/main/res" / folder
    d.mkdir(parents=True, exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for k, v in S.items():
        fmt = ' formatted="true"' if "%" in v[idx] else ""
        lines.append(f'    <string name="{k}"{fmt}>{esc(v[idx])}</string>')
    lines.append("</resources>")
    (d / "strings.xml").write_text("\n".join(lines) + "\n", encoding="utf-8")

used = set()
for f in (root / "app/src").rglob("*.kt"):
    used |= set(re.findall(r"R\.string\.([a-z0-9_]+)", f.read_text(encoding="utf-8")))
missing = sorted(used - set(S))
unused = sorted(set(S) - used)
print(f"{len(S)} strings x 3 languages written; used={len(used)}")
if missing:
    print("MISSING:", missing); sys.exit(1)
# every translation must keep the same placeholders
bad = []
for k, v in S.items():
    ph = [sorted(re.findall(r"%\d\$[sd]", t)) for t in v]
    if not (ph[0] == ph[1] == ph[2]):
        bad.append(k)
if bad:
    print("PLACEHOLDER MISMATCH:", bad); sys.exit(1)
print("unused (ok):", unused)
