#!/usr/bin/env python3
"""
find_structures.py - offline structure index for a Minecraft 1.7.10 / GTNH save.

Reads the MapGenStructureData files a world writes to disk (Village.dat,
Mineshaft.dat, Stronghold.dat, and any modded equivalent) and reports every
structure that has already been generated, with exact coordinates.

Nothing is launched and nothing is written to the save - this is a read-only
pass over .dat files. It only knows about chunks that already exist; unexplored
terrain is invisible to it by construction.

Usage:
    python find_structures.py                     # nearest structures to the player
    python find_structures.py --from spawn        # ...measured from world spawn
    python find_structures.py --type village      # ...only villages
    python find_structures.py --details           # piece breakdown + bounding boxes
    python find_structures.py --json out.json     # machine-readable dump

No dependencies. Python 3.8+.
"""

from __future__ import annotations

import argparse
import csv
import glob
import gzip
import json
import math
import os
import re
import struct
import sys
import zlib
from collections import Counter

# --------------------------------------------------------------------------
# NBT reader (1.7.10 - no TAG_Long_Array)
# --------------------------------------------------------------------------

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG = 0, 1, 2, 3, 4
TAG_FLOAT, TAG_DOUBLE, TAG_BYTE_ARRAY, TAG_STRING = 5, 6, 7, 8
TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY = 9, 10, 11


class NBTError(Exception):
    pass


class _Reader:
    __slots__ = ("b", "i")

    def __init__(self, buf: bytes):
        self.b = buf
        self.i = 0

    def _take(self, n: int) -> bytes:
        end = self.i + n
        if end > len(self.b):
            raise NBTError("truncated at offset %d" % self.i)
        out = self.b[self.i:end]
        self.i = end
        return out

    def string(self) -> str:
        (n,) = struct.unpack(">H", self._take(2))
        return self._take(n).decode("utf-8", "replace")

    def payload(self, tag: int):
        if tag == TAG_BYTE:
            return struct.unpack(">b", self._take(1))[0]
        if tag == TAG_SHORT:
            return struct.unpack(">h", self._take(2))[0]
        if tag == TAG_INT:
            return struct.unpack(">i", self._take(4))[0]
        if tag == TAG_LONG:
            return struct.unpack(">q", self._take(8))[0]
        if tag == TAG_FLOAT:
            return struct.unpack(">f", self._take(4))[0]
        if tag == TAG_DOUBLE:
            return struct.unpack(">d", self._take(8))[0]
        if tag == TAG_BYTE_ARRAY:
            (n,) = struct.unpack(">i", self._take(4))
            return list(self._take(n))
        if tag == TAG_STRING:
            return self.string()
        if tag == TAG_LIST:
            item = self._take(1)[0]
            (n,) = struct.unpack(">i", self._take(4))
            return [self.payload(item) for _ in range(max(0, n))]
        if tag == TAG_COMPOUND:
            out = {}
            while True:
                child = self._take(1)[0]
                if child == TAG_END:
                    return out
                # Read the name BEFORE the value: Python evaluates the RHS of
                # `out[key] = value` first, which would desync the stream.
                name = self.string()
                out[name] = self.payload(child)
        if tag == TAG_INT_ARRAY:
            (n,) = struct.unpack(">i", self._take(4))
            return list(struct.unpack(">%di" % n, self._take(4 * n)))
        raise NBTError("unknown tag %d at offset %d" % (tag, self.i))


def read_nbt(path: str):
    """Load a (possibly compressed) NBT file and return its root payload."""
    with open(path, "rb") as fh:
        raw = fh.read()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    elif raw[:1] == b"\x78":
        raw = zlib.decompress(raw)
    r = _Reader(raw)
    tag = r._take(1)[0]
    if tag != TAG_COMPOUND:
        raise NBTError("root tag is %d, expected compound" % tag)
    r.string()
    return r.payload(tag)


# --------------------------------------------------------------------------
# Labels
# --------------------------------------------------------------------------

# Structure-start ids -> display name. Unknown ids fall through unchanged, so
# modded structures still show up (just under their raw registry name).
STRUCTURE_NAMES = {
    "Village": "Village",
    "MapGenVillageVN": "Village",       # VillageNames replaces the vanilla id
    "Mineshaft": "Mineshaft",
    "Stronghold": "Stronghold",
    "Temple": "Scattered feature",
    "Fortress": "Nether fortress",
}

