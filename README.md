# Structure Surveyor

Find structures in **GregTech: New Horizons 2.9.0 beta 2** (Minecraft 1.7.10 / Forge
10.13.4.1614) — including the ones no other tool can see.

An in-game map on a keybind, plus offline Python tools. Everything reports **where its
information came from and how much to trust it**, because the three ways of finding a
structure have very different reliability.

[![Download structsurveyor-0.24.0.jar](https://img.shields.io/badge/download-structsurveyor--0.24.0.jar-2ea44f?style=for-the-badge)](https://github.com/logostona/structsurveyor-0.22.1-GTNH-2.9.0-Beta_2/raw/main/dist/structsurveyor-0.24.0.jar)

One click, then drop it in `mods/`. Forge 10.13.4.1614, no dependencies.

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

[**Download `structsurveyor-0.24.0.jar`**](https://github.com/logostona/structsurveyor-0.22.1-GTNH-2.9.0-Beta_2/raw/main/dist/structsurveyor-0.24.0.jar) and drop it into your instance's
`mods/` folder. No dependencies beyond Forge. Every released build also lives in
[`dist/`](dist/).

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

## Servers

**The map works on servers you do not own**, client-side, with nothing installed at the
other end. `acceptableRemoteVersions = "*"`, so the mod never blocks joining a server that
lacks it.

What changes is where the data comes from. In singleplayer the mod reads the save on disk,
so it can show terrain you walked through months ago and predict structures you have never
been near. On someone else's server none of that exists locally: the only world data on
your machine is the chunks the server has sent you. So the map records those as you travel
and remembers them.

| | Terrain | Recorded | Predicted | Scanned |
|---|---|---|---|---|
| Singleplayer / LAN host | whole save | yes | yes | whole save |
| Dedicated server, mod installed | whole save | yes | `/survey`, server-side | whole save |
| **Any server, client-side only** | **as you explore** | no | no | **as you explore, off by default** |

- **Terrain** fills in within view distance as you move, with the map closed as well as
  open, and persists to `surveyor/cache/server_<address>/` so reconnecting picks up where
  you left off. Each server address gets its own folder.
- **Recorded** and **Predicted** need the server's own generator objects and world seed,
  neither of which a client has. `/survey` is a server command; on a server without the
  mod it simply does not exist. The map says so rather than showing an empty grid.
- **Scanned** signature detection works — the same rules, reading loaded chunks instead of
  region files — but ships **off** for remote servers. See below.
- **Teleport** falls back to `/tp`, which the server will refuse unless you have
  permission for it.

### `scanOnRemoteServers` is off by default

Drawing terrain you have walked through is what every minimap does. Reading those same
chunks for spawners and buried blocks is a different thing: it finds dungeons through
solid rock, and plenty of servers class that as cheating no matter what the mod is called.

So it is a separate switch, off until you set it:

```
# config/structsurveyor.cfg
server {
    B:scanOnRemoteServers=false
    B:harvestWhileWalking=true
    I:harvestRadiusChunks=8
}
```

Whether your server allows it is between you and its rules. Nothing here hides itself from
anti-cheat, and nothing here asks the server for data it did not already send.

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
*not* pan out — including the coremod-swapped RNG that made mineshaft prediction look
unfixable until it wasn't.

Worth knowing up front:

- **Mineshafts used to predict `UNRELIABLE`, and now verify.** The sweep drew
  `MapGenBase`'s two seed multipliers from a fresh `java.util.Random`; hodgepodge's
  fastload mixin replaces `MapGenBase.rand` with an LCG whose `setSeed` skips the
  `0x5DEECE66D` scramble, so every per-chunk seed was wrong. Taking the multipliers
  from the generator's own RNG reproduces 191/191 recorded starts.
  [docs/NOTES.md](docs/NOTES.md) has the full account — including why every other
  generator verified anyway and hid it.
- **Roguelike variants** are only named when there is evidence — an enchantment table
  means wizard tower. Guessing the building from the biome produced confident, wrong
  labels, so it was removed.
- **Spawner-based detection is heuristic.** Mineshafts, wasp nests and fortresses all
  produce spawner clusters; the rules separating them are tuned against a handful of real
  worlds and will need more.

## License

MIT — see [LICENSE](LICENSE).
