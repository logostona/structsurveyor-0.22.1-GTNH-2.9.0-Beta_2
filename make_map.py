#!/usr/bin/env python3
"""
make_map.py - build one interactive map from every structure source.

Three tools produce three kinds of knowledge, and they are only useful together:

  recorded   find_structures.py  - exact, from the world's own structure data
  predicted  /survey             - from the seed, reaches unexplored terrain
  scanned    scan_regions.py     - block signatures, the only way to see
                                   Roguelike/Lootgames/space dungeons

This merges whatever it can find into a single self-contained HTML file: pan,
zoom, toggle layers, click a marker for coordinates. No server, no dependencies,
no network - open the file and it works.

    python make_map.py                       # auto-discover everything
    python make_map.py --out mymap.html
    python make_map.py --dim 0               # one dimension only

Predicted structures whose generator failed self-verification are tagged
unreliable and switched off by default, so a wrong replay cannot quietly put
fake markers on your map.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys

from find_structures import (DEFAULT_SAVES, dimension_label, discover_worlds,
                             enrich_village_names, scan_world, world_info)

# Sits beside the world folders, next to .minecraft/saves.
DEFAULT_INSTANCE_SURVEYOR = os.path.join(os.path.dirname(DEFAULT_SAVES), "surveyor")


# --------------------------------------------------------------------------
# Gathering
# --------------------------------------------------------------------------


def collect_recorded(world, warnings):
    """Exact structures from the world's saved structure data."""
    items = scan_world(world, warnings)
    enrich_village_names(world, items, warnings)
    out = []
    for s in items:
        x, y, z = s.center
        out.append({
            "source": "recorded", "kind": s.kind, "name": s.name or "",
            "dim": s.dim, "x": x, "y": y, "z": z,
            "detail": ", ".join(s.notable()),
            "reliable": True,
        })
    return out


def collect_predicted(survey_dir, warnings):
    """Seed-predicted structures from /survey output, one file per dimension."""
    out = []
    files = sorted(glob.glob(os.path.join(survey_dir, "survey_dim*.json")))
    for path in files:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except Exception as exc:                          # noqa: BLE001
            warnings.append("could not read %s (%s)" % (os.path.basename(path), exc))
            continue
        dim = data.get("dimension", 0)
        for gen in data.get("generators", []):
            verdict = gen.get("verdict", "")
            active = gen.get("active_in_dimension", True)
            if not active:
                continue            # inherited generator this dimension never uses
            reliable = verdict == "verified"
            for s in gen.get("structures", []):
                out.append({
                    "source": "predicted", "kind": gen.get("tag", "?"), "name": "",
                    "dim": dim, "x": s.get("x"), "y": None, "z": s.get("z"),
                    "detail": verdict,
                    "reliable": reliable,
                })
    return out, files


def collect_scanned(paths, warnings):
    """Block-signature findings from scan_regions.py."""
    out = []
    for path in paths:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except Exception as exc:                          # noqa: BLE001
            warnings.append("could not read %s (%s)" % (os.path.basename(path), exc))
            continue
        dim = data.get("dimension", 0)
        for f in data.get("findings", []):
            bits = [b for b in (f.get("variant"), f.get("biome"), f.get("confidence")) if b]
            out.append({
                "source": "scanned", "kind": f.get("label", "?"),
                "name": f.get("variant") or "",
                "dim": dim, "x": f.get("x"), "y": f.get("y"), "z": f.get("z"),
                "detail": " / ".join(bits),
                "reliable": f.get("confidence") != "possible",
            })
    return out


# --------------------------------------------------------------------------
# HTML
# --------------------------------------------------------------------------

