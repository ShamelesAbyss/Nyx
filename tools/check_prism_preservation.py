#!/usr/bin/env python3
"""Auditable source preservation gate. Complements UI tests; does not claim device acceptance."""
from pathlib import Path
from collections import Counter
import hashlib, json, re, subprocess
ROOT = Path(__file__).resolve().parents[1]
inv = json.loads((ROOT / "docs/prism/control-inventory.json").read_text())
files = [ROOT / "app/src/main/java/com/nyx/ai/MainActivity.kt", *(ROOT / "app/src/main/java/com/nyx/ai/ui").rglob("*.kt")]
current = Counter(m.group(1) for p in files for m in re.finditer(r"\bvm(?:::|\.)(\w+)", p.read_text()))
baseline = Counter(row["handler"] for row in inv["bindings"])
# Console Resume now opens the already-existing explicit recovery review, which still owns resumeObjective.
allowed_relocations = Counter({"resumeObjective": 1})
missing = baseline - current
assert not (missing - allowed_relocations), f"Lost ViewModel bindings: {missing}"
assert current["resumeObjective"] >= 1, "Reviewed resume must remain"
for row in inv["controls"]:
    assert hashlib.sha256(row["baselineCall"].encode()).hexdigest() == row["baselineSha256"], row["id"]
    assert row["newLocation"] and row["verification"], row["id"]
# PRISM is presentation work: preserve every protected production source byte.
changed = subprocess.check_output(["git", "diff", "--name-only", inv["baseline"], "--", "app/src/main"], cwd=ROOT, text=True).splitlines()
allowed = lambda name: name == "app/src/main/java/com/nyx/ai/MainActivity.kt" or name == "app/src/main/java/com/nyx/ai/NyxViewModel.kt" or name.startswith("app/src/main/java/com/nyx/ai/ui/")
assert all(allowed(name) for name in changed), f"Unexpected engine/native/data/manifest/resource change: {changed}"
# The ViewModel exception is limited to the read-only selection adapter, never an execution method.
before = subprocess.check_output(["git", "show", inv["baseline"] + ":app/src/main/java/com/nyx/ai/NyxViewModel.kt"], cwd=ROOT, text=True)
after = (ROOT / "app/src/main/java/com/nyx/ai/NyxViewModel.kt").read_text()
def without_selection(text):
    start = text.index("    fun selectObjective(")
    end = text.index("\n    fun ", start + 5)
    return text[:start] + text[end:]
assert without_selection(before) == without_selection(after), "ViewModel edits outside selection adapter"
print(f"PASS {len(inv['controls'])} archived primitive control sites; {len(inv['bindings'])} original VM bindings retained or explicitly reviewed; protected production sources unchanged")
