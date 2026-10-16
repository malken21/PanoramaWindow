# How it works

## World-locked cube faces

The panorama is a **world-axis-aligned** cube map: the six faces look fixed compass directions
(north / east / south / west / up / down), *not* wherever the player is facing. Because of that,
**turning the view never re-renders the world** - the window's fragment shader just rotates the
sampled direction (`uYaw` / `uPitch`) to match where the player is looking. The window is redrawn
at the end of every Minecraft frame (`Minecraft.runTick`), so looking around stays smooth no matter
how slowly the faces themselves refresh.

The faces only need re-rendering when the *scene* can change (the player moves, time passes, ...).
That happens at the end of the client tick (`Minecraft.tick`) - never in the middle of a frame,
because the 26.1+ renderer cannot nest a level render inside another - and only `FacesPerCapture`
faces are rendered per batch, so there is no "six renders in one frame" stall. If the player has
not moved, the sweep is skipped and only redone every two seconds.

Each face is rendered by calling the game's own level renderer with the player temporarily turned
to that face, the hand and block outline hidden, view bobbing off, and the FOV forced by a tiny
mixin (`GameRenderer.getFov`, or `Camera.calculateFov` on 26.x). The texture sampling uses an
explicit mip level computed from the window size, because the UV jump at a face boundary would
otherwise make the GPU pick the smallest mip and draw a line along every seam.

## Two capture modes

The `CaptureMode` setting chooses how a face is captured:

| | `crop` | `resize` |
| --- | --- | --- |
| Render target | the game's own, unchanged | re-created as a square of the face size |
| Face resolution | at most the game window's height | whatever the panorama window needs (up to 8192) |
| Shader mods / Fabulous! | safe - nothing to re-create | they re-create their own targets every capture |

In `crop` mode the level is rendered with a *vertical* FOV of 90° at the game window's aspect ratio,
and the central square is exactly the 90° × 90° face. `auto` uses `crop` whenever a shader pack or
Fabulous! graphics is active, and otherwise switches to `resize` only when the panorama window wants
more pixels than the game window has.

## Shader mods

Shader mods hook the normal level-render call, so the faces are shaded like the game. While a shader
pack is active, three things are handled specially:

- **Render targets** - packs allocate many screen-sized buffers. Changing the game's render-target
  size (as `resize` does) makes them reallocate everything twice per capture, so `crop` is used.
- **State carried between renders** - shader pipelines keep state from the previous render (the
  previous camera for TAA / motion blur, history buffers, ...). Rendering two faces back to back
  makes the second one inherit the first one's state; with old Iris (1.16.5) whole parts of the
  terrain then vanish. So **one face is rendered per batch (`FacesPerCapture` is ignored) and the
  normal view is rendered once right after it** (off-screen; the next game frame overwrites it).
  That also keeps the game's own TAA history clean. Packs also need a few real frames between
  captures to settle (and a few seconds after joining a world) - capturing too densely or too early
  turns whole faces white with fog on some Iris versions - so the batch interval is at least 6 ticks
  (`CaptureInterval` is raised to that) and capturing starts 5 seconds after joining or changing
  dimension. A full sweep then takes about two seconds; turning the view is still instant.
- **God rays and other screen-space effects** - many packs compute light shafts from the sun's
  position on screen and only from what is on screen. At a face edge the computation simply runs
  out of image, so the rays stop at the seam. With `Overscan` the face is rendered wider than 90°
  and only the centre is kept, so every face has context beyond its edge and the rays line up
  across seams. `Overscan = 0` (automatic) means 1.5× (≈ 113°) while a shader pack is active and
  1.0× otherwise. A pack that disables its rays entirely when the sun is behind the camera will
  still show no rays on that face - that is the pack's choice.

Shader-pack detection is reflective and needs no dependency (`ShaderMods`):

