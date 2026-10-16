# PanoramaWindow

A Minecraft Fabric mod that renders a live 360° panorama into a separate window. Turning the view
never re-renders the world - the six world-locked cube faces are captured GPU-side and a fragment
shader rotates the sampled direction to follow the player. Two small client-side mixins
(`CameraMixin` / `SubmitNodeCollectionMixin`) fix billboard orientation in the panorama - see
[Billboards in docs](docs/how-it-works.md#billboards).

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

## Documentation

For detailed technical explanations of the rendering pipeline, context sharing, and billboard orientation:
- [How it works & Architecture](docs/how-it-works.md)
- [How it works & Architecture](docs/how-it-works.md): Detailed technical explanations of the rendering pipeline, context sharing, and billboard orientation.
- [Development](docs/development.md): Build instructions, automated testing procedures, and port notes.


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