# Piece ids worth calling out. Deliberately conservative: only ids whose meaning
# is unambiguous. Everything else stays raw and shows up under --details.
NOTABLE_PIECES = {
    # Stronghold
    "SHPR": "End portal",
    "SHLi": "Library",
    "SHCC": "Chest corridor",
    "SHPH": "Prison",
    # Modded village buildings that are actually worth travelling for
    "TConstruct:SmelteryStructure": "Smeltery",
    "TConstruct:ToolWorkshopStructure": "Tinker workshop",
    "Forestry:BeeHouse": "Bee house",
    "railcraft:workshop": "Railcraft workshop",
    "witchery:Apothecary": "Apothecary",
    "witchery:bookshop": "Bookshop",
    "witchery:witchhut": "Witch hut",
    "witchery:villagewatchtower": "Watchtower",
    "TCVillageBanker": "Thaumcraft banker",
    "TCVillageTower": "Thaumcraft tower",
    "WGVillagePhotoWorkshop": "Photo workshop",
}

DIMENSION_NAMES = {0: "Overworld", -1: "Nether", 1: "The End"}


def dimension_label(dim_id: int) -> str:
    return DIMENSION_NAMES.get(dim_id, "DIM%d" % dim_id)


# --------------------------------------------------------------------------
# Model
# --------------------------------------------------------------------------


