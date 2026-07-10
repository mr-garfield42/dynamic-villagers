"""Generates the mod's shipped structure templates as gzipped structure NBT.

Templates a builder villager constructs are ordinary vanilla structure files
(data/dynamicvillagers/structure/*.nbt). Real content should be authored in-game with
structure blocks; this script exists for the deliberately simple starter/test templates
so they live in the repo as reviewable code instead of opaque binaries only.

Usage: python tools/gen_structures.py
Writes into src/main/resources/data/dynamicvillagers/structure/.
"""
import gzip
import struct
from pathlib import Path

DATA_VERSION = 3955  # 1.21.1
OUT_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/data/dynamicvillagers/structure"


# --- minimal big-endian NBT writer -------------------------------------------------------

def _str(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def _payload(value):
    """Payload bytes + tag id for int, str, list, dict."""
    if isinstance(value, int):
        return struct.pack(">i", value), 3  # TAG_Int
    if isinstance(value, str):
        return _str(value), 8  # TAG_String
    if isinstance(value, list):
        if not value:
            return b"\x00" + struct.pack(">i", 0), 9  # empty list of TAG_End
        payloads, ids = zip(*(_payload(v) for v in value))
        assert len(set(ids)) == 1, "NBT lists are homogeneous"
        return bytes([ids[0]]) + struct.pack(">i", len(value)) + b"".join(payloads), 9
    if isinstance(value, dict):
        out = b""
        for name, v in value.items():
            payload, tag_id = _payload(v)
            out += bytes([tag_id]) + _str(name) + payload
        return out + b"\x00", 10  # TAG_Compound
    raise TypeError(type(value))


def write_nbt(path, root):
    payload, tag_id = _payload(root)
    assert tag_id == 10
    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(path, "wb") as f:
        f.write(b"\x0a" + _str("") + payload)  # unnamed root compound


# --- template definitions -----------------------------------------------------------------

def structure(size, palette, blocks):
    return {
        "size": list(size),
        "entities": [],
        "palette": [
            {"Name": name, **({"Properties": props} if props else {})}
            for name, props in palette
        ],
        "blocks": [{"pos": list(pos), "state": state} for pos, state in blocks],
        "DataVersion": DATA_VERSION,
    }


def starter_shelter():
    """5x4x5 oak shelter: plank floor, plank walls with log corners, slab roof, a doorway
    gap on the south face (+z), one torch inside. Deliberately no multi-part blocks so the
    4.1 builder MVP can finish it completely."""
    PLANKS, LOG, SLAB, TORCH, AIR = range(5)
    palette = [
        ("minecraft:oak_planks", None),
        ("minecraft:oak_log", {"axis": "y"}),
        ("minecraft:oak_slab", {"type": "bottom", "waterlogged": "false"}),
        ("minecraft:torch", None),
        ("minecraft:air", None),
    ]
    blocks = []
    for y in range(4):
        for z in range(5):
            for x in range(5):
                if y == 0:
                    state = PLANKS  # floor
                elif y == 3:
                    state = SLAB  # roof
                elif x in (0, 4) and z in (0, 4):
                    state = LOG  # corner posts
                elif x in (0, 4) or z in (0, 4):
                    state = AIR if (x, z) == (2, 4) else PLANKS  # walls, doorway gap south
                elif (x, y, z) == (1, 1, 1):
                    state = TORCH
                else:
                    state = AIR  # interior must be clear
                blocks.append(((x, y, z), state))
    return structure((5, 4, 5), palette, blocks)


def empty11x11():
    """Gametest arena big enough for a builder to walk around a 5x5 footprint (same
    convention as empty5x5: stone floor at y0, air above)."""
    STONE, AIR = 0, 1
    palette = [("minecraft:stone", None), ("minecraft:air", None)]
    blocks = [(((x, y, z)), STONE if y == 0 else AIR)
              for y in range(8) for z in range(11) for x in range(11)]
    return structure((11, 8, 11), palette, blocks)


TEMPLATES = {
    "starter_shelter": starter_shelter,
    "empty11x11": empty11x11,
}

if __name__ == "__main__":
    for name, build in TEMPLATES.items():
        path = OUT_DIR / f"{name}.nbt"
        write_nbt(path, build())
        print(f"wrote {path}")
