#!/usr/bin/env python3
"""Kishi V3 Pro footstep haptics made from the game's own footstep sounds.

Usage: footstep_haptics.py <game folder or sprj_main_<lang>.fsb> [output]
Needs vgmstream-cli. Writes <game folder>/haptics/footsteps.txt by default: per floor material a
family, and per family walk and run tracks of amplitude (0-63) and frequency index (0-127) per
7.5 ms slot. The file is made from your game files: do not share it.
"""
import math
import re
import struct
import subprocess
import sys
import tempfile
import wave
from array import array
from pathlib import Path

SLOT_S = 0.0075
MAX_SLOTS = 32  # 240 ms
GATE = 0.15  # of the peak: below it is the reverb tail
FREQ_LO, FREQ_HI = 150.0, 4000.0  # sound brightness range mapped onto...
INDEX_LO, INDEX_HI = 8, 40  # ...the Kishi's frequency index (about 53 to 146 Hz)

# Sample name prefixes (followed by a digit) for walk and run.
FAMILIES = {
    "stone": (["foot-stone-w"], ["foot-stone-r"]),
    "stone_dry": (["foot_stone_walk_dry"], ["foot_stone_run_dry"]),
    "soil": (["foot-soil-w"], ["foot-soil-r"]),
    "wood": (["foot-wood-w"], ["foot-wood-r"]),
    "grass": (["foot-grass-w"], ["foot-grass-r"]),
    "gravel": (["foot_jari_walk"], ["foot_jari_run"]),
    "snow": (["foot_snow_walk"], ["foot_snow_run"]),
    "sand": (["foot_walk_suna"], ["foot_run_suna"]),
    "bone": (["foot_bone_walk"], ["foot_bone_run"]),
    "leaves": (["foot_ochiba_walk"], ["foot_ochiba_run"]),
    "water": (["foot_mizutamari_walk"], ["foot_mizutamari_run"]),
    "mud": (["foot_mud_walk"], ["foot_mud_run"]),
    "tar": (["sprj_foot_coaltar", "foot_coaltar"], ["sprj_foot_coaltar", "foot_coaltar"]),
    "corpse": (["sprj_foot_shitai"], ["sprj_foot_shitai"]),
    "carpet": (["sprj_foot_carpet"], ["sprj_foot_carpet"]),
}

# HitMtrlParam row (the floor material the game reports) -> family, by the rows' names.
MATERIALS = {
    1: "stone", 2: "stone", 3: "soil", 4: "wood", 5: "grass", 6: "gravel", 7: "snow", 8: "snow",
    9: "stone", 10: "sand", 11: "bone", 12: "sand", 13: "leaves", 14: "stone", 15: "stone",
    16: "stone_dry", 17: "snow", 18: "water", 19: "water", 20: "water", 21: "water", 22: "mud",
    23: "tar", 24: "mud", 25: "tar", 26: "tar", 27: "corpse", 28: "carpet", 30: "wood",
    31: "water", 32: "stone", 33: "water", 34: "water", 35: "water", 36: "water", 37: "water",
    38: "wood", 39: "water", 41: "water",
}


def find_bank(source):
    if source.is_file():
        return source
    banks = sorted(source.glob("dvdroot_ps4/sound/sprj_main_*.fsb")) or sorted(source.glob("sound/sprj_main_*.fsb"))
    if not banks:
        sys.exit(f"no sprj_main_<lang>.fsb under {source}")
    return banks[0]


def stream_names(bank):
    """vgmstream's subsong number (from 1) -> sample name, from the FSB5 name table."""
    data = bank.read_bytes()
    if data[:4] != b"FSB5":
        sys.exit(f"{bank}: not an FSB5 bank")
    version, count, headers = struct.unpack_from("<3I", data, 4)
    table = (0x40 if version == 0 else 0x3C) + headers
    names = {}
    for i in range(count):
        start = table + struct.unpack_from("<I", data, table + 4 * i)[0]
        name = data[start:data.index(b"\0", start)].decode("ascii", "replace")
        if "foot" in name:
            names[i + 1] = name
    return names