class Structure:
    __slots__ = ("kind", "raw_kind", "dim", "chunk_x", "chunk_z", "bb",
                 "pieces", "valid", "name", "source")

    def __init__(self, raw_kind, dim, chunk_x, chunk_z, bb, pieces, valid, source):
        self.raw_kind = raw_kind
        self.kind = STRUCTURE_NAMES.get(raw_kind, raw_kind)
        self.dim = dim
        self.chunk_x = chunk_x
        self.chunk_z = chunk_z
        self.bb = bb                # [minX, minY, minZ, maxX, maxY, maxZ] or None
        self.pieces = pieces        # Counter of piece id -> count
        self.valid = valid
        self.name = None            # filled in by enrichment, e.g. village name
        self.source = source

    @property
    def center(self):
        if self.bb and len(self.bb) == 6:
            return ((self.bb[0] + self.bb[3]) // 2,
                    (self.bb[1] + self.bb[4]) // 2,
                    (self.bb[2] + self.bb[5]) // 2)
        return (self.chunk_x * 16 + 8, None, self.chunk_z * 16 + 8)

    @property
    def size(self):
        if self.bb and len(self.bb) == 6:
            return (self.bb[3] - self.bb[0] + 1,
                    self.bb[4] - self.bb[1] + 1,
                    self.bb[5] - self.bb[2] + 1)
        return None

    def contains_xz(self, x, z):
        if not self.bb or len(self.bb) != 6:
            return False
        return self.bb[0] <= x <= self.bb[3] and self.bb[2] <= z <= self.bb[5]

    def notable(self):
        out = []
        for pid, label in NOTABLE_PIECES.items():
            n = self.pieces.get(pid, 0)
            if n:
                out.append(label if n == 1 else "%s x%d" % (label, n))
        return out

    def distance_from(self, x, z):
        cx, _, cz = self.center
        return math.hypot(cx - x, cz - z)

    def to_dict(self):
        cx, cy, cz = self.center
        return {
            "kind": self.kind,
            "raw_kind": self.raw_kind,
            "dimension": self.dim,
            "dimension_name": dimension_label(self.dim),
            "name": self.name,
            "center": {"x": cx, "y": cy, "z": cz},
            "chunk": {"x": self.chunk_x, "z": self.chunk_z},
            "bounding_box": self.bb,
            "size": self.size,
            "piece_count": sum(self.pieces.values()),
            "pieces": dict(self.pieces),
            "notable": self.notable(),
            "valid": self.valid,
            "source_file": self.source,
        }


# --------------------------------------------------------------------------
# Scanning
# --------------------------------------------------------------------------


def looks_like_structure_data(root) -> bool:
    try:
        feats = root["data"]["Features"]
    except (KeyError, TypeError):
        return False
    if not isinstance(feats, dict):
        return False
    for v in feats.values():
        return isinstance(v, dict) and "ChunkX" in v and "ChunkZ" in v
    return False        # empty Features - nothing to report


def parse_structure_file(path: str, dim: int, warnings: list) -> list:
    try:
        root = read_nbt(path)
    except Exception as exc:                              # noqa: BLE001
        warnings.append("could not read %s (%s)" % (os.path.basename(path), exc))
        return []
    if not looks_like_structure_data(root):
        return []

    out = []
    for start in root["data"]["Features"].values():
        pieces = Counter()
        for child in start.get("Children", []):
            if isinstance(child, dict):
                pieces[str(child.get("id", "?"))] += 1
        bb = start.get("BB")
        if not (isinstance(bb, list) and len(bb) == 6):
            bb = None
        out.append(Structure(
            raw_kind=str(start.get("id", "?")),
            dim=dim,
            chunk_x=int(start.get("ChunkX", 0)),
            chunk_z=int(start.get("ChunkZ", 0)),
            bb=bb,
            pieces=pieces,
            valid=bool(start.get("Valid", 1)),
            source=os.path.basename(path),
        ))
    return out


def dimension_dirs(world: str):
    """Yield (dimension_id, data_directory) for every dimension in the save."""
    root_data = os.path.join(world, "data")
    if os.path.isdir(root_data):
        yield 0, root_data
    for entry in sorted(glob.glob(os.path.join(world, "DIM*"))):
        if not os.path.isdir(entry):
            continue
        m = re.match(r"DIM(-?\d+)$", os.path.basename(entry))
        if not m:
            continue
        sub = os.path.join(entry, "data")
        if os.path.isdir(sub):
            yield int(m.group(1)), sub


def scan_world(world: str, warnings: list) -> list:
    found = []
    for dim, data_dir in dimension_dirs(world):
        for path in sorted(glob.glob(os.path.join(data_dir, "*.dat"))):
            found.extend(parse_structure_file(path, dim, warnings))
    return found


def enrich_village_names(world: str, structures: list, warnings: list) -> None:
    """Attach VillageNames town names to the villages they sit inside."""
    path = os.path.join(world, "data", "villagenames3_Village.dat")
    if not os.path.isfile(path):
        return
    try:
        root = read_nbt(path)
        named = root["data"]["NamedStructures"]
    except Exception as exc:                              # noqa: BLE001
        warnings.append("village names unavailable (%s)" % exc)
        return

    villages = [s for s in structures if s.kind == "Village"]
    for key in named:
        m = re.search(r"x(-?\d+)\s+y(-?\d+)\s+z(-?\d+)", key)
        if not m:
            continue
        x, _, z = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
        label = key.split(",")[0].strip()
        hit = next((v for v in villages if v.contains_xz(x, z)), None)
        if hit is None and villages:
            hit = min(villages, key=lambda v: v.distance_from(x, z))
            if hit.distance_from(x, z) > 256:
                hit = None
        if hit is not None and not hit.name:
            hit.name = label


# --------------------------------------------------------------------------
# Reference point
# --------------------------------------------------------------------------


def world_info(world: str, warnings: list) -> dict:
    info = {"name": os.path.basename(world.rstrip("\\/")), "spawn": None,
            "player": None, "seed": None, "generator": None}
    try:
        data = read_nbt(os.path.join(world, "level.dat"))["Data"]
    except Exception as exc:                              # noqa: BLE001
        warnings.append("level.dat unreadable (%s)" % exc)
        return info
    info["name"] = data.get("LevelName", info["name"])
    info["seed"] = data.get("RandomSeed")
    info["generator"] = data.get("generatorName")
    if "SpawnX" in data:
        info["spawn"] = (data["SpawnX"], data.get("SpawnY"), data["SpawnZ"])
    pos = (data.get("Player") or {}).get("Pos")
    if isinstance(pos, list) and len(pos) == 3:
        info["player"] = (int(pos[0]), int(pos[1]), int(pos[2]))
        info["player_dim"] = data["Player"].get("Dimension", 0)
    return info


def resolve_origin(spec: str, info: dict):
    """Return ((x, z), description) for the distance reference point."""
    if spec not in ("player", "spawn"):
        m = re.match(r"^(-?\d+)\s*[, ]\s*(-?\d+)$", spec.strip())
        if not m:
            raise SystemExit("--from expects 'player', 'spawn', or 'X,Z' (got %r)" % spec)
        x, z = int(m.group(1)), int(m.group(2))
        return (x, z), "X=%d Z=%d" % (x, z)

    if spec == "player" and info.get("player"):
        x, _, z = info["player"]
        return (x, z), "player at X=%d Z=%d" % (x, z)
    if info.get("spawn"):
        x, _, z = info["spawn"]
        return (x, z), "world spawn at X=%d Z=%d" % (x, z)
    return (0, 0), "origin (no spawn found)"


# --------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------


def compass(dx, dz):
    if dx == 0 and dz == 0:
        return "--"
    angle = math.degrees(math.atan2(dx, -dz)) % 360
    return ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][int((angle + 22.5) % 360 // 45)]


def print_report(structures, info, origin, origin_desc, args):
    ox, oz = origin
    print()
    print("  %s   (%s, seed %s)" % (info["name"], info.get("generator") or "?",
                                    info.get("seed")))
    print("  distances from %s" % origin_desc)
    print()

    if not structures:
        print("  No generated structures found.")
        print("  Explore further, or these structure types don't write .dat files.")
        print()
        return

    by_kind = Counter((s.kind, s.dim) for s in structures)
    width = max(len(k) for k, _ in by_kind) + 2
    print("  Found %d structures" % len(structures))
    print("  " + "-" * (width + 26))
    for (kind, dim), n in sorted(by_kind.items(), key=lambda kv: -kv[1]):
        print("  %-*s %4d   %s" % (width, kind, n, dimension_label(dim)))
    print()

    ranked = sorted(structures, key=lambda s: s.distance_from(ox, oz))
    shown = ranked[:args.top]
    print("  Nearest %d:" % len(shown))
    print()
    for s in shown:
        cx, cy, cz = s.center
        dist = s.distance_from(ox, oz)
        head = s.kind if not s.name else "%s - %s" % (s.kind, s.name)
        coords = "X %-7d Z %-7d" % (cx, cz)
        if cy is not None:
            coords += " Y %-4d" % cy
        print("  %-34s %5.0fm %-2s  %s" % (head, dist, compass(cx - ox, cz - oz), coords))

        extras = []
        if s.dim != 0:
            extras.append(dimension_label(s.dim))
        notable = s.notable()
        if notable:
            extras.append(", ".join(notable))
        if not s.valid:
            extras.append("marked invalid")
        if extras:
            print("  %-34s        %s" % ("", "  |  ".join(extras)))

        if args.details:
            size = s.size
            if size:
                print("  %-34s        span %dx%dx%d  bb %s" %
                      ("", size[0], size[1], size[2], s.bb))
            print("  %-34s        chunk [%d,%d]  %d pieces  (%s)" %
                  ("", s.chunk_x, s.chunk_z, sum(s.pieces.values()), s.source))
            for pid, n in s.pieces.most_common():
                print("  %-34s          %-42s %d" % ("", pid, n))
            print()
    print()


def write_json(path, structures, info, origin):
    payload = {
        "world": info,
        "origin": {"x": origin[0], "z": origin[1]},
        "generated_structures": [s.to_dict() for s in structures],
    }
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2)
    print("  wrote %s (%d structures)" % (path, len(structures)))


def write_csv(path, structures, origin):
    ox, oz = origin
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["kind", "name", "dimension", "x", "y", "z",
                    "distance", "chunk_x", "chunk_z", "pieces", "notable"])
        for s in sorted(structures, key=lambda s: s.distance_from(ox, oz)):
            cx, cy, cz = s.center
            w.writerow([s.kind, s.name or "", dimension_label(s.dim), cx,
                        "" if cy is None else cy, cz,
                        round(s.distance_from(ox, oz)),
                        s.chunk_x, s.chunk_z, sum(s.pieces.values()),
                        "; ".join(s.notable())])
    print("  wrote %s (%d structures)" % (path, len(structures)))


