<div align="center">

# PlayerControl++

**A lightweight client-side Fabric mod that hands your repetitive keypresses to a macro**

Auto forward · Quick turn · Route cruising · Input recording & playback · Bulk container caching · Schematic water filling

</div>

---

## What it is for

When you build something large, reorganise a warehouse, or run a long supply route, the time does not
go into deciding what to do — it goes into **holding one key down for a minute**, **clicking open one
chest after another**, and **right-clicking a water bucket onto three hundred blocks**.

PlayerControl++ turns those into hotkey-driven routines. Everything works by **simulating your own key
presses** — it never rewrites your position and never sends malformed packets, so it behaves the same
in single-player, on a LAN world, and on a multiplayer server.

---

## Features

| Feature | What it does |
|---|---|
| **Auto Forward** | Keeps walking forward on one keypress — the same as holding W. Switches off on world change |
| **Quick Turn** | Turns you around instantly; the angle is configurable (180° by default) |
| **Route Cruising** | Record a start, any number of waypoints, and an end, then walk the whole thing on one key. Round-trip N times or loop forever, sprint the whole way, and jump automatically when stuck |
| **Input Recording & Playback** | Records your movement, camera and click input at tick precision and plays it back. Loops indefinitely; the files are compressed |
| **Auto-cache Nearby Containers** | Opens every container within arm's reach so a container-index mod can record its contents — no more clicking through a wall of chests by hand |
| **Cache Containers in a Selection** | The whole-area version of the above: mark out a region, then simply walk through it and the containers inside get cached as you pass them |
| **Auto Water Fill** | Fills in the blocks that your schematic says should be waterlogged but are not yet, saving hundreds of manual right-clicks |
| **Render-layer Sync** | Flips the schematic to the next layer each time a route completes a lap, so the blueprint follows you |

> The options in the config screen **grow and shrink with the optional mods you have installed**.
> With more compatible mods present, a few extra advanced options show up that are not listed here —
> have a look for yourself.

---

## Getting started

1. Install **Fabric Loader**, **Fabric API** and **MaLiLib**, then drop this mod into `mods`.
2. In game press **`P` + `C`** to open the config screen (Mod Menu works too, if you have it).
3. Bind keys for the features you want on the **Hotkeys** tab — nothing is bound by default, so it
   cannot clash with your existing keys.

The UI follows the MaLiLib convention (Litematica / Tweakeroo), so there is nothing new to learn.
All text is available in **English** and **简体中文**.

---

## Dependencies

**Required**

| Mod | Why |
|---|---|
| Fabric API | Base runtime |
| MaLiLib | Config, GUI and hotkey system |

**Optional — the matching features appear only when these are present, and their options stay hidden otherwise**

| Mod | Unlocks |
|---|---|
| Litematica | Selection container caching, auto water fill, render-layer sync |
| Chest Tracker | Container content caching |
| QuickShulker | Opening shulker boxes straight from the inventory |
| Mod Menu | Config entry from the mod list |

---

## Supported versions

`1.21` – `1.21.1` · `1.21.4` · `1.21.6` – `1.21.7` · `1.21.8` · `1.21.11` · `26.1` – `26.1.2` · `26.2`

**Client-side only** — nothing needs to be installed on the server.

---

## Good to know

- Every automated routine stops or pauses immediately when you **change world, die, or open another
  screen**. Control is always one keypress away.
- Route and recording data is written atomically, so a crash will not leave you with a corrupt config.
- Playback reproduces **key input, not coordinates**, so a little positional drift is normal. The mod
  tells you when it happens and offers an optional correction toggle.

---

<div align="center">

**by Alonediamond** · MIT licence · Issues and suggestions welcome on GitHub

</div>
