# Resource Pack Optimizer

A mod that significantly speeds up resource pack loading/reloading and removes the red loading screen in favor of a small bar at the top of the screen.

Download on [Modrinth](https://modrinth.com/mod/resource-pack-optimizer).

## Changing packs in a world

![Changing packs in a world: Vanilla vs Resource Pack Optimizer](https://cdn.modrinth.com/data/obeVHS2b/images/380b169855f58eb2cb17ca1ea0a07734ab00643c.gif)

| | Vanilla | Resource Pack Optimizer |
|---|---|---|
| Turn a pack on | 3.04 s | **0.23 s** |
| Turn a pack off | 3.06 s | **0.21 s** |
| Time you can't play | 3.05 s | **0.02 s** |

## Changing packs in the main menu

![Main menu: Vanilla vs Resource Pack Optimizer](https://cdn.modrinth.com/data/obeVHS2b/images/bceb594a6dfc77e454407fbfa60921b47cdebcdc.gif)

| | Vanilla | Resource Pack Optimizer |
|---|---|---|
| Turn a pack on | 3.08 s | **0.30 s** |
| Turn a pack off | 3.08 s | **0.24 s** |

## Joining a server with a resource pack for the first time

![Joining a server for the first time: Vanilla vs Resource Pack Optimizer](https://cdn.modrinth.com/data/obeVHS2b/images/6a325b5bb68ed08d8000408e73be66e4943df0c1.gif)

| | Vanilla | Resource Pack Optimizer |
|---|---|---|
| Join (first time this session) | 3.32 s | **2.07 s** |

## Joining the same server again

![Joining the same server again: Vanilla vs Resource Pack Optimizer](https://cdn.modrinth.com/data/obeVHS2b/images/f5d00f8802d3c7703caea44d71557deeb084deac.gif)

| | Vanilla | Resource Pack Optimizer |
|---|---|---|
| Join again | 3.19 s | **0.92 s** |
| Leave | 3.06 s | **0.34 s** |

<sub>Tested with Resource Pack Optimizer 1.4.0 on Minecraft 26.3 with the Prime 32x pack (about 2,600 textures). Each number is the median of 6 to 18 runs, measured from clicking Done or Join until the pack is fully loaded. Server times include joining the world; the server ran on the same PC. i5-13600K, RTX 3060 Ti, NVMe SSD.</sub>

## What it does

- Reloads happen in the background with a small progress bar at the top, so you can keep moving, looking around and using menus
- No forced 1 second minimum or 2 second fade on the loading screen
- Only reloads what actually changed: textures, models, fonts, shaders, sounds and chunk rebuilds are kept when their files didn't change
- Parsed models are kept when only a texture atlas changed
- Chunks keep drawing with the old textures until the rebuilt ones are ready, so they no longer disappear and fade back in after a texture change
- Texture atlases are built off the main thread, with tiles built in parallel, and uploaded over several frames, so there's no freeze
- Mipmaps are generated on all CPU cores
- Decoded textures and mipmaps are cached in memory and on disk, so switching back to a pack or restarting the game skips decoding files that didn't change
- Compiled shaders are cached on disk between restarts, and identical shaders are only compiled once
- Zip packs are indexed, and vanilla assets and pack info are cached, so looking up files is much faster
- Languages are loaded in the background, and changing language only reloads translations
- Changing mipmap levels only rebuilds textures and models
- The audio device stays open across reloads, and sounds keep playing when no sound files changed
- Server pack checks are cached for the session
- Works alongside Fabric API, Sodium, Sodium Extra and Iris without turning its speedups off
- Can be turned on and off from Mod Menu without restarting the game