def decode(bank, index, out):
    subprocess.run(["vgmstream-cli", "-s", str(index), "-o", str(out), str(bank)], capture_output=True, check=True)
    with wave.open(str(out)) as w:
        channels, rate = w.getnchannels(), w.getframerate()
        raw = array("h", w.readframes(w.getnframes()))
    if sys.byteorder == "big":
        raw.byteswap()
    mono = [sum(raw[i:i + channels]) / (channels * 32768.0) for i in range(0, len(raw), channels)]
    return mono, rate


def slots(samples, rate):
    """Loudness (RMS) and brightness (RMS of the first difference over RMS, as Hz) per slot."""
    size = round(rate * SLOT_S)
    out = []
    for start in range(0, len(samples) - size + 1, size):
        seg = samples[start:start + size]
        energy = sum(v * v for v in seg) / size
        if energy <= 0:
            out.append((0.0, FREQ_LO))
            continue
        slope = sum((seg[i] - seg[i - 1]) ** 2 for i in range(1, size)) / (size - 1)
        out.append((math.sqrt(energy), rate / (2 * math.pi) * math.sqrt(slope / energy)))
    return out


def trim(env):
    """From the onset to where it stays below GATE of its peak (the reverb tail) for three slots; at most MAX_SLOTS."""
    gate = max(level for level, _ in env) * GATE
    start = next(i for i, (level, _) in enumerate(env) if level >= gate)
    out, quiet = [], 0
    for level, bright in env[start:start + MAX_SLOTS]:
        quiet = quiet + 1 if level < gate else 0
        if quiet == 3:
            break
        out.append((level, bright))
    while out[-1][0] < gate:
        out.pop()
    return out


def encode(env, reference):
    pairs = []
    for level, bright in env:
        hz = min(max(bright, FREQ_LO), FREQ_HI)
        index = round(INDEX_LO + (INDEX_HI - INDEX_LO) * math.log(hz / FREQ_LO) / math.log(FREQ_HI / FREQ_LO))
        # The Kishi shakes less above index 10 (about a third at 35): make some of it up.
        gain = 1 + max(0, index - 10) / 40
        amp = min(63, round(63 * min(1.0, level / reference) ** 0.8 * gain))
        pairs.append(f"{amp}:{index if amp else 0}")
    return " ".join(pairs)


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    source = Path(sys.argv[1])
    bank = find_bank(source)
    output = Path(sys.argv[2]) if len(sys.argv) == 3 else (source if source.is_dir() else bank.parents[2]) / "haptics" / "footsteps.txt"
    names = stream_names(bank)
    tracks = {}  # (family, gait) -> [envelope]
    with tempfile.TemporaryDirectory() as tmp:
        decoded = {}
        for index, name in names.items():
            for family, gaits in FAMILIES.items():
                for gait, prefixes in zip(("walk", "run"), gaits):
                    if any(re.fullmatch(re.escape(p) + r"\d+", name) for p in prefixes):
                        if name not in decoded:
                            decoded[name] = trim(slots(*decode(bank, index, Path(tmp) / f"{index}.wav")))
                        tracks.setdefault((family, gait), []).append(decoded[name])
    missing = [f for f in FAMILIES if (f, "walk") not in tracks]
    if missing:
        sys.exit(f"{bank.name}: no footstep samples for {', '.join(missing)}")
    peaks = sorted(max(level for level, _ in env) for env in decoded.values())
    reference = peaks[int(len(peaks) * 0.9)]
    output.parent.mkdir(parents=True, exist_ok=True)
    with open(output, "w") as out:
        out.write(f"# Kishi footstep haptics from {bank.name}: amplitude:frequency index per 7.5 ms slot\n")
        for material, family in sorted(MATERIALS.items()):
            out.write(f"m {material} {family}\n")
        for (family, gait), envs in sorted(tracks.items()):
            for env in envs:
                out.write(f"t {family} {gait} {encode(env, reference)}\n")
    print(f"{output}: {sum(map(len, tracks.values()))} tracks for {len(FAMILIES)} surfaces")


if __name__ == "__main__":
    main()
