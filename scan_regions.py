#!/usr/bin/env python3
"""
scan_regions.py - find structures that leave no structure data behind.

Roguelike Dungeons, Lootgames and space dungeons use custom worldgen: they write
no MapGenStructureData, so find_structures.py cannot see them, and their
placement depends on terrain height, so the seed sweep cannot predict them. The
one thing they do leave is blocks.

This reads the save's region files directly and looks for their block
signatures. Offline and read-only - the game must not be running (region files
are memory-mapped while it is).

Only covers chunks that have already generated. That is the inherent trade:
complete for explored terrain, blind beyond it.

    python scan_regions.py                    # overworld
    python scan_regions.py --dim -1           # nether
    python scan_regions.py --list-blocks lootgames

No dependencies. Python 3.8+.
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import math
import os
import re
import struct
import sys
import zlib
from collections import Counter, defaultdict

from find_structures import DEFAULT_SAVES, discover_worlds, read_nbt

# --------------------------------------------------------------------------
# Signatures
#
# Each entry: block names to look for, how many hits must cluster together, and
# how far apart (in blocks) hits may be and still count as one structure.
# Names are resolved to numeric ids via the save's own FML registry, so these
# survive id shuffles between pack versions.
# --------------------------------------------------------------------------

SIGNATURES = [
    {
        "label": "Lootgames dungeon",
        "blocks": ["lootgames:ms_master", "lootgames:sdk_master", "lootgames:gol_master",
                   "lootgames:LootGamesMasterBlock", "lootgames:GOLMasterBlock"],
        "min_cluster": 1,          # one master block == one dungeon
        "radius": 32,
    },
    {
        # Redundant with the master block above - every room reports the same
        # fixed wall count at the same spot - so it is off unless asked for.
        # Useful only to confirm a room whose master block was mined out.
        "label": "Lootgames room (walls)",
        "blocks": ["lootgames:LootGamesDungeonWall", "lootgames:LootGamesDungeonLight"],
        "min_cluster": 24,
        "radius": 24,
        "optional": True,
    },
    {
        "label": "Roguelike dungeon",
        # No registered blocks of its own, so it is found by spawner density: a
        # vanilla dungeon or mineshaft has one spawner, a roguelike stacks many
        # across its levels.
        #
        # Two constraints keep this honest, both learned from false positives:
        #   dims  - roguelike's own config is dimensionWL=0, Overworld only.
        #           Without this, Nether fortress blaze spawners and BoP wasp
        #           nests were being reported as dungeons.
        #   mobs  - only vanilla spawners count. BoP wasp nests are dense
        #           clusters of BiomesOPlenty.Wasp and would otherwise qualify.
        "spawners": True,
        "vanilla_mobs_only": True,
        "dims": [0],
        "min_cluster": 12,
        "radius": 24,
        "confidence": [(40, "certain"), (20, "likely"), (0, "possible")],
        "by_biome": True,
    },
    {
        # Galacticraft dungeons contain Evolved-mob spawners and nothing else
        # does. This catches them where the brick signature cannot: the dungeon
        # walls are metadata variants of moonBlock, and metadata is not indexed.
        "label": "Space dungeon",
        "spawners": True,
        "mob_match": "galacticraft",
        "exclude_dims": [0],
        "min_cluster": 2,
        "radius": 64,
    },
    {
        # Everything else spawner-dense. Named by what actually spawns there
        # rather than guessed at, so a Blaze cluster reads as a fortress and a
        # Wasp cluster as a BoP nest instead of both being called "dungeon".
        "label": "Spawner cluster",
        "spawners": True,
        "min_cluster": 4,
        "radius": 24,
        "by_biome": True,
        "name_by_mob": True,
        "generic": True,
    },
    {
        # Galacticraft's MapGenDungeon, not a MapGenStructure - so /survey cannot
        # predict it and this is the only way to locate one.
        "label": "Moon dungeon",
        "blocks": ["GalacticraftCore:tile.moonBricksStairs"],
        "min_cluster": 8,          # these bricks are craftable, so require a mass
        "radius": 64,
    },
    {
        "label": "Space dungeon (Mars)",
        "blocks": ["GalacticraftMars:tile.marsDungeonBricksStairs"],
        "min_cluster": 8,
        "radius": 64,
    },
    {
        "label": "HEE dungeon",
        "blocks": ["HardcoreEnderExpansion:dungeon_puzzle"],
        "min_cluster": 1,
        "radius": 64,
    },
]


# --------------------------------------------------------------------------
# Block registry
# --------------------------------------------------------------------------


def load_block_registry(world: str) -> dict:
    """name -> numeric id, for blocks only, from level.dat's FML registry."""
    root = read_nbt(os.path.join(world, "level.dat"))
    entries = root.get("FML", {}).get("ItemData", [])
    blocks = {}
    for e in entries:
        k = e.get("K")
        if not isinstance(k, str) or not k:
            continue
        # FML prefixes each name with \x01 for blocks and \x02 for items.
        if k[0] == "\x01":
            blocks[k[1:]] = e["V"]
    return blocks


