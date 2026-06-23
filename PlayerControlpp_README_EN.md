<div align="center">
  <h1>PlayerControl++</h1>
  <p>v1.4 · A lightweight Fabric client-side mod — enhanced player controls, route flow automation, input recording & playback, container caching assistance, auto water fill, and auto material gathering.</p>
</div>

## Introduction

PlayerControl++ is a client-side Fabric mod designed to enhance the vanilla player experience. It provides auto-forward, quick-turn, a configurable route flow system with waypoints, and a high-fidelity input recording and playback system with RLE compression. The mod also integrates container caching automation, auto water fill assistance, and auto material gathering, with deep integration with tools like Litematica and Baritone.

All operations are implemented by simulating player keystroke inputs — no direct entity position modification or abnormal packet transmission. It strives to be compatible with multiplayer servers and anti-cheat environments.

## Quick Start

1. Install Fabric Loader, Fabric API, and MaLiLib.
2. Place this mod into your `mods` folder.
3. In-game, press the default hotkey `P + C` to open the config GUI. You can also access it from Mod Menu.
4. Bind hotkeys for each feature in the config GUI and enable auto-forward, route flow, recording, or caching as needed.

## Core Features

### Auto Forward

- Customizable Toggle hotkey; when pressed, the player continuously moves forward as if holding the W key.
- Automatically disables on world or dimension change, with ActionBar status notifications.
- Lightweight implementation that does not interfere with other key operations.

### Quick Turn

- Customizable Trigger hotkey; instantly rotates the player by a configurable angle (default 180°, range 0-360°).
- Angle can be adjusted in the config GUI.

### Route Flow System

The route flow system is one of the mod's core features, allowing players to preset multiple routes with waypoints and walk them automatically.

**Route Management**
- Each route supports multiple intermediate waypoints (start → waypoints → end).
- Supports adding / deleting routes, adding / deleting waypoints, and dynamic hotkey bindings.
- Route data is persisted as JSON files.

**Route Execution**
- Simulates holding the W key + automatic yaw correction, mimicking human walking.
- Hybrid turning mode: allows slight player camera movement, auto-corrects smoothly when deviation is too large.
  - Tiered correction speed (>45° = 25°/tick, >15° = 18°/tick, default 15°/tick).
  - ±2° dead zone to avoid a "head-lock" feeling.
- XZ-plane only; ignores vertical deviation.

**Loop Modes**

| Loop Count | Behavior |
|------------|----------|
| 1 | Single pass: start → end, then stop |
| >1 | Round-trip N times: start → end → start (back and forth) |
| 0 | Infinite loop |

**Stuck Detection**
- No movement for 3 seconds → auto-jump once.
- Still no movement 5 seconds after jump → terminate route with ActionBar notification.

**Safety System**
- World change, dimension change, player death, server disconnect → immediate stop.

**Per-Route Options (configured independently for each route)**
- **Sprint**: When enabled, holds the sprint key throughout the entire route.
- **LayerCtrl**: When enabled, automatically switches the Litematica schematic render layer after each traversal.

### Player Recording & Playback

Records player input at tick-level precision and plays it back, with RLE compression and position correction. Since v1.4, uses binary NBT format.

**Recorded Data**
- Movement (WASD), sprint, jump, sneak, camera (Yaw + Pitch), left/right click actions.

**RLE Compression**
- Consecutive identical inputs are merged into Segments, each with a duration field for the tick count.
- Greatly reduces file size while maintaining playback precision.

**NBT Binary Format (v1.4 Refactored)**
- Recording files changed from JSON (.json) to NBT binary format (.pcr extension).
- Uses RingBuffer with chunked pre-allocated arrays (CHUNK_SIZE=1024) during recording, reducing GC pressure.
- After recording stops, data is written to .pcr file on a background thread (PCpp-RecSave), not blocking the main thread.
- Corrupt file detection: damaged .pcr files show an ActionBar error message on load.
- Always records position keyframes (every 20 ticks); during playback, if position deviation exceeds 0.2 blocks, auto-corrects to the recorded trajectory.

**Playback**
- Play Count = 0 for infinite loop, Play Count = N to repeat N times.
- At playback start / loop restart, walks to the recorded starting position — no teleportation.
- On-demand file loading (full data is read only when the user clicks Play).

**File Management**
- Separate index file + individual recording files.
- The recording list GUI only reads the index, so open speed is independent of recording count.
- Deletion only removes the corresponding recording file + updates the index.

**Safety**
- Cannot start a new recording while playing.
- Automatically closes all GUI screens when recording/playback starts.
- Persistent ActionBar indicator during recording.

### Litematica Integration

During route execution, after each traversal (start→end or end→start), automatically switches the SINGLE_LAYER render layer of the Litematica schematic.

- Reflection-based invocation; no compile-time dependency. Silently degrades when Litematica is not installed.
- Only effective in SINGLE_LAYER mode.
- Supports custom layer increment (positive = up, negative = down).

