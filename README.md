# PanoramaWindow-NoMixin

A Minecraft Fabric mod that aims to enable real-time rendering of panoramas without the use of mixins.

The configuration is stored in `/config/panorama_window.json` in the game directory:

| Key | Default | Meaning |
| --- | --- | --- |
| `CameraSize` | `512` | Resolution (px) of each of the six captured cube faces. Lower values cut GPU cost sharply. |
| `Width` / `Height` | `1280` / `720` | Initial size of the panorama window. The window is resizable; the shader pass re-fits to the framebuffer. |
| `Borderless` | `false` | Hide the window's title bar / border. |
| `CaptureInterval` | `2` | Client ticks between capture batches. Only limits how often the world is re-rendered to refresh the (world-locked) faces - the window itself still redraws every frame. |
| `FacesPerCapture` | `2` | How many of the six faces to re-render per batch (`1`..`6`). Lower spreads the GPU cost over more ticks; `6` restores the old "re-render everything at once" burst. |
| `PauseWhenHidden` | `true` | Stop capturing while the panorama window is minimized. |

- Minecraft **26.2**
- [Fabric Loader](https://fabricmc.net/use/installer/) >= 0.19.3
- [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) >= 0.158.0+26.2
- Java 25

![mc-360](.github/assets/mc-360.gif)

## How it works

The panorama is a **world-axis-aligned** cube map: the six faces look fixed compass
directions (north / east / south / west / up / down), *not* wherever the player is facing.
Because of that, **turning the view never re-renders the world** - the window's fragment
shader just rotates the sampled direction (`uYaw` / `uPitch`) to match where the player is
looking, horizontally *and* vertically. The window is redrawn every frame
(`LevelRenderEvents.END_MAIN`), so looking around stays smooth no matter how slowly the faces
themselves refresh.

The faces only need re-rendering when the *scene* changes (the player moves, blocks change,
time passes). That capture runs from the client tick - Minecraft 26.1+ splits world
rendering into a non-reentrant "extract" / "render" pipeline, so it can't be driven from a
world-render event - and only `FacesPerCapture` faces are re-rendered per eligible tick, so
there is no periodic "six full world renders in one frame" stall. Rendered faces are read
back through the Blaze3D GPU abstraction one frame later and each face is uploaded to the
window's textures only when it actually changes.

## Notes on the 26.2 port

Minecraft 26.1+ ships unobfuscated, so the mod now builds against Mojang's official
mappings instead of Yarn.

### Building

```bash
./gradlew build
```

### Testing

An automated in-world test is available:

```bash
./gradlew runClientGameTest
```

It builds a ring of coloured landmark pillars, then rotates the camera, walks, and travels
Overworld -> Nether -> End -> Overworld while checking that:

- the face capture keeps completing without crashing or stalling (including across
  dimension changes, and while switching the GL context every frame),
- the panorama window actually contains a rendered scene (not blank),
- the panorama changes when the camera turns, when the player looks up, when the player moves,
  and between dimensions,
- the player actually lands where the test teleported it (a fall-through would invalidate the
  captured scene).

The rendered panorama frames are written to
`build/run/clientGameTest/panorama-test-output/`.