| Shader mod | "A shader pack is active" when |
| --- | --- |
| Iris (or anything exposing Iris's API) | `IrisApi.getInstance().isShaderPackInUse()` returns true |
| OptiFine (through OptiFabric) | `net.optifine.Config.isShaders()` returns true |
| Canvas | `grondag.canvas.CanvasMod` is present (always) |

Which of these have actually been tried on a real client is listed under
[Compatibility](../README.md#compatibility).

## GPU-to-GPU copy

On OpenGL, the panorama window **shares Minecraft's GL context**. After each face is rendered, its
square is copied with `glCopyTexSubImage2D` (through a private read framebuffer) straight into a
face texture the window samples - no CPU read-back. Only raw GL is used, and every binding touched is
restored afterwards, so the same code works with every Minecraft renderer from 1.14's fixed-function
pipeline to 26.x's Blaze3D (whose state cache would otherwise go stale).

### Vulkan (26.2+)

Minecraft 26.2 can run on Vulkan. There is no GL context to share then, so each face is read back
through Blaze3D (`copyTextureToBuffer`) and uploaded into the panorama window's own GL context.

### SDL3 (26.3+)

Minecraft 26.3 replaced GLFW with SDL3. The panorama window is then created with SDL3 as well
(`SdlSurface`, compiled against LWJGL's SDL bindings in a separate source set and only loaded when
GLFW is absent). Minecraft's SDL event loop handles *window* events without checking which window
they belong to - closing or resizing the panorama window would quit or resize the game - so a mixin
(`SDLEventHandlerMixin`) drops events that belong to the panorama window.

## Billboards

Billboards - name tags, the score line under a name, fire on a burning entity, XP orbs, thrown items,
fishing bobbers, the conduit eye - are camera-facing quads. A cube face is rendered with the camera
pointing along that face's axis, so vanilla would turn each billboard towards a *different*
direction per face: one on a face boundary would be drawn twice, 90° apart, and tear along the seam.

While capturing (and only then) two kinds of fix are applied:

- **All billboards** are turned to one horizontal direction - the view yaw latched at the start of the
  sweep - on all six faces, so nothing tears at a seam. The window shader tilts them with the view.
- **Name tags** are turned to face the player's position (the same direction in every face), so a tag
  you look at is square-on and readable.

How the billboard direction reaches the renderer changed many times, so each era has its own mixins
(`adapters/bb_*`, `camera_*`, `nametag_*`):

| Minecraft | Billboard direction | Name tag |
| --- | --- | --- |
| 1.14 | `EntityRenderDispatcher.playerRotY/X` | arguments of `GameRenderer.renderNameTagInWorld` |
| 1.15 - 1.19.2 | `EntityRenderDispatcher.overrideCameraOrientation` (`com.mojang.math.Quaternion`) | `cameraOrientation()` inside `EntityRenderer.renderNameTag` |
| 1.19.3 - 1.21.8 | same, with JOML `Quaternionf` (the camera rotation formula changed in 1.21) | same (entity render state from 1.21.2) |
| 1.21.9 - 1.21.11 | `CameraRenderState.orientation`, set in `GameRenderer.extractCamera` | swap `orientation` during `SubmitNodeCollection.submitNameTag` |
| 26.1+ | `CameraRenderState.orientation`, set in `Camera.extractRenderState` | same |

Particles are billboards too, but in older versions they read the `Camera`'s own rotation instead of
the billboard direction above. This mod does not override that rotation, so those particles are left
as vanilla draws them. The affected versions are listed under
[Known limitations](../README.md#known-limitations).

## Spout2

Spout2 shares a DirectX 11 texture between processes. The sender (`core/spout`) follows the
protocol of the [Spout2 SDK](https://github.com/leadedge/Spout2), written in Java:

1. Create a Direct3D 11 device **on the same GPU as the OpenGL context**: DXGI adapters are tried in
   the order *same LUID as OpenGL* (`GL_EXT_memory_object_win32`'s `GL_DEVICE_LUID_EXT`) → same name
   as `GL_RENDERER` → the rest, and the first one that `wglDXOpenDeviceNV` accepts is used. Matching
   by name alone fails with two identical GPUs; on a multi-GPU machine the default adapter often is
   not the GL one.
2. Create a shareable `B8G8R8A8` texture and register it with `WGL_NV_DX_interop2`.
3. Add the sender name to the `SpoutSenderNames` shared memory and write the 280-byte
   `SharedTextureInfo` (share handle, size, format, host path) into shared memory named after the
   sender, both guarded by their `_mutex`.
4. Each frame: take `<name>_SpoutAccessMutex`, lock the interop object, blit the equirectangular
   image in flipped vertically (D3D rows run top-down), unlock, and advance
   `<name>_Count_Semaphore` (the frame counter receivers use).

Win32 / D3D11 / DXGI functions and COM methods are called through the FFI that LWJGL already ships
- dyncall in LWJGL 3.2 (Minecraft 1.14 - 1.18) and libffi in 3.3+ (1.19+). On Windows x64 every
argument travels in a 64-bit slot, so a single "all arguments pointer-sized, 64-bit return" call
shape covers every function used.