HTML = r"""<meta charset="utf-8">
<title>__TITLE__</title>
<style>
  :root {
    --bg: #f6f7f9; --panel: #ffffff; --ink: #14171a; --muted: #5b6570;
    --line: #dfe3e8; --grid: #e9ecf0; --axis: #c3cad2;
  }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#0f1216; --panel:#171b21; --ink:#e8ecf1; --muted:#9aa5b1;
            --line:#262c35; --grid:#1e242c; --axis:#37404b; }
  }
  :root[data-theme="dark"] {
    --bg:#0f1216; --panel:#171b21; --ink:#e8ecf1; --muted:#9aa5b1;
    --line:#262c35; --grid:#1e242c; --axis:#37404b;
  }
  :root[data-theme="light"] {
    --bg:#f6f7f9; --panel:#ffffff; --ink:#14171a; --muted:#5b6570;
    --line:#dfe3e8; --grid:#e9ecf0; --axis:#c3cad2;
  }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--ink);
         font:14px/1.5 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif; }
  .wrap { display:flex; height:100vh; overflow:hidden; }
  .side { width:280px; flex:0 0 280px; background:var(--panel);
          border-right:1px solid var(--line); overflow-y:auto; padding:16px; }
  .stage { flex:1; position:relative; min-width:0; }
  canvas { display:block; width:100%; height:100%; cursor:crosshair; }
  h1 { font-size:15px; margin:0 0 2px; letter-spacing:-.01em; }
  .sub { color:var(--muted); font-size:12px; margin-bottom:16px; }
  h2 { font-size:11px; text-transform:uppercase; letter-spacing:.07em;
       color:var(--muted); margin:18px 0 8px; font-weight:600; }
  label.row { display:flex; align-items:center; gap:8px; padding:3px 0;
              cursor:pointer; font-size:13px; }
  label.row input { accent-color:#3b82f6; }
  .dot { width:9px; height:9px; border-radius:50%; flex:0 0 9px; }
  .sq { width:9px; height:9px; flex:0 0 9px; }
  .count { margin-left:auto; color:var(--muted); font-size:11px;
           font-variant-numeric:tabular-nums; }
  .warn { color:#b45309; font-size:11px; margin-left:auto; }
  button { background:var(--panel); color:var(--ink); border:1px solid var(--line);
           border-radius:6px; padding:5px 9px; font-size:12px; cursor:pointer; }
  button:hover { border-color:var(--axis); }
  .btns { display:flex; gap:6px; flex-wrap:wrap; margin-top:8px; }
  .hud { position:absolute; left:12px; bottom:12px; background:var(--panel);
         border:1px solid var(--line); border-radius:6px; padding:6px 10px;
         font-size:12px; font-variant-numeric:tabular-nums; color:var(--muted);
         pointer-events:none; }
  .tip { position:absolute; background:var(--panel); border:1px solid var(--line);
         border-radius:6px; padding:8px 10px; font-size:12px; pointer-events:none;
         box-shadow:0 4px 14px rgba(0,0,0,.18); max-width:260px; display:none; z-index:5; }
  .tip b { font-size:13px; }
  .tip .c { color:var(--muted); font-variant-numeric:tabular-nums; }
  .legend { font-size:11px; color:var(--muted); line-height:1.7; }
  .legend i { display:inline-block; width:9px; height:9px; margin-right:5px; }
</style>

<div class="wrap">
  <aside class="side">
    <h1>__WORLD__</h1>
    <div class="sub">__SUBTITLE__</div>

    <h2>Dimension</h2>
    <div id="dims"></div>

    <h2>Structures</h2>
    <div id="kinds"></div>

    <h2>Source</h2>
    <div class="legend">
      <div><i class="dot" style="background:currentColor;border-radius:50%"></i>round = recorded (exact)</div>
      <div><i class="sq" style="background:currentColor"></i>square = predicted from seed</div>
      <div><i class="sq" style="background:currentColor;transform:rotate(45deg)"></i>diamond = block scan</div>
      <div style="margin-top:6px">Hollow = unverified or low confidence.</div>
    </div>

    <h2>View</h2>
    <div class="btns">
      <button id="home">Home</button>
      <button id="fit">Fit all</button>
      <button id="origin">Go to 0,0</button>
      <button id="theme">Theme</button>
    </div>
  </aside>

  <div class="stage">
    <canvas id="cv"></canvas>
    <div class="hud" id="hud">&nbsp;</div>
    <div class="tip" id="tip"></div>
  </div>
</div>

<script>
const DATA = __DATA__;
const SPAWN = __SPAWN__;

const PALETTE = ["#3b82f6","#ef4444","#10b981","#f59e0b","#8b5cf6","#ec4899",
                 "#14b8a6","#f97316","#6366f1","#84cc16","#06b6d4","#a855f7"];
const colorOf = {};
[...new Set(DATA.map(d => d.kind))].sort().forEach((k,i) => colorOf[k] = PALETTE[i % PALETTE.length]);

const dims = [...new Set(DATA.map(d => d.dim))].sort((a,b) => a-b);
let activeDim = dims.includes(0) ? 0 : dims[0];
const enabled = {};

const cv = document.getElementById("cv"), ctx = cv.getContext("2d");
const tip = document.getElementById("tip"), hud = document.getElementById("hud");
let scale = 0.06, ox = 0, oz = 0;          // world->screen scale and centre

function visible() {
  return DATA.filter(d => d.dim === activeDim && enabled[d.kind] !== false);
}

/* ---------- sidebar ---------- */
function buildDims() {
  const host = document.getElementById("dims");
  host.innerHTML = "";
  dims.forEach(d => {
    const n = DATA.filter(x => x.dim === d).length;
    const l = document.createElement("label");
    l.className = "row";
    l.innerHTML = `<input type="radio" name="dim" ${d===activeDim?"checked":""}>
                   <span>${DATA.find(x=>x.dim===d).dimName}</span>
                   <span class="count">${n}</span>`;
    l.querySelector("input").onchange = () => { activeDim = d; buildKinds(); home(); };
    host.appendChild(l);
  });
}

function buildKinds() {
  const host = document.getElementById("kinds");
  host.innerHTML = "";
  const inDim = DATA.filter(d => d.dim === activeDim);
  const kinds = [...new Set(inDim.map(d => d.kind))].sort();
  kinds.forEach(k => {
    const rows = inDim.filter(d => d.kind === k);
    const shaky = rows.filter(r => !r.reliable).length;
    const allShaky = shaky === rows.length;
    // A layer that is entirely unreliable starts hidden - a wrong replay must
    // not put fake markers on the map unless you deliberately ask for it. Mixed
    // layers stay on, but say how many entries are not to be trusted.
    if (enabled[k] === undefined) enabled[k] = !allShaky;
    const note = allShaky ? "unreliable"
               : shaky ? shaky + " unverified" : "";
    const l = document.createElement("label");
    l.className = "row";
    l.innerHTML = `<input type="checkbox" ${enabled[k]?"checked":""}>
                   <span class="dot" style="background:${colorOf[k]}"></span>
                   <span>${k}</span>
                   ${note ? `<span class="warn">${note}</span>` : ''}
                   <span class="count">${rows.length}</span>`;
    l.querySelector("input").onchange = e => { enabled[k] = e.target.checked; draw(); };
    host.appendChild(l);
  });
  draw();
}

/* ---------- view ---------- */
function resize() {
  const r = cv.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
  cv.width = r.width * dpr; cv.height = r.height * dpr;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  draw();
}
const sx = x => (x - ox) * scale + cv.clientWidth / 2;
const sz = z => (z - oz) * scale + cv.clientHeight / 2;
const wx = px => (px - cv.clientWidth / 2) / scale + ox;
const wz = pz => (pz - cv.clientHeight / 2) / scale + oz;

/* Never trust clientWidth here: on first paint layout may not have run yet, and
   a zero width silently pins the zoom to its floor. */
function viewSize() {
  return [cv.clientWidth || cv.parentElement.clientWidth || 900,
          cv.clientHeight || cv.parentElement.clientHeight || 700];
}
const clampScale = s => Math.min(Math.max(s, 0.0015), 4);

function fit() {
  const pts = visible(), [w, h] = viewSize();
  if (!pts.length) { ox = oz = 0; scale = 0.06; return draw(); }
  const xs = pts.map(p => p.x), zs = pts.map(p => p.z);
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const minZ = Math.min(...zs), maxZ = Math.max(...zs);
  ox = (minX + maxX) / 2; oz = (minZ + maxZ) / 2;
  scale = clampScale(Math.min((w - 60) / Math.max(1, maxX - minX),
                              (h - 60) / Math.max(1, maxZ - minZ)));
  draw();
}

/* Default view. Fitting everything is the wrong default: one far-flung survey
   makes the populated area a single dot. Centre on spawn and frame the bulk of
   the markers, leaving outliers a pan away. */
function home() {
  const pts = visible(), [w, h] = viewSize();
  if (!pts.length) { ox = oz = 0; scale = 0.06; return draw(); }
  const asc = (a, b) => a - b;
  const xs = pts.map(p => p.x).sort(asc), zs = pts.map(p => p.z).sort(asc);
  const at = (a, f) => a[Math.min(a.length - 1, Math.floor(a.length * f))];
  const mid = a => at(a, 0.5);
  ox = (SPAWN && activeDim === 0) ? SPAWN[0] : mid(xs);
  oz = (SPAWN && activeDim === 0) ? SPAWN[1] : mid(zs);
  const spread = Math.max(at(xs, 0.9) - at(xs, 0.1), at(zs, 0.9) - at(zs, 0.1));
  const span = Math.max(600, Math.min(spread * 1.8, 40000));
  scale = clampScale(Math.min((w - 80) / span, (h - 80) / span));
  draw();
}

function css(v) { return getComputedStyle(document.documentElement).getPropertyValue(v).trim(); }

function draw() {
  const w = cv.clientWidth, h = cv.clientHeight;
  ctx.clearRect(0, 0, w, h);

  /* grid: pick a spacing that stays readable at any zoom */
  let step = 100;
  while (step * scale < 60) step *= 10;
  while (step * scale > 600) step /= 10;
  ctx.strokeStyle = css("--grid"); ctx.lineWidth = 1;
  ctx.beginPath();
  const x0 = Math.floor(wx(0) / step) * step, x1 = wx(w);
  for (let x = x0; x < x1; x += step) { const p = Math.round(sx(x)) + .5; ctx.moveTo(p, 0); ctx.lineTo(p, h); }
  const z0 = Math.floor(wz(0) / step) * step, z1 = wz(h);
  for (let z = z0; z < z1; z += step) { const p = Math.round(sz(z)) + .5; ctx.moveTo(0, p); ctx.lineTo(w, p); }
  ctx.stroke();

  /* axes */
  ctx.strokeStyle = css("--axis");
  ctx.beginPath();
  ctx.moveTo(Math.round(sx(0)) + .5, 0); ctx.lineTo(Math.round(sx(0)) + .5, h);
  ctx.moveTo(0, Math.round(sz(0)) + .5); ctx.lineTo(w, Math.round(sz(0)) + .5);
  ctx.stroke();

  /* spawn */
  if (SPAWN && activeDim === 0) {
    const px = sx(SPAWN[0]), pz = sz(SPAWN[1]);
    ctx.strokeStyle = css("--ink"); ctx.lineWidth = 1.5;
    ctx.beginPath(); ctx.arc(px, pz, 6, 0, 7); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(px-9,pz); ctx.lineTo(px+9,pz);
    ctx.moveTo(px,pz-9); ctx.lineTo(px,pz+9); ctx.stroke();
  }

  /* markers: shape encodes source, fill encodes trust */
  for (const d of visible()) {
    const px = sx(d.x), pz = sz(d.z);
    if (px < -20 || pz < -20 || px > w + 20 || pz > h + 20) continue;
    const c = colorOf[d.kind], r = 4;
    ctx.strokeStyle = c; ctx.fillStyle = c; ctx.lineWidth = 1.5;
    ctx.beginPath();
    if (d.source === "recorded") {
      ctx.arc(px, pz, r, 0, 7);
    } else if (d.source === "predicted") {
      ctx.rect(px - r + .5, pz - r + .5, r * 2, r * 2);
    } else {
      ctx.moveTo(px, pz - r - 1); ctx.lineTo(px + r + 1, pz);
      ctx.lineTo(px, pz + r + 1); ctx.lineTo(px - r - 1, pz); ctx.closePath();
    }
    d.reliable ? ctx.fill() : ctx.stroke();
  }
  hud.textContent = `${visible().length} shown · 1 grid = ${step} blocks · scale ${scale.toFixed(3)}`;
}

/* ---------- interaction ---------- */
let drag = null;
cv.addEventListener("mousedown", e => drag = { x: e.clientX, y: e.clientY, ox, oz });
addEventListener("mouseup", () => drag = null);
addEventListener("mousemove", e => {
  if (drag) {
    ox = drag.ox - (e.clientX - drag.x) / scale;
    oz = drag.oz - (e.clientY - drag.y) / scale;
    return draw();
  }
  const r = cv.getBoundingClientRect();
  if (e.clientX < r.left || e.clientY < r.top) { tip.style.display = "none"; return; }
  const mx = e.clientX - r.left, my = e.clientY - r.top;
  let best = null, bestD = 12 * 12;
  for (const d of visible()) {
    const dx = sx(d.x) - mx, dz = sz(d.z) - my, dd = dx * dx + dz * dz;
    if (dd < bestD) { bestD = dd; best = d; }
  }
  if (!best) { tip.style.display = "none"; return; }
  const dist = SPAWN ? Math.round(Math.hypot(best.x - SPAWN[0], best.z - SPAWN[1])) : null;
  tip.innerHTML = `<b>${best.kind}</b>${best.name ? " — " + best.name : ""}<br>
    <span class="c">X ${best.x}${best.y !== null ? "  Y " + best.y : ""}  Z ${best.z}</span>
    ${dist !== null ? `<br><span class="c">${dist} blocks from spawn</span>` : ""}
    ${best.detail ? `<br><span class="c">${best.detail}</span>` : ""}
    <br><span class="c">${best.source}${best.reliable ? "" : " · unverified"}</span>`;
  tip.style.display = "block";
  // The tooltip is positioned inside .stage, so it must use stage-relative
  // coordinates. Using viewport clientX here offsets it by the sidebar width.
  const st = cv.parentElement.getBoundingClientRect();
  const tw = tip.offsetWidth || 240, th = tip.offsetHeight || 80;
  let lx = mx + 14, ly = my + 14;
  if (lx + tw > st.width - 8) lx = mx - tw - 14;     // flip left near the edge
  if (ly + th > st.height - 8) ly = my - th - 14;    // flip up near the bottom
  tip.style.left = Math.max(4, lx) + "px";
  tip.style.top = Math.max(4, ly) + "px";
});
cv.addEventListener("wheel", e => {
  e.preventDefault();
  const r = cv.getBoundingClientRect();
  const bx = wx(e.clientX - r.left), bz = wz(e.clientY - r.top);
  scale = Math.min(Math.max(scale * (e.deltaY < 0 ? 1.15 : 1 / 1.15), 0.0015), 4);
  ox = bx - (e.clientX - r.left - cv.clientWidth / 2) / scale;
  oz = bz - (e.clientY - r.top - cv.clientHeight / 2) / scale;
  draw();
}, { passive: false });

document.getElementById("home").onclick = home;
document.getElementById("fit").onclick = fit;
document.getElementById("origin").onclick = () => { ox = 0; oz = 0; draw(); };
document.getElementById("theme").onclick = () => {
  const cur = document.documentElement.getAttribute("data-theme");
  const dark = cur ? cur === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
  document.documentElement.setAttribute("data-theme", dark ? "light" : "dark");
  draw();
};

addEventListener("resize", resize);
buildDims(); buildKinds(); resize();
// Wait for layout before sizing the view, or the zoom pins to its floor.
requestAnimationFrame(() => { resize(); home(); });
</script>
"""


