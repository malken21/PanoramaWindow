# PanoramaWindow-NoMixin

A Minecraft Fabric mod that aims to enable real-time rendering of panoramas without the use of mixins.

The configuration is stored in `/config/panorama_window.json` in the game directory.

- Minecraft **26.2**
- [Fabric Loader](https://fabricmc.net/use/installer/) >= 0.19.3
- [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) >= 0.158.0+26.2
- Java 25

![mc-360](.github/assets/mc-360.gif)

## Notes on the 26.2 port

Minecraft 26.1+ ships unobfuscated, so the mod now builds against Mojang's official
mappings instead of Yarn. The renderer was also rewritten: world rendering is now split
into a non-reentrant "extract" / "render" pipeline, so the panorama's six cube faces can
no longer be captured from inside a world-render event. Instead the capture is driven from
the client tick (between frames), mirroring what the vanilla panorama screenshot does, and
the rendered faces are read back through the Blaze3D GPU abstraction. As a result the
panorama window updates at the client tick rate (20 Hz) rather than the full frame rate.

### Building

```bash
./gradlew build
```

### Testing

An automated in-world test is available:

```bash
./gradlew runClientGameTest
```

It creates a world, then rotates the camera, looks around and walks while checking that:

- the six-face capture keeps completing without crashing or stalling,
- the panorama window actually contains a rendered scene (not blank),
- the panorama changes when the camera rotates and when the player moves.

The rendered panorama frames are written to
`build/run/clientGameTest/panorama-test-output/`.
