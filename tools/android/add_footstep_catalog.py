#!/usr/bin/env python3
"""Add the Android runtime's hook catalogs (tools/android/hooks-*.txt) to the staged rebuild catalog."""
import shutil
import sys
from pathlib import Path

root = Path(sys.argv[1])
variants = root / "variants"
hooks = sorted(Path(__file__).parent.glob("hooks-*.txt"))
names = {hook.name for hook in hooks}
def catalog_hash(paths):
    # Match catalogHash() in the Rebuild Tool, including the filenames.
    value = 0xcbf29ce484222325
    for path in sorted(paths):
        for byte in path.name.encode() + b"\0" + path.read_bytes() + b"\0":
            value = ((value ^ byte) * 1099511628211) & 0xffffffffffffffff
    return f"{value:016x}"


version = root / "VERSION"
lines = version.read_text().splitlines()
hash_file = variants / "CATALOG_HASH"
expected = next((line.split()[1] for line in lines if line.startswith("upstream_catalog ")),
                hash_file.read_text().strip())
# A port catalog named like one of the package's own would hide it here: the hash then differs.
upstream = catalog_hash(path for path in variants.glob("*.txt") if path.name not in names)
if upstream != expected:
    sys.exit(f"Unmodified Rebuild Tool catalog hash is {upstream}, package says {expected}")

for hook in hooks:
    shutil.copyfile(hook, variants / hook.name)
bundled = catalog_hash(variants.glob("*.txt"))
hash_file.write_text(bundled + "\n")
lines = [line for line in lines if not line.startswith(("upstream_catalog ", "port_catalog "))]
lines.extend(("upstream_catalog " + upstream, "port_catalog " + bundled))
version.write_text("\n".join(lines) + "\n")
