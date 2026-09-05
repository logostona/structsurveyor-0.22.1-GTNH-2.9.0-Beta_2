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

(That mineshaft line is the historical example that this check was built to catch;
the cause is found and fixed — see *The mineshaft bug* below.)

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
| Overworld | Mineshaft (`MapGenMesaMineshaft`) | verified 191/191 |
| Nether (DIM-1) | Fortress | verified 5/5 (185 found) |
| Twilight Forest (DIM7) | TFFeature | verified 16/16 (545 found) |

### The mineshaft bug: MapGenBase.rand is not a java.util.Random

Mineshafts read `UNRELIABLE` for a long time — 1/435 on the world this was first
tested against. The cause turned out to be one line in the sweep, and it is worth
recording in full because the misleading part was the *evidence*, not the bug.

`MapGenBase` derives two multipliers at the top of every `generate()` call:

```java
this.rand.setSeed(world.getSeed());
long xMul = this.rand.nextLong();
long zMul = this.rand.nextLong();
// then, per chunk in a (2*range+1)^2 neighbourhood:
this.rand.setSeed(chunkX * xMul ^ chunkZ * zMul ^ world.getSeed());
```

The sweep reproduced the per-chunk line correctly but drew the two multipliers
from a fresh `new Random(worldSeed)`. That is the same thing *only while
`MapGenBase.rand` is a `java.util.Random`* — and in this pack it is not.
Hodgepodge's `fastload` mixin
(`mixins.early.minecraft.fastload.rand.MixinMapGenBase`) replaces that field with
`com.mitchej123.hodgepodge.util.StdLCG`, whose `setSeed` stores the seed raw:

```java
public void setSeed(long seed) { this.seed = seed; }        // StdLCG
this.seed = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1);       // java.util.Random
```

Same world seed, completely different multipliers, so every per-chunk seed was
wrong. (`StdLCG`'s *constructor* does apply the scramble; only `setSeed` skips it,
and `MapGenBase` uses `setSeed`.)

The fix is to draw the multipliers from the generator's own `rand`, which is
correct whether or not that field has been swapped. Against this world's 191
recorded mineshaft starts: **191/191**, with every `nextDouble` landing under
`0.004` (max `0.00399`, flush against the threshold, which is what a correct
replay looks like). The old code scores 2/191 — noise at the base rate.

**Why every other generator hid this.** Mineshaft is the only structure here
whose predicate consumes the RNG stream it inherits from `MapGenBase`. Village
calls `worldObj.setRandomSeed(...)`, stronghold precomputes its positions from
its own `new Random(worldSeed)`, and nether fortress and `TFFeature` re-seed
`this.rand` inside their own predicates. All of them overwrite whatever seeding
the sweep set up, so all of them verify no matter how wrong the multipliers are.
"Villages score EXACT MATCH, so the harness is sound" was the reasoning that kept
this hidden the longest, and it does not follow: villages never exercise the code
path mineshafts depend on. A generator that re-seeds is not evidence about one
that doesn't.

The earlier investigation was sound as far as it went, and each result now reads
as a symptom rather than a dead end:

- The replay does match decompiled `MapGenBase` and `MapGenStructure`, including
  the `rand.nextInt()` consumed before the chunk test. The bug was upstream of
  the part being checked.
- A from-scratch `java.util.Random` in Python failed identically because it too
  was the wrong RNG — reimplementing the standard algorithm faithfully cannot
  help when the game is not running the standard algorithm.
- `/survey diag` brute-forced 5 formulas x 2 multiplier derivations x 0-3 skips
  against the live predicate, but every candidate took its multipliers from a
  `java.util.Random`. The one dimension that mattered was held fixed. It now
  searches multiplier *source* as well, and labels which one won.
- "No grid or spacing rule at any period from 4 to 80 chunks" was correct and
  expected: vanilla mineshafts have no spacing rule, only a per-chunk
  probability.

The general lesson for this pack: any 1.7.10 coremod may replace a vanilla field
with a lookalike. Read state off the live object rather than reconstructing it
from the class the source says should be there.

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

**Filled = trustworthy, hollow = unverified or low confidence.** A layer whose
sweep came back `UNRELIABLE` starts switched **off**; mixed layers
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
