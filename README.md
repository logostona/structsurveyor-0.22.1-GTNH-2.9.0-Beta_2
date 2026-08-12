# Structure Surveyor

Find structures in **GregTech: New Horizons 2.9.0 beta 2** (Minecraft 1.7.10 / Forge
10.13.4.1614) — including the ones no other tool can see.

An in-game map on a keybind, plus offline Python tools. Everything reports **where its
information came from and how much to trust it**, because the three ways of finding a
structure have very different reliability.

> **Prototype.** Detection rules are still being tuned against real worlds. Nothing here
> modifies your save — the mod reads region files, and the Python tools are read-only —
> but treat findings as leads, not gospel. Issues and world reports are welcome.

---

## What it does

Three independent sources, each with different reach:

| Source | Reach | Trust |
|---|---|---|
| **Recorded** — the world's own `MapGenStructureData` | Explored chunks only | Exact |
| **Predicted** — replays generator placement from the seed | Unexplored terrain too | Self-verified against known structures |
| **Scanned** — block and spawner signatures | Generated chunks only | Heuristic, confidence marked |

The third is the interesting one. **Roguelike Dungeons, Lootgames, space dungeons and
AE2 meteorites write no structure data and their placement depends on terrain**, so they
cannot be predicted from a seed at all. The only evidence they leave is what they built.

### Currently detected

Villages · Mineshafts · Strongholds · Nether fortresses · Twilight Forest features ·
Moon villages · Roguelike Dungeons · Lootgames dungeons · Wizard towers · Hilltop Stones ·
Stone circles · Slime islands · AE2 meteorites · Space dungeons · HEE dungeons ·
spawner clusters

---

## Install

Drop `structsurveyor-<version>.jar` into your instance's `mods/` folder. No dependencies
beyond Forge.

Press **N** to open the map (rebindable under *Options → Controls → Miscellaneous*).

### Map controls

| | |
|---|---|
| drag / wheel | pan / zoom |
| right-click | mark a spot visited (white X) |
| `R` | centre on player |
| `T` | teleport to the hovered marker |
| `Z` · `[` `]` | scan a radius around you · adjust it |
| `S` | scan the whole world |
| `C` | clear findings and rescan |
| `A` | show overlapping matches |
| `D` | dump detection stats to chat |

Terrain is drawn from the save's own heightmap and biome data, one pixel per block,
shaded by slope. **Nothing is generated to draw it** — only chunks that already exist
appear, so the map is an honest picture of what you have explored.

### `/survey` command

```
/survey <radius> [x z]     predict structures from the world seed
/survey list               generators present in this dimension
/survey diag               test seeding candidates against the live predicate
/survey status | stop | rate <n>
```

Each sweep **verifies itself**: it compares its predictions against the structures the
world has actually recorded and reports `verified`, `UNRELIABLE`, or `unverified`. A
wrong replay produces right-looking density and plausible spread, so the count alone
tells you nothing — read the verdict.

Results are written to `.minecraft/surveyor/survey_dim<N>.json`.

---

## Offline tools

Pure Python 3.8+, no dependencies. Close the game first — region files are held open
while it runs. Set `SURVEYOR_MINECRAFT` to your `.minecraft` folder if auto-discovery
picks the wrong instance.

```bash
python find_structures.py            # exact list from the world's structure data
python scan_regions.py --dim -1      # block-signature scan of region files
python verify_sweep.py survey_dim0.json   # check a sweep against ground truth
python make_map.py                   # merge everything into one HTML map
```

---

## Server support

**Partial.** The `/survey` command works on a dedicated server; the map does not.

| | |
|---|---|
| Singleplayer / LAN | everything works |
| Dedicated server, mod installed | `/survey` works, writes JSON server-side |
| Client on a remote server | map opens but stays empty |

The map reads region files from the local save and queries the integrated server for
recorded structures. A remote client has neither, so it shows *"No terrain data (remote
server?)"*. Making it work would need findings sent over the network and region data read
server-side — not done yet.

`acceptableRemoteVersions = "*"`, so the mod never blocks joining a server that lacks it.

---

## Building

Requires a JDK (17+ to run Gradle). The GTNH buildscript fetches everything else.

```bash
cd surveyor && ./gradlew reobfJar
```

`build/libs/structsurveyor-<version>.jar` is the one to install. The `-dev` jar is for a
development workspace only.

Two settings differ from the GTNH template, both for a reason:

- `enableModernJavaSyntax = jvmDowngrader` — the default `jabel` needs an Azul Zulu JDK
  that Gradle must download; the downgrader reaches the same Java 8 target using whatever
  JDK you have.
- `enableGenericInjection = false` — that step also wants a specific vendor's JDK and is
  only a source-readability nicety.

<details>
<summary>If Gradle fails with <code>Unable to establish loopback connection</code></summary>

On some Windows setups AF_UNIX sockets don't work under `AppData\Local`, which breaks
`Selector.open()` and therefore the Gradle daemon. Point the JDK somewhere that works, in
`~/.gradle/gradle.properties`:

```
org.gradle.jvmargs=-Xmx4g -Djdk.net.unixdomain.tmpdir=C:/some/short/path
```

Note it is **not** `java.io.tmpdir`.
</details>

---

## How detection works, and where it is wrong

[docs/NOTES.md](docs/NOTES.md) has the full account: what each signature keys on, every
rule that exists because of a specific false positive, and the investigations that did
*not* pan out — including one generator whose recorded output still cannot be reproduced
from its own documented algorithm.

Worth knowing up front:

- **Mineshafts are `UNRELIABLE` in prediction.** The replay matches decompiled
  `MapGenBase` exactly and a from-scratch reimplementation fails identically, so the
  cause is elsewhere. They are flagged, not hidden.
- **Roguelike variants** are only named when there is evidence — an enchantment table
  means wizard tower. Guessing the building from the biome produced confident, wrong
  labels, so it was removed.
- **Spawner-based detection is heuristic.** Mineshafts, wasp nests and fortresses all
  produce spawner clusters; the rules separating them are tuned against a handful of real
  worlds and will need more.

## License

MIT — see [LICENSE](LICENSE).