# --------------------------------------------------------------------------
# World discovery
# --------------------------------------------------------------------------

def _default_saves():
    """
    Where to look for worlds, most specific first.

    Set SURVEYOR_MINECRAFT to a .minecraft folder to skip the guessing. The
    fallbacks cover the usual launchers; anything else can be passed on the
    command line.
    """
    explicit = os.environ.get("SURVEYOR_MINECRAFT")
    if explicit:
        return os.path.join(explicit, "saves")
    appdata = os.environ.get("APPDATA", "")
    home = os.path.expanduser("~")
    candidates = []
    for base in (os.path.join(appdata, "PrismLauncher", "instances"),
                 os.path.join(appdata, "MultiMC", "instances"),
                 os.path.join(home, ".local", "share", "PrismLauncher", "instances")):
        if os.path.isdir(base):
            for inst in sorted(os.listdir(base)):
                saves = os.path.join(base, inst, ".minecraft", "saves")
                if os.path.isdir(saves):
                    candidates.append(saves)
    candidates.append(os.path.join(appdata, ".minecraft", "saves"))
    candidates.append(os.path.join(home, ".minecraft", "saves"))
    for c in candidates:
        if os.path.isdir(c):
            return c
    return candidates[0]


DEFAULT_SAVES = _default_saves()


