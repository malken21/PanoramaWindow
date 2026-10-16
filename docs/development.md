# Development

## Notes on the 26.2 port

Minecraft 26.1+ ships unobfuscated, so the mod now builds against Mojang's official mappings instead of Yarn.

## Building

```bash
./gradlew build
```

## Testing

An automated in-world test is available:

```bash
./gradlew runClientGameTest
```

It builds a ring of coloured landmark pillars, then rotates the camera, walks, and travels Overworld -> Nether -> End -> Overworld while checking that:

- The face capture keeps completing without crashing or stalling (including across dimension changes, and while switching the GL context every frame)
- The panorama window actually contains a rendered scene (not blank)
- The panorama changes when the camera turns, when the player looks up, when the player moves, and between dimensions
- A named armour stand on a cube-face boundary keeps rendering (its name tag exercises the billboard mixins during capture)
- The player actually lands where the test teleported it (a fall-through would invalidate the captured scene)

The rendered panorama frames are written to `build/run/clientGameTest/panorama-test-output/`.