# --------------------------------------------------------------------------
# Region file reading (Anvil, 1.7.10)
# --------------------------------------------------------------------------

SECTOR = 4096


def iter_region_chunks(path: str):
    """Yield (chunk_x, chunk_z, level_compound) for every stored chunk."""
    with open(path, "rb") as fh:
        header = fh.read(SECTOR)
        if len(header) < SECTOR:
            return
        for i in range(1024):
            off = struct.unpack(">I", b"\x00" + header[i * 4:i * 4 + 3])[0]
            count = header[i * 4 + 3]
            if off == 0 or count == 0:
                continue
            fh.seek(off * SECTOR)
            head = fh.read(5)
            if len(head) < 5:
                continue
            (length,) = struct.unpack(">I", head[:4])
            scheme = head[4]
            payload = fh.read(max(0, length - 1))
            try:
                if scheme == 1:
                    import gzip
                    raw = gzip.decompress(payload)
                elif scheme == 2:
                    raw = zlib.decompress(payload)
                else:
                    continue
            except Exception:
                continue
            try:
                root = _nbt_from_bytes(raw)
            except Exception:
                continue
            level = root.get("Level")
            if isinstance(level, dict):
                yield level.get("xPos"), level.get("zPos"), level


def _nbt_from_bytes(raw: bytes):
    """Parse an uncompressed NBT payload (reuses find_structures' reader)."""
    from find_structures import _Reader, TAG_COMPOUND
    r = _Reader(raw)
    tag = r._take(1)[0]
    if tag != TAG_COMPOUND:
        raise ValueError("chunk root is not a compound")
    r.string()
    return r.payload(tag)


def section_hits(section: dict, wanted_by_low: dict):
    """
    Find block positions in one 16x16x16 section matching any wanted id.

    Block ids above 255 are split: the low byte lives in Blocks, the high nibble
    in the optional Add array. Scanning for the low byte first with bytes.find
    keeps the hot loop in C and only checks nibbles on the few candidates.
    """
    blocks = section.get("Blocks")
    if not blocks:
        return
    buf = bytes(bytearray(b & 0xFF for b in blocks)) if isinstance(blocks, list) else blocks
    add = section.get("Add")

    for low, wanted in wanted_by_low.items():
        start = 0
        while True:
            idx = buf.find(bytes([low]), start)
            if idx < 0:
                break
            start = idx + 1
            if add:
                nib = add[idx >> 1]
                high = (nib & 0x0F) if (idx & 1) == 0 else ((nib >> 4) & 0x0F)
            else:
                high = 0
            full = (high << 8) | low
            name = wanted.get(full)
            if name is not None:
                y = idx >> 8
                z = (idx >> 4) & 15
                x = idx & 15
                yield x, y, z, full, name


# --------------------------------------------------------------------------
# Biomes, for telling Roguelike variants apart
#
# Roguelike Dungeons picks its surface building from the biome: brick house in
# forest, wizard tower in mountains, wooden outpost in swamp, castle tower on
# plains, overgrown temple in jungle, sandstone temple in desert. Knowing which
# one to look for matters, because that building is all you can see from above.
#
# The biome -> variant rules below are inferred from biome family, not read out
# of Roguelike's own settings, so treat an odd label as a hint rather than
# gospel. The coordinates are exact regardless.
# --------------------------------------------------------------------------