### Auto Cache Nearby Containers

Helps container-caching mods like ChestTracker automatically discover nearby container contents without manually opening each one.

- Spherical scan centered on the player, with a radius equal to the current game mode's max interaction distance.
- 6-state task state machine: SCANNING → OPENING_CONTAINER → WAITING_AFTER_OPEN → CLOSING_GUI → COOLDOWN → AUTO_STOP_COUNTDOWN.
- Customizable container whitelist (default: all 28 vanilla storage container types).
- Configurable cache container delay (1-200 ticks, default 1). First container opens immediately; 3-second auto-stop unaffected.
- Sorted by distance from the player; already-cached containers are not re-opened.
- Continuously rescans as the player moves; newly entered containers are automatically added to the processing queue.
- All operations use normal client interaction flow — compatible with singleplayer, LAN, and multiplayer servers.
- Immediately stops on world change.

### Auto Water Fill

Automatically detects waterloggable blocks in Litematica schematics and uses water buckets to waterlog them. Requires a Litematica schematic to be loaded.

- 6-state task state machine; scans for waterloggable blocks in schematics within player reach distance.
- Automatically finds water buckets in the hotbar/inventory and switches to them.
- Rotates view to face the target block, right-clicks via interactBlock + interactItem to waterlog.
- New hotkey "Auto Water Fill" (no default binding); press to toggle on/off.
- Configurable scan radius (0-5 blocks) and operation delay (1-200 ticks).
- Safety: pauses on sneaking, stops on death/world change.

### Auto Material Gathering

Auto material gathering from Litematica schematics using Baritone + ChestTracker.

- Requires Baritone and ChestTracker mods.
- Automatically extracts required materials from nearby cached containers.
- Auto stores to shulker boxes when inventory is full.

## Configuration Categories

The config GUI (opened with `P + C`) has the following tabs:

| Tab | Contents |
|-----|----------|
| **Hotkeys** | General hotkeys: Open Config GUI, Auto Forward, Quick Turn, Toggle Recording, Auto Cache Nearby Containers, Auto Water Fill + Container Whitelist |
| **Route Hotkeys** | Dynamic route hotkeys (one binding per route) |
| **Settings** | Turn angle, Cache Container Delay, Water Fill Scan Radius, Water Fill Operation Delay |
| **Routes** | Route flow system editor (route list / waypoint management / option toggles) |
| **Recording** | Recording & playback (recording management / playback controls / corrupt file detection) |
| **Baritone** | Auto material gathering configuration |

## Dependencies & Compatibility

**Required**

| Mod | Minimum Version |
|-----|-----------------|
| Fabric Loader | 0.17.3+ |
| Fabric API | 0.119.2+ |
| MaLiLib | 0.23.5+ |

**Optional Integrations**

| Mod | Purpose |
|-----|---------|
| ModMenu | Config screen entry (recommended) |
| Litematica | Schematic layer switching / Auto water fill integration (reflection-based, no compile-time dependency) |
| ChestTracker | Auto-cache containers / Material gathering integration (reflection-based, no compile-time dependency) |
| QuickShulker | Quick shulker box opening from inventory (reflection-based, no compile-time dependency) |
| Baritone | Auto material gathering path execution (reflection-based, no compile-time dependency) |

> [!NOTE]
> All integrations use reflection-based invocation with no compile-time dependencies. When a dependency is missing, the corresponding feature silently degrades without affecting normal mod operation.

## Build

```bash
./gradlew build
```

Windows PowerShell:

```powershell
.\gradlew build
```

## License

This project is licensed under the `MIT` License.

## Third-Party Projects

This project depends on, is compatible with, or references several Minecraft client ecosystem projects. Unless explicitly stated otherwise in source files, this repository does not bundle, copy, or redistribute the source code, resources, or binary artifacts of the following third-party projects; their names are used solely for dependency, compatibility, or feature attribution purposes.

- **Fabric API / Fabric Loader**: Used as the Fabric runtime ecosystem. Copyright and license belong to the Fabric project authors.
- **MaLiLib**: Config management, GUI framework, and hotkey system. Copyright and license belong to masa.
- **ModMenu**: Config screen entry. Copyright and license belong to Prospector / TerraformersMC.
- **Litematica**: Schematic render layer switching integration. Copyright and license belong to masa.
- **ChestTracker**: Container cache data integration. Copyright and license belong to the ChestTracker project authors.
- **QuickShulker**: Quick shulker box operation integration. Copyright and license belong to the QuickShulker project authors.

Should there be a need to include third-party source code in the future, license compatibility must be confirmed first, original copyright notices must be preserved in the corresponding files, and any required NOTICE or attribution files must be added according to the license terms. If licenses are incompatible, the code must be re-implemented or removed.

---

<div align="center">Made by Alonediamond</div>