def main(argv=None):
    p = argparse.ArgumentParser(description="Build one HTML map from all structure sources.",
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("world", nargs="?", help="world folder (default: most recent)")
    p.add_argument("--saves", help="saves folder to search")
    p.add_argument("--surveyor", help="folder holding survey_dim*.json")
    p.add_argument("--scan", action="append", metavar="FILE",
                   help="scan_regions.py JSON (repeatable; default: *_scan.json here)")
    p.add_argument("--dim", type=int, help="only this dimension")
    p.add_argument("--out", default="structure_map.html")
    args = p.parse_args(argv)

    if args.world:
        world = args.world
    else:
        worlds = discover_worlds(args.saves or DEFAULT_SAVES)
        if not worlds:
            raise SystemExit("no worlds found; pass a world folder")
        world = max(worlds, key=lambda w: os.path.getmtime(os.path.join(w, "level.dat")))

    warnings = []
    info = world_info(world, warnings)

    survey_dir = args.surveyor or DEFAULT_INSTANCE_SURVEYOR
    scans = args.scan or sorted(glob.glob("*_scan.json"))

    rows = collect_recorded(world, warnings)
    predicted, survey_files = collect_predicted(survey_dir, warnings)
    rows += predicted
    rows += collect_scanned(scans, warnings)

    if args.dim is not None:
        rows = [r for r in rows if r["dim"] == args.dim]
    rows = [r for r in rows if r["x"] is not None and r["z"] is not None]
    for r in rows:
        r["dimName"] = dimension_label(r["dim"])

    if not rows:
        raise SystemExit("nothing to map - run find_structures/survey/scan_regions first")

    spawn = [info["spawn"][0], info["spawn"][2]] if info.get("spawn") else None
    by_source = {}
    for r in rows:
        by_source[r["source"]] = by_source.get(r["source"], 0) + 1
    subtitle = "seed %s · %s" % (info.get("seed"), ", ".join(
        "%d %s" % (n, s) for s, n in sorted(by_source.items())))

    html = (HTML
            .replace("__TITLE__", "Structure map — %s" % info["name"])
            .replace("__WORLD__", info["name"])
            .replace("__SUBTITLE__", subtitle)
            .replace("__DATA__", json.dumps(rows, separators=(",", ":")))
            .replace("__SPAWN__", json.dumps(spawn)))

    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write(html)

    print()
    print("  %s   (%.0f KB)" % (args.out, os.path.getsize(args.out) / 1024))
    for s, n in sorted(by_source.items()):
        print("    %-10s %d" % (s, n))
    if survey_files:
        print("    surveys    %s" % ", ".join(os.path.basename(f) for f in survey_files))
    if scans:
        print("    scans      %s" % ", ".join(os.path.basename(f) for f in scans))
    if warnings:
        print("  notes:")
        for w in warnings:
            print("    -", w)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
