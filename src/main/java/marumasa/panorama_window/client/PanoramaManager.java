package marumasa.panorama_window.client;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.lwjgl.opengl.GL11C;

import static marumasa.panorama_window.PanoramaWindow.LOGGER;

/**
 * Captures the world around the player as six <em>world-axis-aligned</em> cube faces so the mod's
 * separate OpenGL window can show a live 360&deg; panorama.
 *
 * <p>Because the faces are locked to the world (north / east / south / west / up / down) rather
 * than to the player, turning the view no longer needs a re-render - the window shader just
 * rotates the sampled longitude. The world is only re-rendered when its <em>contents</em> change
 * (the player moves, blocks change, time passes), and even then the six passes are spread across
 * several client ticks ({@code FacesPerCapture} at a time) so there is no periodic six-render
 * stall.
 *
 * <p>Each rendered face is copied <em>GPU-to-GPU</em> ({@link
 * com.mojang.blaze3d.systems.CommandEncoder#copyTextureToTexture}) straight into a persistent
 * texture that the panorama window samples directly. The panorama window shares Minecraft's GL
 * context, so there is no CPU read-back and no re-upload.
 */
public class PanoramaManager {

    private static volatile boolean taking = false;

    /** Number of individual faces that have finished capturing. */
    public static volatile long completedFaceReadbacks = 0;
    /** Number of full six-face sweeps that have finished. Used by the client game test. */
    public static volatile long completedCaptures = 0;

    /**
     * View yaw (degrees) latched at the start of the current capture sweep. The billboard mixins
     * ({@code CameraMixin} / {@code SubmitNodeCollectionMixin}) orient billboards to this one
     * direction on every one of the six faces - instead of each face's own axis - so a billboard
     * that lands on a face boundary no longer tears at the seam. The window shader then rotates the
     * whole sphere to the live view, so a billboard captured facing the player still faces the player.
     */
    public static volatile float billboardYawDegrees = 180.0F;

    public static boolean isTaking() {
        return taking;
    }

    // Cube-face capture resolution. Follows the panorama window's size: re-read from
    // windows.faceSize() at each sweep boundary and the face textures reallocated to match.
    private int cameraSize;
    private final int captureInterval;
    private final int facesPerCapture;
    private final Minecraft client;
    private final WindowManager windows;

    // Six persistent GPU textures, one per cube face, sampled directly by the panorama window
    // (shared GL context). A rendered face is blitted into these with copyTextureToTexture.
    private GpuTexture[] faceTextures;
    private final int[] faceGlIds = new int[6];

    // Faces whose texture holds pixels not yet presented by the window.
    private final Object dirtyLock = new Object();
    private int dirtyMask = 0;

    // Round-robin cursor: the next face index to (re)capture.
    private int nextFace = 0;

    // Throttles how often a capture batch is started. Counts eligible ticks since the last batch.
    private int ticksSinceCapture = 0;

    // Idle skip: a world-locked sweep only needs redoing when the scene can change. Standing still
    // in a quiet spot, re-rendering the world six times every CaptureInterval ticks is pure waste.
    private static final int IDLE_RESWEEP_TICKS = 40; // still re-sweep ~every 2s for time / mobs / blocks
    private double lastSweepX = Double.NaN, lastSweepY = Double.NaN, lastSweepZ = Double.NaN;
    private float lastSweepYaw = Float.NaN;
    private int idleTicks = 0;

    // The client level/player instances are swapped out on a dimension change (and the level
    // renderer reloads); wait a few ticks before re-rendering the world again.
    private Object lastLevel;
    private int settleTicks = 0;

    public PanoramaManager(int captureInterval, int facesPerCapture, Minecraft client, WindowManager windows) {
        this.client = client;
        this.windows = windows;
        this.cameraSize = windows.faceSize();
        this.captureInterval = Math.max(1, captureInterval);
        this.facesPerCapture = Math.min(6, Math.max(1, facesPerCapture));
    }

    /** GL texture names of the six face textures (shared context), for the window to bind. */
    public int[] faceGlIds() {
        return faceGlIds;
    }

    /** Edge length (px) of the current face textures - hand this to the window with the ids. */
    public int faceSize() {
        return cameraSize;
    }

    /** True once every face has been captured at least once (the window would otherwise show gaps). */
    public boolean hasFirstSweep() {
        return completedFaceReadbacks >= 6;
    }

    /**
     * Returns the bit mask of faces that have new pixels since the last call and clears it, so the
     * window only re-presents when something actually changed.
     */
    public int drainDirtyFaces() {
        synchronized (dirtyLock) {
            int d = dirtyMask;
            dirtyMask = 0;
            return d;
        }
    }

    /** Applies the fixed world-aligned rotation for a cube face. Face order matches the window shader. */
    private static void applyFaceRotation(LocalPlayer player, int face) {
        switch (face) {
            // yaw 180 = -Z (north), 270 = +X (east), 0 = +Z (south), 90 = -X (west)
            case 0 -> { player.setYRot(180.0F); player.setXRot(0.0F); }   // front  (shader +Z)
            case 1 -> { player.setYRot(270.0F); player.setXRot(0.0F); }   // right  (shader +X)
            case 2 -> { player.setYRot(0.0F);   player.setXRot(0.0F); }   // back   (shader -Z)
            case 3 -> { player.setYRot(90.0F);  player.setXRot(0.0F); }   // left   (shader -X)
            case 4 -> { player.setYRot(180.0F); player.setXRot(-90.0F); } // top    (shader +Y)
            default -> { player.setYRot(180.0F); player.setXRot(90.0F); } // bottom (shader -Y)
        }
    }

    public void take() {
        if (!RenderSystem.isOnRenderThread()) return;

        final LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameRenderer == null) return;

