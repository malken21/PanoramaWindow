# Development

Every supported Minecraft version range is a separate build target, and all of them are packed into
one jar. [`targets.json`](../targets.json) is the list of targets: which Minecraft versions each one
covers, and which adapter layers each era stacks.

## Layout

| Folder | Contents |
| --- | --- |
| `core` | Everything that does not touch Minecraft: capture scheduling, the panorama window (GLFW / SDL3), the GL face copy, the settings UI toolkit (ported from MarumaSign), Spout2, config, translations. Compiled for Java 8 against the oldest bundled libraries (LWJGL 3.2.1, Gson 2.8.0). Two extra source sets hold code that needs newer LWJGL APIs and is only loaded when they exist: `ffi33` (libffi, LWJGL 3.3+) and `sdl` (SDL3, Minecraft 26.3+). |
| `adapters` | Minecraft-version-specific code, in small layers (stacked per era as `targets.json` says). All layers use the package `marumasa.panorama_window.adapter`. |
| `dist` | Builds `PanoramaWindow-<version>.jar`: `core` plus one nested jar per target (Fabric's jar-in-jar picks the one whose `minecraft` dependency matches). |
| `tools` | Linkage check, real-client verification, UI layer generator. |

Adapter layers:

| Layer | Purpose |
| --- | --- |
| `common` | Fabric entry point, `MinecraftMixin` (tick / frame / stop hooks), `GameImpl`, settings key handling. |
| `v*` | `Mc`: how to render a level, resize the render target, read the colour texture, ... for that era. |
| `fov_*` | FOV override mixin (`getFov` returning `double` / `float`, or `Camera.calculateFov`). |
| `acc_1_14` | Accessors for fields that have no setters before 1.17. |
| `bb_*`, `camera_*`, `nametag_*` | Billboard / name-tag mixins (see [How it works](how-it-works.md#billboards)). |
| `sdl_events` | 26.3+: keep SDL window events of the panorama window away from Minecraft. |
| `ui_*` | Settings screen: texture upload + screen drawing per era. Generated from MarumaSign by `python tools/gen_ui_adapters.py` (the output is committed). |

Mixin annotations are remapped straight into the bytecode (`mixinRemap { disableRefmap() }`), so the
nested jars need no refmap.

## Building

```bash
./gradlew :dist:assemble
```

The jar is written to `dist/build/libs`. The first build downloads and remaps every supported
Minecraft version, which takes a while. To iterate on a few targets only:

```bash
./gradlew -PpanoramaOnly=mc1_16_5,mc26_2 :dist:assemble
```

## Checks

```bash
# Every nested jar against every Minecraft version it claims to support (no game launch):
# referenced classes / methods / fields and mixin targets must exist in that exact version.
python tools/check_linkage.py

# Spout2 sender + a Java receiver, without Minecraft (LWJGL 3.3 / libffi by default)
./gradlew :core:spoutCheck
./gradlew :core:spoutCheck -PspoutLwjgl=3.2.2   # LWJGL 3.2 / dyncall path

# Real clients: starts a vanilla server, joins with Fabric + the mod, and runs the built-in self test
python tools/verify/matrix.py                    # representative version of every era
python tools/verify/matrix.py 1.20.1 26.3        # chosen versions
python tools/verify/matrix.py --shaders          # with Iris + a shader pack (BSL by default)
python tools/verify/matrix.py --shaders --shader-pack complementary-reimagined 1.21.1
python tools/verify/matrix.py --option 'preferredGraphicsBackend:"vulkan"' 26.2   # Vulkan backend
```

### The real-client self test

The self test (`-Dpanorama_window.selftest=<dir>`) waits for several full sweeps, saves the panorama
window, turns 90° and looks up (saving each time), opens the settings screen (the script captures the
Minecraft window), turns Spout2 on and checks that the sender is registered, then quits.

A run is **OK** when all of these hold:

- the panorama images contain a scene and change with the view;
- the four horizontal faces show the ground (no missing terrain; checked on OpenGL only);
- the settings screen was visible;
- the Spout2 sender was registered (Windows only);
- the log has no mod-related warnings, OpenGL errors or mixin failures.

Results go to `build/verify-summary.json` (or the file given with `--summary`). Runs share the
client / server cache with MarumaSign's checks (`~/.marumasign-verify`).

`python tools/verify/report.py` turns `build/verify-summary.json`, `verify-shaders.json`,
`verify-complementary.json` and `verify-vulkan.json` into [verification.md](verification.md). That
file is generated - edit `report.py`, not the Markdown.
