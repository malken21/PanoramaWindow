# PanoramaWindow-NoMixin

A Minecraft Fabric mod that renders a live 360° panorama into a separate window. It was built to
avoid mixins; it now carries a single small client-side mixin (`CameraMixin` /
`SubmitNodeCollectionMixin`) purely to fix billboard orientation in the panorama - see
[Billboards](#billboards).

The configuration is stored in `/config/panorama_window.json` in the game directory:

| Key | Default | Meaning |
| --- | --- | --- |
| `Width` / `Height` | `1280` / `720` | Panorama window size. The window is resizable; whatever size you leave it at is written back here on quit. |
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

The capture resolution is not a setting - it is derived from the window size (about a quarter
of the width per face) so it always matches how big the window is. Resize the window and the
next full sweep re-captures at the new resolution. The per-face resolution is capped at the
GPU's maximum texture size (and 8192 px); an 8K window (~3240 px/face wanted) is captured at
full resolution on any GPU that reports an 8192+ limit.

## Billboards

Billboards - player / mob **name tags**, the score line under a name, fire on a burning entity,
XP orbs, thrown items, fishing bobbers, the conduit eye - are camera-facing quads. A cube face is
captured with the camera pointing along that face's axis, so vanilla would billboard each of them
towards a *different* direction per face: a name tag near a face boundary is then drawn twice, once
per face, 90° apart, and tears along the seam; one away from a face centre is skewed.

Two tiny client mixins fix this, and only while the mod is capturing (the normal Minecraft view is
untouched):

- `CameraMixin` forces every billboard on all six faces to one fixed horizontal direction - the
  view yaw latched at the start of the sweep - with zero pitch, so nothing tears at a seam and the
  window shader tilts them with the view when you look up / down.
- `SubmitNodeCollectionMixin` refines name tags further: each is turned to face the player's actual
  position (the same in every face), so a name tag you look at is square-on and readable.

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
- a named armour stand on a cube-face boundary keeps rendering (its name tag exercises the
  billboard mixins during capture),
- the player actually lands where the test teleported it (a fall-through would invalidate the
  captured scene).

The rendered panorama frames are written to
`build/run/clientGameTest/panorama-test-output/`.
