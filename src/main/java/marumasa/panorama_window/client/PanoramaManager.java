package marumasa.panorama_window.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.stream.IntStream;

import static marumasa.panorama_window.PanoramaWindow.LOGGER;

/**
 * Captures the world around the player as six <em>world-axis-aligned</em> cube faces and copies
 * each face back into a CPU {@link ByteBuffer} for the mod's separate OpenGL window.
 *
 * <p>Because the faces are locked to the world (north / east / south / west / up / down) rather
 * than to the player, turning the view no longer needs a re-render - the window shader just
 * rotates the sampled longitude. The world is only re-rendered when its <em>contents</em> change
 * (the player moves, blocks change, time passes), and even then the six passes are spread across
 * several client ticks ({@code FacesPerCapture} at a time) so there is no periodic six-render
 * stall.
 *
 * <p>Since Minecraft 26.1 texture read-back goes through the Blaze3D GPU abstraction, so the
 * pixels for a face arrive one frame later via a fenced task; {@link #drainDirtyFaces()} reports
 * which faces have new data waiting to be uploaded.
 */
public class PanoramaManager {

    private static volatile boolean taking = false;

    /** Number of individual faces that have finished reading back. */
    public static volatile long completedFaceReadbacks = 0;
    /** Number of full six-face sweeps that have finished reading back. Used by the client game test. */
    public static volatile long completedCaptures = 0;

    public static boolean isTaking() {
        return taking;
    }

    private final int cameraSize;
    private final int captureInterval;
    private final int facesPerCapture;
    private final Minecraft client;
    private final ByteBuffer[] buffers;

    private GpuBuffer[] readbackBuffers;
    private volatile int inFlight = 0;

    // Faces whose CPU buffer holds pixels not yet uploaded to the window textures.
    private final Object dirtyLock = new Object();
    private int dirtyMask = 0;

    // Round-robin cursor: the next face index to (re)capture.
    private int nextFace = 0;

    // Throttles how often a capture batch is started. Counts eligible ticks since the last batch.
    private int ticksSinceCapture = 0;

    // The client level/player instances are swapped out on a dimension change (and the level
    // renderer reloads); wait a few ticks before re-rendering the world again.
    private Object lastLevel;
    private int settleTicks = 0;

    public PanoramaManager(int cameraSize, int captureInterval, int facesPerCapture, Minecraft client) {
        this.client = client;
        this.cameraSize = cameraSize;
        this.captureInterval = Math.max(1, captureInterval);
        this.facesPerCapture = Math.min(6, Math.max(1, facesPerCapture));
        // Six CPU-side RGBA buffers, one per cube face.
        this.buffers = IntStream.range(0, 6)
                .mapToObj(i -> BufferUtils.createByteBuffer(this.cameraSize * this.cameraSize * 4))
                .toArray(ByteBuffer[]::new);
    }

    public ByteBuffer[] getBuffers() {
        return buffers;
    }

    /** True once every face has been read back at least once (the window would otherwise show gaps). */
    public boolean hasFirstSweep() {
        return completedFaceReadbacks >= 6;
    }

    /**
     * Returns the bit mask of faces that have new pixels since the last call and clears it, so the
     * window only re-uploads textures that actually changed.
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
        // A previous batch is still being read back - don't stomp on those buffers.
        if (inFlight > 0) return;

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
            ensureReadbackBuffers(device);
            inFlight = count;

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

                queueReadback(device, target, face);
            }
        } catch (Exception exception) {
            LOGGER.error("Couldn't take panorama images", exception);
            inFlight = 0;
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

        nextFace = (nextFace + count) % 6;
    }

    private void ensureReadbackBuffers(GpuDevice device) {
        if (readbackBuffers != null) return;
        long size = (long) cameraSize * cameraSize * 4L;
        readbackBuffers = new GpuBuffer[6];
        for (int i = 0; i < 6; i++) {
            readbackBuffers[i] = device.createBuffer(
                    () -> "panorama_window readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                    size
            );
        }
    }

    private void queueReadback(GpuDevice device, RenderTarget target, int face) {
        GpuTexture color = target.getColorTexture();
        if (color == null) {
            inFlight = Math.max(0, inFlight - 1);
            return;
        }

        final GpuBuffer readback = readbackBuffers[face];
        final int index = face;

        device.createCommandEncoder().copyTextureToBuffer(color, readback, 0L, () -> {
            try (GpuBufferSlice.MappedView view = readback.map(true, false)) {
                ByteBuffer src = view.data();
                ByteBuffer dst = buffers[index];
                dst.clear();
                int count = Math.min(src.remaining(), dst.remaining());
                int srcLimit = src.limit();
                src.limit(src.position() + count);
                dst.put(src);
                src.limit(srcLimit);
                dst.flip();
                synchronized (dirtyLock) {
                    dirtyMask |= (1 << index);
                }
            } catch (Exception e) {
                LOGGER.error("Couldn't read back panorama face {}", index, e);
            } finally {
                inFlight = Math.max(0, inFlight - 1);
                if ((++completedFaceReadbacks % 6) == 0) {
                    completedCaptures++;
                }
            }
        }, 0);
    }
}
