# Resource Pack Optimizer

A mod that significantly speeds up resource pack loading/reloading and removes the red loading screen in favor of a small bar at the top of the screen.

Download on [Modrinth](https://modrinth.com/mod/resource-pack-optimizer).

## Changing packs in a world

![Changing packs in a world: Vanilla vs Resource Pack Optimizer](https://cdn.modrinth.com/data/obeVHS2b/images/d542f6318b6fe496d7d7d79a25e90900c66318ea.gif)

| | Vanilla | Resource Pack Optimizer |
|---|---|---|
| Turn a pack on | 3.06 s | **0.34 s** |
| Turn a pack off | 3.05 s | **0.30 s** |
| Time you can't play | 3.06 s | **0.02 s** |

## Changing packs in the main menu

![Main menu: Vanilla vs Resource Pack Optimizer](https://cdn.modrinth.com/data/obeVHS2b/images/7e882a71f148e7aaadd8e5aaecf3e211230964e0.gif)

| | Vanilla | Resource Pack Optimizer |
|---|---|---|
| Turn a pack on | 3.08 s | **0.41 s** |
| Turn a pack off | 3.09 s | **0.29 s** |

## Joining a server with a resource pack for the first time

![Joining a server for the first time: Vanilla vs Resource Pack Optimizer](https://cdn.modrinth.com/data/obeVHS2b/images/a512a39a37dd25a1f2aa25ea57b6befe0645aaaa.gif)

| | Vanilla | Resource Pack Optimizer |
|---|---|---|
| Join (first time this session) | 3.28 s | **1.91 s** |

## Joining the same server again

![Joining the same server again: Vanilla vs Resource Pack Optimizer](https://cdn.modrinth.com/data/obeVHS2b/images/0d4a144cdc8d0d01f744c141b08d6f33cd736117.gif)

| | Vanilla | Resource Pack Optimizer |
|---|---|---|
| Join again | 3.17 s | **0.94 s** |
| Leave | 3.06 s | **0.36 s** |

<sub>Tested on Minecraft 26.3 with the Prime 32x pack (about 2,600 textures). Each number is the median of 6 to 10 runs, measured from clicking Done or Join until the pack is fully loaded. Server times include joining the world; the server ran on the same PC. i5-13600K, RTX 3060 Ti, NVMe SSD.</sub>

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
