# PanoramaWindow

A client-side Fabric mod that renders a live 360° panorama of the world around you into a separate
window - and, on Windows, can send it to other apps through **Spout2**.

![mc-360](.github/assets/mc-360.gif)

- **One jar for every Fabric version from Minecraft 1.14 to 26.3.** Fabric Loader picks the
  implementation that matches the running game. Fabric API is *not* required.
- **Works with shader mods** (Iris / Oculus-style packs, OptiFine through OptiFabric, Canvas): the
  panorama shows the shaded world, and options exist to keep screen-space effects such as god rays
  continuous across the cube-face seams.
- **In-game settings screen** (press **O**, rebindable under *Controls → Miscellaneous*). Changes
  apply immediately.
- **Spout2 output** (Windows): the equirectangular panorama is shared GPU-to-GPU with OBS, Resolume,
  TouchDesigner, etc. Implemented in pure Java on top of the LWJGL that Minecraft already ships -
  no native DLL is bundled.

Turning your view never re-renders the world: the six cube faces are locked to the world axes and
the window's fragment shader just rotates the sampled direction. See
[How it works](docs/how-it-works.md).

## Requirements

| | |
| --- | --- |
| Minecraft | 1.14 - 26.3 (Fabric) |
| Fabric Loader | 0.15.0 or newer |
| Fabric API | not needed |
| Java | whatever your Minecraft version uses |

## Settings

Open the settings screen with **O** (or edit `config/panorama_window.json`; the file is rewritten on
every change and unknown / missing keys are tolerated).

| Key | Default | Meaning |
| --- | --- | --- |
| `Width` / `Height` | `1280` / `720` | Panorama window size. The window is resizable; its last size is written back here on quit. |
| `ShowWindow` | `true` | Show the panorama window. Turn it off if you only want the Spout2 output. |
| `Borderless` | `false` | Hide the window's title bar / border (drag anywhere to move it). |
| `PauseWhenHidden` | `true` | Stop capturing while the window is minimized (capturing continues while Spout2 is on). |
| `CaptureMode` | `auto` | `crop`: keep the game's render target and cut the central square out of a 90° (or wider) render - shader friendly. `resize`: re-create the render target at the face size - can exceed the game window's resolution. `auto`: `crop` with shader packs / Fabulous!, otherwise whichever gives enough resolution. |
| `CaptureInterval` | `2` | Client ticks between capture batches (at least 6 while a shader pack is active). |
| `FacesPerCapture` | `2` | Faces re-rendered per batch (`1`-`6`). Always 1 while a shader pack is active (see How it works). |
| `Overscan` | `0` | Render wider than the 90° face and keep only the centre (e.g. `1.5` ≈ 113° FOV). Screen-space effects such as god rays then have context past the face edge and no longer break at the seams. `0` = automatic (`1.5` while a shader pack is active, else `1.0`). |
| `SpoutEnabled` | `false` | Send the panorama via Spout2 (Windows). |
| `SpoutName` | `PanoramaWindow` | Spout2 sender name (a `_1`, `_2`, ... suffix is added if it is taken). |
| `SpoutWidth` | `3840` | Width of the Spout2 image; the height is half (2:1 equirectangular). |

## Documentation

- [How it works](docs/how-it-works.md) - capture pipeline, shader-mod handling, billboards, Spout2, Vulkan / SDL3.
- [Development](docs/development.md) - repository layout, building, and the automated real-client checks.
- [Verification results](docs/verification.md) - which versions and shader setups have been tested.

## License

MIT. The Spout2 sender follows the protocol of the [Spout2 SDK](https://github.com/leadedge/Spout2)
(BSD-2-Clause); no Spout code or binaries are included. The settings screen's widget toolkit and
the multi-version build layout come from [MarumaSign](https://github.com/TORO-Server/MarumaSign).
