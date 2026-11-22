#!/usr/bin/env python3
"""Add the Android runtime's footstep hook to the staged rebuild catalog."""
import shutil
import sys
from pathlib import Path

root = Path(sys.argv[1])
variants = root / "variants"
hook = Path(__file__).with_name("hooks-footsteps.txt")
shutil.copyfile(hook, variants / hook.name)

# Match catalogHash() in the Rebuild Tool, including the filenames.
value = 0xcbf29ce484222325
for path in sorted(variants.glob("*.txt")):
    for byte in path.name.encode() + b"\0" + path.read_bytes() + b"\0":
        value = ((value ^ byte) * 1099511628211) & 0xffffffffffffffff
hash_text = f"{value:016x}"
(variants / "CATALOG_HASH").write_text(hash_text + "\n")

version = root / "VERSION"
lines = [line for line in version.read_text().splitlines() if not line.startswith("port_catalog ")]
lines.append("port_catalog " + hash_text)
version.write_text("\n".join(lines) + "\n")
