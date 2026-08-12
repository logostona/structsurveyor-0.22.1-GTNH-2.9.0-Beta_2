# GTNH structure index (step 1)

Offline, read-only listing of every structure that has **already generated** in a
Minecraft 1.7.10 / GTNH save. Reads the `MapGenStructureData` files the world
writes to disk — `Village.dat`, `Mineshaft.dat`, `Stronghold.dat`, and any modded
equivalent — and reports exact coordinates.

No dependencies, no JDK, no mod loading. The game does not need to be running,
and nothing is written to the save.

## Use

```bash
python find_structures.py
```

Auto-detects the GTNH instance, picks the most recently played world, and lists
the nearest structures to where your player is standing.

| Flag | |
|---|---|
| `--from player \| spawn \| X,Z` | distance reference point (default: player) |
| `--type village` | filter by name, repeatable |
| `--dim 0` | restrict to one dimension |
| `--top N` | how many to list (`0` = all, default 20) |
| `--details` | bounding boxes + full per-piece breakdown |
| `--json FILE` / `--csv FILE` | machine-readable export |
| `--list-worlds` | show detected worlds |
| `world` (positional) | explicit world folder |

## What it reports

Per structure: type, name (villages, via VillageNames), centre X/Y/Z, distance
and compass bearing, chunk coords, and a **notable contents** line — end portal
rooms and libraries in strongholds, Tinker's smelteries, Forestry bee houses,
Railcraft workshops, Witchery apothecaries and Thaumcraft bankers in villages.

Piece labels are deliberately conservative: only unambiguous ids get friendly
names. Everything else shows under `--details` as its raw registry id, so modded
structures still appear rather than being silently dropped.

## Limits

- **Generated chunks only.** This reads what the world already recorded. Terrain
  you have never visited is invisible — that is what step 2 (seed-driven
  placement sweep) is for.
- **Only structures that use `MapGenStructureData`.** Roguelike Dungeons,
  Lootgames and similar use their own worldgen and write no `.dat`, so they will
  not appear here.
- Village `Y` is taken from the structure bounding box, not a surface probe.

---

# Step 2: Structure Surveyor (Forge mod)

`surveyor/` builds a 1.7.10 Forge mod that predicts structures in terrain you
have **never visited**, by replaying each generator's placement decision from
the world seed. No chunks are generated.

Installed as `structsurveyor-0.1.0.jar`. Rebuild with:

```bash
cd surveyor && ./gradlew reobfJar
```

`build/libs/structsurveyor-<version>.jar` is the reobfuscated jar to install;
the `-dev` one is for a development workspace only.

## In-game commands

| Command | |
|---|---|
| `/survey list` | show the structure generators found in this dimension |
| `/survey <radius>` | sweep that radius in blocks around you |
| `/survey <radius> <x> <z>` | sweep around a chosen point |
| `/survey status` / `/survey stop` | check on or cancel a running sweep |
| `/survey rate <n>` | chunks per tick (default 2000; lower it if the game stutters) |

Results are written to `.minecraft/surveyor/survey_dim<N>.json`.

The sweep runs on the server thread in per-tick slices: some placement
predicates do biome lookups that are not thread-safe in 1.7.10, so a worker
thread would race with chunk generation.

## Self-verification (automatic)

Every sweep checks itself. At the end it reads each generator's `structureMap`
— the structures the game itself recorded — and reports how many of them the
replay reproduced:

```
  Village: 444 found     [verified 34/34 known]
  Mineshaft: 6322 found  [UNRELIABLE: only 1/435 known structures reproduced]
  Stronghold: 3 found    [verified 3/3 known]
```

Read the verdict, not the count. A wrong replay still produces the right
*density* and a believable spread, so the raw number tells you nothing about
whether it is correct.

- **verified** — every known structure in the swept region was reproduced. Trust
  the predictions outside explored terrain.
- **UNRELIABLE** — the replay does not match reality. Ignore these positions.
- **unverified** — nothing known in the region to check against. Re-run centred
  on explored terrain to get a real verdict.

The same fields (`verdict`, `known_in_region`, `known_reproduced`) are written
per generator into the JSON.

### Known results on this pack

Run `/survey` in each dimension — discovery is per-dimension, so the Nether and
Twilight Forest are covered by the same command with no extra code.

| Dimension | Generator | Verdict |
|---|---|---|
| Overworld | Village (`MapGenVillageVN`) | verified 34/34 |
| Overworld | Stronghold | verified 3/3 |
| Overworld | Mineshaft (`MapGenMesaMineshaft`) | **UNRELIABLE** 1/435 |
| Nether (DIM-1) | Fortress | verified 5/5 (185 found) |
| Twilight Forest (DIM7) | TFFeature | verified 16/16 (545 found) |