VANILLA_BIOMES = {
    0: "Ocean", 1: "Plains", 2: "Desert", 3: "Extreme Hills", 4: "Forest", 5: "Taiga",
    6: "Swampland", 7: "River", 8: "Hell", 9: "Sky", 10: "FrozenOcean",
    11: "FrozenRiver", 12: "Ice Plains", 13: "Ice Mountains", 14: "MushroomIsland",
    15: "MushroomIslandShore", 16: "Beach", 17: "DesertHills", 18: "ForestHills",
    19: "TaigaHills", 20: "Extreme Hills Edge", 21: "Jungle", 22: "JungleHills",
    23: "JungleEdge", 24: "Deep Ocean", 25: "Stone Beach", 26: "Cold Beach",
    27: "Birch Forest", 28: "Birch Forest Hills", 29: "Roofed Forest",
    30: "Cold Taiga", 31: "Cold Taiga Hills", 32: "Mega Taiga", 33: "Mega Taiga Hills",
    34: "Extreme Hills+", 35: "Savanna", 36: "Savanna Plateau", 37: "Mesa",
    38: "Mesa Plateau F", 39: "Mesa Plateau",
}

# Ordered: first matching keyword wins, so specific families come before broad.
VARIANT_RULES = [
    (("desert", "sand", "mesa", "dune", "oasis", "wasteland"), "Sandstone Temple"),
    (("jungle", "rainforest", "tropic", "bamboo"), "Overgrown Temple"),
    (("swamp", "bayou", "bog", "sludge", "marsh", "quagmire", "fen", "wetland"),
     "Wooden Outpost"),
    (("mountain", "alps", "crag", "extreme hills", "highland", "peak", "cliff"),
     "Wizard Tower"),
    (("forest", "taiga", "wood", "grove", "thicket", "boreal", "conifer", "shrub",
      "brushland", "orchard"), "Brick House"),
    (("plain", "meadow", "prairie", "field", "steppe", "grass", "savanna", "heath",
      "moor", "scrub", "shield", "chaparral"), "Castle Tower"),
]


