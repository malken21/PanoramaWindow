# How it works

The panorama is a **world-axis-aligned** cube map: the six faces look fixed compass directions (north / east / south / west / up / down), *not* wherever the player is facing. Because of that, **turning the view never re-renders the world** - the window's fragment shader just rotates the sampled direction (`uYaw` / `uPitch`) to match where the player is looking, horizontally *and* vertically. The window is redrawn every frame (`LevelRenderEvents.END_MAIN`), so looking around stays smooth no matter how slowly the faces themselves refresh.

The faces only need re-rendering when the *scene* changes (the player moves, blocks change, time passes). That capture runs from the client tick - Minecraft 26.1+ splits world rendering into a non-reentrant "extract" / "render" pipeline, so it can't be driven from a world-render event - and only `FacesPerCapture` faces are re-rendered per eligible tick, so there is no periodic "six full world renders in one frame" stall. If the player hasn't moved or turned, the sweep is skipped entirely and only redone every couple of seconds to pick up time of day, mobs and block changes.

The panorama window **shares Minecraft's GL context**, so a rendered face is copied GPU-to-GPU (`copyTextureToTexture`) straight into the texture the window samples - no CPU read-back, no re-upload, even at 8K.

The capture resolution is not a setting - it is derived from the window size (about a quarter of the width per face) so it always matches how big the window is. Resize the window and the next full sweep re-captures at the new resolution. The per-face resolution is capped at the GPU's maximum texture size (and 8192 px); an 8K window (~3240 px/face wanted) is captured at full resolution on any GPU that reports an 8192+ limit.

## Billboards

Billboards - player / mob **name tags**, the score line under a name, fire on a burning entity, XP orbs, thrown items, fishing bobbers, the conduit eye - are camera-facing quads. A cube face is captured with the camera pointing along that face's axis, so vanilla would billboard each of them towards a *different* direction per face: a name tag near a face boundary is then drawn twice, once per face, 90° apart, and tears along the seam; one away from a face centre is skewed.

Two tiny client mixins fix this, and only while the mod is capturing (the normal Minecraft view is untouched):

- `CameraMixin` forces every billboard on all six faces to one fixed horizontal direction - the view yaw latched at the start of the sweep - with zero pitch, so nothing tears at a seam and the window shader tilts them with the view when you look up / down.
- `SubmitNodeCollectionMixin` refines name tags further: each is turned to face the player's actual position (the same in every face), so a name tag you look at is square-on and readable.

