<div align="center">

# PlayerControl++

**A lightweight, client-side Fabric mod that turns repetitive tasks into hotkey-driven actions**

Think of it as a mod that brings other mods together: optional integrations unlock more powerful features.

</div>

---

## Overview

PlayerControl++ builds on MaLiLib to add practical client-side utilities. Press **`P + C`** to open the settings by default.

---

## Features

| Feature | What it does |
|---|---|
| **Auto Forward** | Keeps you moving forward with a single keypress, just like holding down W |
| **Quick Turn** | Turns you around with one keypress. The angle is configurable, with a default of 180° |
| **Route Playback** | Record a start, waypoints, and an end, then follow the route with one keypress. Supports a set number of round trips or an endless loop, sprinting, and automatic jumps to help you get unstuck |
| **Input Recording & Playback** | Records movement, camera direction, and left- and right-click input at tick-level precision. Each category has its own capture toggle, so a recording can be attack-only or camera-only. Loop mode replays forever: after each pass it walks back along the recorded path to the start and goes again. Playback reproduces key input rather than coordinates, so a little drift is normal — the optional correction toggle only applies in single-player, where the client owns your position |
| **Auto-cache Nearby Containers (requires Chest Tracker)** | Automatically opens containers within reach so Chest Tracker can remember their contents. No need to open every chest by hand |
| **Cache Containers in a Schematic Selection (requires Chest Tracker and Litematica)** | The area-wide version of nearby caching: select a region, then walk through it to cache the containers within reach |
| **Auto Water Fill (requires Litematica)** | Waterlogs blocks that should contain water according to your schematic but do not yet. Especially handy for technical builds and redstone contraptions |
| **Render-layer Sync (requires Litematica)** | Advances the schematic's render layer after each route traversal, helping you work through a build layer by layer |

> Available features and configuration options depend on the optional mods you have installed.
> Compatible material-list managers and pathfinding mods can unlock additional tools not listed here,
> such as collecting building supplies from chests to match your schematic, whole shulker boxes at a time.
> Explore the settings to see what your setup supports.

---

## Dependencies

**Required**

| Mod | What it provides |
|---|---|
| Fabric API | Core runtime support |
| MaLiLib | Configuration, menus, and hotkeys |

**Optional — install these to use their integrations**

| Mod | What it unlocks |
|---|---|
| Litematica | Container caching within an area selection, automatic waterlogging, and render-layer sync |
| Chest Tracker | Container contents caching |
| QuickShulker | Opening shulker boxes directly from your inventory |
| LitematList | Manage multiple schematic material lists: upload, merge, replace, and ignore materials. Its lists can also be used for automatic material gathering |
| Mod Menu | Access to the configuration screen from the mod list |

---

## Supported Versions

`1.21` – `1.21.1` · `1.21.4` · `1.21.6` – `1.21.7` · `1.21.8` · `1.21.11` · `26.1` – `26.1.2` · `26.2`

**Client-side only:** PlayerControl++ does not need to be installed on the server.

---

## Feedback & Support

- Found a bug or have a feature request? Please get in touch on GitHub or send me a private message on [Bilibili](https://space.bilibili.com/478620684).
- Much of the mod was developed with AI assistance. I remain responsible for technical support and will do my best to respond promptly to feedback.

---

<div align="center">

**By Alonediamond** · MIT License

</div>