def load_biome_names(world: str) -> dict:
    """biome id -> name, from vanilla ids plus the pack's BoP id config."""
    names = dict(VANILLA_BIOMES)
    # <instance>/.minecraft/saves/<world>  ->  <instance>/.minecraft/config
    cfg = os.path.join(world, os.pardir, os.pardir, "config", "biomesoplenty", "ids.cfg")
    try:
        with open(cfg, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = re.match(r'\s*I:"?(.+?)\s*ID"?\s*=\s*(\d+)', line)
                if m:
                    names[int(m.group(2))] = m.group(1).strip('"')
    except OSError:
        pass                     # BoP absent or moved; vanilla names still apply
    return names


def biome_at(level: dict, local_x: int, local_z: int):
    """
    Biome id for a column in a chunk.

    EndlessIDs replaces vanilla's byte Biomes array with Biomes16v2: 256 cells of
    two bytes, low byte first, so ids can exceed 255.
    """
    arr = level.get("Biomes16v2")
    if arr and len(arr) >= 512:
        i = 2 * (local_z * 16 + local_x)
        return (arr[i] & 0xFF) | ((arr[i + 1] & 0xFF) << 8)
    arr = level.get("Biomes")
    if arr and len(arr) >= 256:
        return arr[local_z * 16 + local_x] & 0xFF
    return None


def variant_for(biome_name: str) -> str:
    low = (biome_name or "").lower()
    for keywords, variant in VARIANT_RULES:
        if any(k in low for k in keywords):
            return variant
    return ""


def spawner_hits(level):
    """Yield (x, y, z, mob) for each mob spawner recorded in this chunk."""
    for te in level.get("TileEntities", []):
        if not isinstance(te, dict) or te.get("id") != "MobSpawner":
            continue
        mob = te.get("EntityId") or te.get("EntityID") or "?"
        try:
            yield int(te["x"]), int(te["y"]), int(te["z"]), str(mob)
        except (KeyError, TypeError, ValueError):
            continue


def confidence_of(sig, count):
    """Label how sure we are, for signatures where hit count carries meaning."""
    table = sig.get("confidence")
    if not table:
        return ""
    for threshold, label in table:
        if count >= threshold:
            return label
    return ""


def cluster(points, radius, min_size):
    """Greedy spatial grouping in XZ. Returns [(count, cx, cy, cz)]."""
    remaining = list(points)
    out = []
    r2 = radius * radius
    while remaining:
        seed = remaining.pop()
        group = [seed]
        changed = True
        while changed:
            changed = False
            keep = []
            for p in remaining:
                if any((p[0] - q[0]) ** 2 + (p[2] - q[2]) ** 2 <= r2 for q in group):
                    group.append(p)
                    changed = True
                else:
                    keep.append(p)
            remaining = keep
        if len(group) >= min_size:
            out.append((len(group),
                        sum(p[0] for p in group) // len(group),
                        sum(p[1] for p in group) // len(group),
                        sum(p[2] for p in group) // len(group),
                        group))
    return out


# --------------------------------------------------------------------------


def region_dir(world: str, dim: int) -> str:
    return os.path.join(world, "region") if dim == 0 \
        else os.path.join(world, "DIM%d" % dim, "region")


def main(argv=None):
    p = argparse.ArgumentParser(description="Find custom-worldgen structures in region files.",
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("world", nargs="?", help="world folder (default: most recent)")
    p.add_argument("--saves", help="saves folder to search")
    p.add_argument("--dim", type=int, default=0, help="dimension id (default 0)")
    p.add_argument("--list-blocks", metavar="TEXT",
                   help="list registry blocks matching TEXT and exit")
    p.add_argument("--only", metavar="TEXT", help="only signatures whose label contains TEXT")
    p.add_argument("--json", metavar="FILE")
    p.add_argument("--csv", metavar="FILE")
    args = p.parse_args(argv)

    if args.world:
        world = args.world
    else:
        worlds = discover_worlds(args.saves or DEFAULT_SAVES)
        if not worlds:
            raise SystemExit("no worlds found; pass a world folder")
        world = max(worlds, key=lambda w: os.path.getmtime(os.path.join(w, "level.dat")))

    registry = load_block_registry(world)
    biome_names = load_biome_names(world)

    if args.list_blocks:
        needle = args.list_blocks.lower()
        for name, bid in sorted(registry.items()):
            if needle in name.lower():
                print("  %-56s id=%d" % (name, bid))
        return 0

    if args.only:
        sigs = [s for s in SIGNATURES if args.only.lower() in s["label"].lower()]
    else:
        sigs = [s for s in SIGNATURES if not s.get("optional")]

    # Resolve names to ids, grouped by low byte so one pass finds everything.
    wanted_by_low = defaultdict(dict)
    resolved = {}
    missing = []
    for sig in sigs:
        ids = []
        for name in sig.get("blocks", []):
            bid = registry.get(name)
            if bid is None:
                missing.append(name)
                continue
            ids.append(bid)
            wanted_by_low[bid & 0xFF][bid] = name
        resolved[sig["label"]] = ids

    rdir = region_dir(world, args.dim)
    files = sorted(glob.glob(os.path.join(rdir, "r.*.mca")))
    print()
    print("  world  : %s  (DIM%d)" % (os.path.basename(world.rstrip("\\/")), args.dim))
    print("  regions: %d" % len(files))
    if missing:
        print("  note   : not in this pack, skipped: %s" % ", ".join(sorted(set(missing))))
    if not files:
        print("\n  No region files - this dimension has never been generated.\n")
        return 1
    if not wanted_by_low:
        print("\n  No signature blocks resolved; nothing to look for.\n")
        return 1

    by_block = defaultdict(list)
    spawners = []                       # (x, y, z, biome, mob)
    need_spawners = any(sig.get("spawners") for sig in sigs)
    chunks_scanned = 0
    for n, path in enumerate(files, 1):
        for cx, cz, level in iter_region_chunks(path):
            if cx is None:
                continue
            chunks_scanned += 1
            if need_spawners:
                for sx_, sy_, sz_, mob in spawner_hits(level):
                    spawners.append((sx_, sy_, sz_,
                                     biome_at(level, sx_ & 15, sz_ & 15), mob))
            for section in level.get("Sections", []):
                if not isinstance(section, dict):
                    continue
                sy = section.get("Y", 0) * 16
                for x, y, z, full, name in section_hits(section, wanted_by_low):
                    by_block[name].append((cx * 16 + x, sy + y, cz * 16 + z,
                                           biome_at(level, x, z), None))
        sys.stdout.write("\r  scanning: %d/%d regions, %d chunks" % (n, len(files), chunks_scanned))
        sys.stdout.flush()
    print("\n")

    findings = []
    for sig in sigs:
        if sig.get("dims") and args.dim not in sig["dims"]:
            continue
        if args.dim in sig.get("exclude_dims", []):
            continue
        if sig.get("spawners"):
            pts = spawners
            if sig.get("vanilla_mobs_only"):
                # Modded ids are namespaced ("BiomesOPlenty.Wasp"); vanilla are not.
                pts = [p for p in pts if p[4] and "." not in p[4] and ":" not in p[4]]
            if sig.get("mob_match"):
                needle = sig["mob_match"].lower()
                pts = [p for p in pts if p[4] and needle in p[4].lower()]
        else:
            pts = []
            for name in sig["blocks"]:
                pts.extend(by_block.get(name, []))
        if not pts:
            continue
        for count, x, y, z, group in cluster(pts, sig["radius"], sig["min_cluster"]):
            # Most common biome in the cluster, so one stray column at an edge
            # cannot mislabel the whole thing.
            ids = [p[3] for p in group if len(p) > 3 and p[3] is not None]
            biome_id = Counter(ids).most_common(1)[0][0] if ids else None
            if biome_id is None:
                biome = ""
            else:
                # Unknown ids come from mods with no id config we can read;
                # show the number rather than an empty cell.
                biome = biome_names.get(biome_id) or ("biome id=%d" % biome_id)
            label = sig["label"]
            mobs = [p[4] for p in group if len(p) > 4 and p[4]]
            top_mob = Counter(mobs).most_common(1)[0] if mobs else None
            if sig.get("name_by_mob") and top_mob:
                label = "%s (%s x%d)" % (label, top_mob[0], top_mob[1])
            findings.append({"label": label, "generic": bool(sig.get("generic")),
                             "blocks": count,
                             "confidence": confidence_of(sig, count),
                             "biome": biome,
                             "mob": top_mob[0] if top_mob else "",
                             "variant": variant_for(biome) if sig.get("by_biome") else "",
                             "x": x, "y": y, "z": z})

    if not findings:
        print("  Nothing found in %d generated chunks.\n" % chunks_scanned)
        return 0

    # A roguelike dungeon is also a big spawner cluster; reporting both at the
    # same coordinates is just noise. Specific findings win.
    specific = [f for f in findings if not f.get("generic")]
    findings = [f for f in findings
                if not f.get("generic")
                or not any(abs(f["x"] - g["x"]) <= 48 and abs(f["z"] - g["z"]) <= 48
                           for g in specific)]
    for f in findings:
        f.pop("generic", None)

    findings.sort(key=lambda f: (f["label"], -f["blocks"]))
    width = max(len(f["label"]) for f in findings) + 2
    print("  Found %d structures in %d generated chunks:\n" % (len(findings), chunks_scanned))
    print("  %-*s %8s   %-26s %-10s %s"
          % (width, "type", "blocks", "location", "confidence", "look for / biome"))
    print("  " + "-" * (width + 70))
    for f in findings:
        loc = "X %-7d Y %-4d Z %-7d" % (f["x"], f["y"], f["z"])
        extra = f["variant"] or ""
        if f["biome"]:
            extra = ("%s  (%s)" % (extra, f["biome"])) if extra else f["biome"]
        print("  %-*s %8d   %-26s %-10s %s"
              % (width, f["label"], f["blocks"], loc, f["confidence"], extra))
    print()

    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"world": world, "dimension": args.dim,
                       "chunks_scanned": chunks_scanned, "findings": findings}, fh, indent=2)
        print("  wrote %s" % args.json)
    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            w.writerow(["type", "blocks", "x", "y", "z"])
            for f in findings:
                w.writerow([f["label"], f["blocks"], f["x"], f["y"], f["z"]])
        print("  wrote %s" % args.csv)
    return 0


if __name__ == "__main__":
    sys.exit(main())
