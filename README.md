# PanoramaWindow

A client-side Fabric mod that renders a live 360° panorama of the world around you into a separate
window - and, on Windows, can send it to other apps through **Spout2**.

![mc-360](.github/assets/mc-360.gif)

- **One jar for every supported Minecraft version** (see [Requirements](#requirements)). Fabric
  Loader picks the implementation that matches the running game.
- **Shader mods**: the panorama shows the shaded world (see [Compatibility](#compatibility)).
- **In-game settings screen** (press **O**, rebindable under *Controls → Miscellaneous*). Changes
  apply immediately.
- **Spout2 output** (Windows): the equirectangular panorama is shared GPU-to-GPU with OBS, Resolume,
  TouchDesigner, etc. No native DLL is bundled.

How the capture, the shader-mod handling and Spout2 work is described in
[How it works](docs/how-it-works.md).

## Requirements

| | |
| --- | --- |
| Minecraft | 1.14 - 26.3 (Fabric) |
| Fabric Loader | 0.15.0 or newer |
| Fabric API | not needed |
| Java | whatever your Minecraft version uses |

## Compatibility

| | Status |
| --- | --- |
| Vanilla (OpenGL, and the Vulkan backend of 26.2+) | Tested on real clients - which versions: [Verification results](docs/verification.md). |
| Iris + shader packs | Tested on real clients - which versions and packs: [Verification results](docs/verification.md). |
| OptiFine (through OptiFabric) | Tested on real clients (1.16.5, 1.20.1). OptiFabric 1.14.3 hung at start-up on Fabric Loader 0.20.0 and works on 0.15.11 - use an older Loader with it. |
| Canvas | Tested on real clients (1.18.2, 1.20.1; `canvas_basic` and `canvas_standard` pipelines). Canvas exists for 1.17.1 - 1.20.2 only. |

"Detected" means the mod recognises the shader mod and switches to its shader-pack behaviour
([details](docs/how-it-works.md#shader-mods)).

### Known limitations

- **Particles in 1.14 - 1.21.8** are not turned to a common direction while capturing, so a particle
  on a cube-face boundary can tear along the seam ([why](docs/how-it-works.md#billboards)).
- **Screen-space lens flares** of shader packs appear once per face, as ghost blobs in odd places.
  Turn them off in the pack's settings. God rays and sun glow need `Overscan` to stay continuous
  across seams ([details](docs/how-it-works.md#shader-mods)).

## Settings

Open the settings screen with **O**, or edit `config/panorama_window.json`. The file is rewritten on
every change; unknown keys are ignored and missing keys keep their defaults.

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `Width` / `Height` | `1280` / `720` | 64 - 15360 | Panorama window size. The window is resizable; its last size is written back here on quit. |
| `ShowWindow` | `true` | | Show the panorama window. Turn it off if you only want the Spout2 output. |
| `Borderless` | `false` | | Hide the window's title bar / border (drag anywhere to move it). |
| `PauseWhenHidden` | `true` | | Stop capturing while the window is minimized (capturing continues while Spout2 is on). |
| `CaptureMode` | `auto` | `auto` / `crop` / `resize` | How a face is captured; see [Two capture modes](docs/how-it-works.md#two-capture-modes). |
| `CaptureInterval` | `2` | 1 or more (settings screen: up to 40) | Client ticks between capture batches. Raised while a shader pack is active ([Shader mods](docs/how-it-works.md#shader-mods)). |
| `FacesPerCapture` | `2` | 1 - 6 | Faces re-rendered per batch. Forced to 1 while a shader pack is active ([Shader mods](docs/how-it-works.md#shader-mods)). |
| `Overscan` | `0` | `0`, or 1.0 - 3.0 | Render wider than the 90° face and keep only the centre (e.g. `1.5` ≈ 113° FOV), so screen-space effects such as god rays continue across seams. `0` = automatic ([Shader mods](docs/how-it-works.md#shader-mods)). |
| `SpoutEnabled` | `false` | | Send the panorama via Spout2 (Windows). |
| `SpoutName` | `PanoramaWindow` | | Spout2 sender name (a `_1`, `_2`, ... suffix is added if it is taken). |
| `SpoutWidth` | `3840` | 256 - 8192 | Width of the Spout2 image (rounded down to a multiple of 4); the height is half (2:1 equirectangular). |

## Documentation

- [How it works](docs/how-it-works.md) - capture pipeline, shader-mod handling, billboards, Spout2, Vulkan / SDL3.
- [Development](docs/development.md) - repository layout, building, and the automated real-client checks.
- [Verification results](docs/verification.md) - the latest results of those checks.

## License

MIT. The Spout2 sender follows the protocol of the [Spout2 SDK](https://github.com/leadedge/Spout2)
(BSD-2-Clause); no Spout code or binaries are included. The settings screen's widget toolkit and
the multi-version build layout come from [MarumaSign](https://github.com/TORO-Server/MarumaSign).