        // Settle after a dimension change / (re)join before poking the world renderer again.
        if (client.level != lastLevel) {
            lastLevel = client.level;
            settleTicks = 30;
            nextFace = 0;
        }
        if (settleTicks > 0) {
            settleTicks--;
            return;
        }
        if (!player.isAlive()) return;
        // While paused the world is frozen, so another capture would only reproduce the last one.
        if (client.isPaused()) return;

        // Only start a new batch every captureInterval ticks.
        if (++ticksSinceCapture < captureInterval) return;
        ticksSinceCapture = 0;

        // At the start of a fresh sweep:
        if (nextFace == 0) {
            // The faces are world-locked, so a sweep only needs redoing when the scene can change.
            // If the player has not moved or turned, re-sweep only every IDLE_RESWEEP_TICKS ticks
            // (to pick up time of day, mobs, block changes) instead of every batch.
            double px = player.getX(), py = player.getY(), pz = player.getZ();
            float pyaw = player.getYRot();
            boolean sceneMayHaveChanged = Double.isNaN(lastSweepX)
                    || Math.abs(px - lastSweepX) > 1.0e-3
                    || Math.abs(py - lastSweepY) > 1.0e-3
                    || Math.abs(pz - lastSweepZ) > 1.0e-3
                    || Math.abs(Mth.wrapDegrees(pyaw - lastSweepYaw)) > 0.05F;
            if (!sceneMayHaveChanged && idleTicks < IDLE_RESWEEP_TICKS) {
                idleTicks += captureInterval;
                return;
            }
            idleTicks = 0;
            lastSweepX = px;
            lastSweepY = py;
            lastSweepZ = pz;
            lastSweepYaw = pyaw;

            // Latch the view yaw so every face of this sweep billboards (name tags etc.)
            // consistently towards it - see billboardYawDegrees / the mixin classes.
            billboardYawDegrees = pyaw;

            // Pick up any window resize: re-render the whole sphere at the resolution the new
            // window size wants.
            int wanted = windows.faceSize();
            if (wanted != cameraSize) {
                cameraSize = wanted;
                reallocateFaceTextures();
            }
        }

        final int count = Math.min(facesPerCapture, 6);
        final Window window = client.getWindow();
        final RenderTarget target = client.gameRenderer.mainRenderTarget();
        final Camera camera = client.gameRenderer.mainCamera();
        final GpuDevice device = RenderSystem.getDevice();

        final int originalWidth = window.getWidth();
        final int originalHeight = window.getHeight();
        final float yaw = player.getYRot();
        final float pitch = player.getXRot();
        final float prevYaw = player.yRotO;
        final float prevPitch = player.xRotO;

        taking = true;
        try {
            client.gameRenderer.setRenderBlockOutline(false);
            ensureFaceTextures(device, target);

            camera.enablePanoramicMode();
            window.setWidth(cameraSize);
            window.setHeight(cameraSize);
            target.resize(cameraSize, cameraSize);

            for (int i = 0; i < count; i++) {
                final int face = (nextFace + i) % 6;
                applyFaceRotation(player, face);
                player.yRotO = player.getYRot();
                player.xRotO = player.getXRot();

                client.gameRenderer.update(DeltaTracker.ONE);
                client.gameRenderer.extract(DeltaTracker.ONE, true);
                client.gameRenderer.renderLevel(DeltaTracker.ONE);

                copyFace(device, target, face);
            }
        } catch (Exception exception) {
            LOGGER.error("Couldn't take panorama images", exception);
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = prevYaw;
            player.xRotO = prevPitch;
            client.gameRenderer.setRenderBlockOutline(true);
            window.setWidth(originalWidth);
            window.setHeight(originalHeight);
            target.resize(originalWidth, originalHeight);
            camera.disablePanoramicMode();
            taking = false;
        }

        // Make the GPU-to-GPU copies visible to the panorama window's (shared) context before it
        // next binds these textures. glFlush, not glFinish - it does not stall waiting for the GPU.
        GL11C.glFlush();

        nextFace = (nextFace + count) % 6;
    }

    /**
     * (Re)creates the six face textures at the current {@link #cameraSize}, closing any previous
     * set. Called at a sweep boundary after the window changed size. Pending dirty bits are cleared:
     * those pixels are the old size - the faces keep their current texture until this sweep
     * re-captures them.
     */
    private void reallocateFaceTextures() {
        closeFaceTextures();
        synchronized (dirtyLock) {
            dirtyMask = 0;
        }
    }

    private void ensureFaceTextures(GpuDevice device, RenderTarget target) {
        if (faceTextures != null) return;
        // Match the render target's colour format so copyTextureToTexture accepts the blit.
        var format = target.getColorTexture().getFormat();
        faceTextures = new GpuTexture[6];
        for (int i = 0; i < 6; i++) {
            GpuTexture tex = device.createTexture(
                    () -> "panorama_window face",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    format, cameraSize, cameraSize, 1, 1
            );
            faceTextures[i] = tex;
            faceGlIds[i] = ((GlTexture) tex).glId();
        }
    }

    private void closeFaceTextures() {
        if (faceTextures == null) return;
        for (GpuTexture tex : faceTextures) {
            if (tex != null) tex.close();
        }
        faceTextures = null;
    }

    private void copyFace(GpuDevice device, RenderTarget target, int face) {
        GpuTexture color = target.getColorTexture();
        if (color == null) return;
        device.createCommandEncoder().copyTextureToTexture(
                color, faceTextures[face], 0, 0, 0, 0, 0, cameraSize, cameraSize);
        synchronized (dirtyLock) {
            dirtyMask |= (1 << face);
        }
        if ((++completedFaceReadbacks % 6) == 0) {
            completedCaptures++;
        }
    }
}