Everything except mineshafts reproduces exactly. Mineshafts here are
`ganymedes01.etfuturum.world.structure.MapGenMesaMineshaft`, which overrides only
`getStructureStart` — the placement predicate is vanilla's.

What has been ruled out, all reproducibly:

- The replay matches decompiled `MapGenBase` and `MapGenStructure` exactly,
  including the `rand.nextInt()` consumed before the chunk test.
- A from-scratch `java.util.Random` reimplementation in Python fails identically
  (2/435, mean `nextDouble` 0.52 — noise), so it is not a mod-side bug.
- `/survey diag` brute-forces 40 candidate seedings (5 formulas x 2 multiplier
  derivations x 0-3 RNG skips) against the *live* predicate object, which catches
  a coremod having rewritten its bytecode. Best score: 0/11.
- Villages score EXACT MATCH in the same harness, so the harness is sound.
- Spatially the real mineshafts show no grid or spacing rule at any period from
  4 to 80 chunks — the placement is random in character, just not from this
  RNG stream.

Not a fixable-by-guessing problem. `/survey diag` also reports predicate
reachability, which distinguishes "wrong seeding" from "gated on biome/terrain
so no seeding can work".

Mineshafts here are `ganymedes01.etfuturum.world.structure.MapGenMesaMineshaft`,
which only overrides `getStructureStart` — the placement predicate is vanilla's.
The replay matches decompiled `MapGenBase` exactly, and a from-scratch
reimplementation of `java.util.Random` in Python fails identically (2/435, mean
`nextDouble` 0.52 — noise). Brute-forcing 5 seeding formulas x 2 multiplier
derivations x 0-3 RNG skips x 3 probability thresholds found no correlation.

These mineshafts are simply not reproducible from the placement predicate;
they were most likely generated under a different mod or config state. Nothing
in the replay is known to be wrong — but do not trust mineshaft output.

## In-game map (press N)

A top-down map screen, rebindable under **Options > Controls > Miscellaneous**
("Structure Surveyor map"). Default **N**, chosen because it is unbound in GTNH
while M and J belong to JourneyMap.

| | |
|---|---|
| drag | pan |
| wheel | zoom about the cursor |
| `R` | centre on the player |
| `+` / `-` | zoom |
| click a marker | print its coordinates to chat |
| click a sidebar row | toggle that structure type |

**Terrain** is drawn one pixel per block from the save's own `HeightMap` and
biome data, tinted by biome map colour and shaded by the slope against the
north-west neighbour — so hills and valleys read as relief. Nothing is generated
to draw it: only chunks that already exist appear, which makes the map an honest
picture of what has been explored.

Imagery is built one region at a time (512x512 px, ~1 MB each, 24 kept resident)
and fills in progressively over a few frames. That work happens on the render
thread on purpose: chunk data comes through Minecraft's own `RegionFileCache`,
which the integrated server is also using, and a brief progressive fill beats
racing it from a worker thread.

**Markers** are this session's `/survey` predictions plus everything the world has
actually recorded. Filled = confirmed, **hollow = unverified prediction**, and the
sidebar count turns amber when a type contains any. Generators marked inactive for
the dimension are excluded entirely.

Terrain needs local region files, so on a remote server the map shows markers
only and says so.

### Caching

Two layers, because nothing here should be computed twice:

- **Within a session** — imagery and scan results live in `MapCache`, keyed by
  dimension, not in the screen. Reopening the map is instant, and dimension
  hopping does not throw work away. Released when you leave the world (on
  disconnect, not on `WorldEvent.Unload`, which fires at every portal).
- **Across restarts** — built region imagery is written to
  `.minecraft/surveyor/cache/<save>/DIM<n>/r.<x>.<z>.img`, and scan results to
  `signatures.bin` beside it.

Each cached region records the source `.mca` file's **timestamp**. If the region
file has changed since — because you explored more of it — the entry is ignored
and rebuilt, so the map can never show stale terrain.

The scan cache stores **raw hits, not clustered findings**, along with the set of
chunks already examined. Clustering has to run over everything at once for a
structure spanning a region boundary to stay one finding, so the inputs are what
must survive a restart. It also means `S` never rescans a chunk it has already
seen, in this session or any previous one.

Delete the `surveyor/cache` folder to force a full rebuild.

## Offline verification (independent check)

The replay must reproduce Minecraft's per-chunk RNG seeding exactly. If it does
not, the output is wrong but still looks plausible. The world's own `.dat` files
are the oracle — every structure the game actually generated must appear in a
sweep covering it:

```bash
python verify_sweep.py "<instance>/.minecraft/surveyor/survey_dim0.json"
```

Run a sweep centred on **explored** terrain first, so there is ground truth to
check against. Read the per-type breakdown, not just the total: villages seed
their own RNG from the world and can pass while RNG-dependent types like
mineshafts fail.

---

# Step 3: `scan_regions.py` — the structures nothing else can find

Roguelike Dungeons, Lootgames and space dungeons write **no** structure data, so
`find_structures.py` cannot see them, and their placement depends on terrain
height, so `/survey` cannot predict them. What they do leave is blocks.

This reads region files straight off disk and matches block signatures. Offline,
read-only, no mod, no dependencies — but **close the game first**, region files
are held open while it runs.

```bash
python scan_regions.py                       # overworld of the newest world
python scan_regions.py --dim -1              # nether
python scan_regions.py <world> --json out.json --csv out.csv
python scan_regions.py --list-blocks lootgames   # explore the block registry
python scan_regions.py --only roguelike      # one signature
```

Block names are resolved through the save's **own FML registry**, so signatures
survive the id reshuffling that happens between pack versions.

## What it finds

| Signature | How |
|---|---|
| Lootgames dungeon | its `*_master` block — one per dungeon, so a hit *is* a dungeon |
| Roguelike dungeon | spawner density; a vanilla dungeon or mineshaft has one, a roguelike stacks many |
| Space dungeon (Mars) | mars dungeon brick stairs |
| HEE dungeon | `dungeon_puzzle` |

Roguelike has no blocks of its own, so the spawner count doubles as confidence:
20+ `certain`, 8+ `likely`, below that `possible`.

Roguelike dungeons are also split by **surface building**, which is what you
actually look for from above — Brick House (forest), Wizard Tower (mountain),
Wooden Outpost (swamp), Castle Tower (plains), Overgrown Temple (jungle),
Sandstone Temple (desert). The biome comes from the chunk (`Biomes16v2`, which
EndlessIDs uses instead of vanilla's byte array so ids can exceed 255) and names
from vanilla ids plus the pack's BoP `ids.cfg`. The biome→variant rules are
inferred from biome family rather than read out of Roguelike's own settings, so
an odd label is a hint; **the coordinates are exact regardless**. Unknown biomes
report `biome id=N` rather than a blank.

## Result on this world

7,800 generated chunks: **10 Lootgames dungeons, 5 Roguelike dungeons**
(4 `certain`, 1 `possible`).

---

# Step 4: `make_map.py` — one map for everything

The three tools produce three different *kinds* of knowledge, and they are only
really useful together. This merges them into a single self-contained HTML file:

```bash
python make_map.py                    # auto-discovers everything
python make_map.py --out mymap.html --dim 0
```

Open the file in a browser. No server, no dependencies, no network.

| Source | Marker | Means |
|---|---|---|
| `find_structures.py` | circle | recorded — exact, from the world's own data |
| `/survey` | square | predicted from the seed, reaches unexplored terrain |
| `scan_regions.py` | diamond | block signature — the only way to see dungeons |

**Filled = trustworthy, hollow = unverified or low confidence.** A layer that is
entirely unreliable (predicted mineshafts) starts switched **off**; mixed layers
stay on but the sidebar says how many entries are not to be trusted, e.g.
`Mineshaft  39 unverified  56`. A wrong replay can never quietly put fake markers
on the map.

Pan with drag, zoom with the wheel, hover for coordinates and distance from
spawn. Dimensions switch in the sidebar. Light/dark aware.

Two things worth knowing, both learned the hard way:

- The default view is **spawn-centred and framed on the bulk of the markers**,
  not fit-to-everything. Fitting everything is a bad default here: one survey run
  far from spawn squashes the populated area into a single dot. `Fit all` is still
  a button.
- Sizing the view has to wait for layout (`requestAnimationFrame`). Reading
  `clientWidth` too early yields zero and silently pins the zoom to its floor.

## Build notes for this machine

Two non-obvious settings in `surveyor/gradle.properties`:

- `org.gradle.jvmargs = -Djdk.net.unixdomain.tmpdir=...` — Windows here cannot
  create usable AF_UNIX sockets under `AppData\Local`, which breaks
  `Selector.open()` and therefore the Gradle daemon. Note it is *not*
  `java.io.tmpdir`.
- `enableModernJavaSyntax = jvmDowngrader` — the default `jabel` mode needs an
  Azul Zulu JDK, and `cdn.azul.com` is unreachable from this network. The
  downgrader reaches the same Java 8 bytecode target using the local JDK.