def discover_worlds(saves_dir: str):
    if not os.path.isdir(saves_dir):
        return []
    out = []
    for entry in sorted(os.listdir(saves_dir)):
        path = os.path.join(saves_dir, entry)
        if os.path.isfile(os.path.join(path, "level.dat")):
            out.append(path)
    return out


def pick_world(args):
    if args.world:
        if not os.path.isfile(os.path.join(args.world, "level.dat")):
            raise SystemExit("no level.dat in %s" % args.world)
        return args.world

    worlds = discover_worlds(args.saves or DEFAULT_SAVES)
    if not worlds:
        raise SystemExit(
            "No worlds found in %s\nPass a world folder explicitly:\n"
            "  python find_structures.py \"<path to saves>/<world>\""
            % (args.saves or DEFAULT_SAVES))
    if len(worlds) == 1:
        return worlds[0]

    # Most recently played wins; --list-worlds shows the alternatives.
    return max(worlds, key=lambda p: os.path.getmtime(os.path.join(p, "level.dat")))


# --------------------------------------------------------------------------


def main(argv=None):
    p = argparse.ArgumentParser(
        description="List every already-generated structure in a 1.7.10 world.",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("world", nargs="?", help="world folder (default: most recent)")
    p.add_argument("--saves", help="saves folder to search")
    p.add_argument("--list-worlds", action="store_true", help="list worlds and exit")
    p.add_argument("--from", dest="origin", default="player", metavar="REF",
                   help="measure distance from: player | spawn | X,Z")
    p.add_argument("--type", action="append", metavar="TEXT",
                   help="only structures whose name contains TEXT (repeatable)")
    p.add_argument("--dim", type=int, metavar="ID", help="only this dimension id")
    p.add_argument("--top", type=int, default=20, metavar="N",
                   help="how many to list (default 20, 0 = all)")
    p.add_argument("--details", action="store_true",
                   help="bounding boxes and full piece breakdown")
    p.add_argument("--json", metavar="FILE", help="write JSON")
    p.add_argument("--csv", metavar="FILE", help="write CSV")
    args = p.parse_args(argv)

    if args.list_worlds:
        for w in discover_worlds(args.saves or DEFAULT_SAVES):
            print(" ", w)
        return 0

    world = pick_world(args)
    warnings: list = []

    info = world_info(world, warnings)
    structures = scan_world(world, warnings)
    enrich_village_names(world, structures, warnings)

    if args.dim is not None:
        structures = [s for s in structures if s.dim == args.dim]
    if args.type:
        needles = [t.lower() for t in args.type]
        structures = [s for s in structures
                      if any(n in s.kind.lower() or n in s.raw_kind.lower()
                             for n in needles)]

    origin, origin_desc = resolve_origin(args.origin, info)
    if args.top <= 0:
        args.top = len(structures)

    print_report(structures, info, origin, origin_desc, args)

    if args.json:
        write_json(args.json, structures, info, origin)
    if args.csv:
        write_csv(args.csv, structures, origin)
    if warnings:
        print("  notes:")
        for w in warnings:
            print("    -", w)
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
