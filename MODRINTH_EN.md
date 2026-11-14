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
| **Input Recording & Playback** | Records movement, camera direction, and left- and right-click input at tick-level precision. Replay whenever you like, with optional endless looping and compressed recording files |
| **Auto-cache Nearby Containers (requires Chest Tracker)** | Automatically opens containers within reach so Chest Tracker can remember their contents. No need to open every chest by hand |
| **Cache Containers in a Schematic Selection (requires Chest Tracker and Litematica)** | The area-wide version of nearby caching: select a region, then walk through it to cache the containers within reach |
| **Auto Water Fill (requires Litematica)** | Waterlogs blocks that should contain water according to your schematic but do not yet. Especially handy for technical builds and redstone contraptions |
| **Render-layer Sync (requires Litematica)** | Advances the schematic's render layer after each route traversal, helping you work through a build layer by layer |
| **Automatic Schematic Material Gathering (requires Baritone, Chest Tracker, and Litematica)** | Finds cached containers, travels to them, and collects the schematic's materials. The current container or workstation can be highlighted in the world |
| **Manual Material Queue (requires Baritone and Chest Tracker)** | Search for items and arrange batches of additional quantities. Earlier batch targets stay reserved, and a failed batch can be resumed |
| **Build Restocking and Layer Building (requires Baritone and Litematica)** | Refills loose materials when building pauses for supplies. Layer building can be off, upward, or downward |

### Gathering, Crafting, and Layer Building

- Tracing raw materials for schematic gathering requires LitematList as the list source and an uploaded list. Tracing for the manual queue only requires LitematList to be loaded; no uploaded list is needed. Crafting preserves schematic requirements and earlier queue targets instead of treating them as spare materials.
- For each batch, recipe selection uses a beam width of 32 and estimates costs up to three recipe layers deep. It considers materials already held, cached stock within range, recipes, and known mineable sources. The estimate ranks candidates; actual container contents are checked when opened.
- Batches are planned as they run, so only the current batch's ingredients are gathered. Automatic workstation selection falls back between inventory and marked world positions of the same type; it does not switch between crafting table and stonecutter recipes, whose ingredient counts may differ. Stonecutting input is placed one unit at a time to preserve reserved materials.
- World material search tries only block positions known to Baritone near the player. The default radius is 64 blocks, with a total limit of 1,200 ticks per item. No known position does not mean the material is absent from the world.
- Layer building is off by default. Upward and downward modes require Litematica's single Y layer mode and a valid schematic range. Unloaded chunks delay completion; changing the layer or schematic settings during a run stops it.

Without Baritone, the gathering, crafting, restocking, and material queue pages are hidden. Routes and route hotkeys remain available separately.

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
| Baritone | Gathering navigation, world material search, and build restocking |
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
