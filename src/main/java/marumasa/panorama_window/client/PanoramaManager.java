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
 * Renders the six cube faces of the panorama by re-running Minecraft's world renderer once
 * per direction, then asynchronously copies each rendered face back into a CPU {@link ByteBuffer}
 * so it can be uploaded into the mod's separate OpenGL window.
 *
 * <p>Since Minecraft 26.1 the renderer no longer exposes a synchronous {@code renderWorld} call
 * and texture read-back goes through the Blaze3D GPU abstraction, so the pixels arrive one frame
 * later via a fenced task. {@link #hasFreshData()} reports when a full set of six faces is ready.
 */
public class PanoramaManager {

    private static volatile boolean taking = false;

    /** Number of full six-face sets that have finished reading back. Used by the client game test. */
    public static volatile long completedCaptures = 0;

    public static boolean isTaking() {
        return taking;
    }

    private final int cameraSize;
    private final Minecraft client;
    private final ByteBuffer[] buffers;

    private GpuBuffer[] readbackBuffers;
    private volatile int pendingFaces = 0;
    private volatile boolean freshData = false;

    // The client level/player instances are swapped out on a dimension change (and the level
    // renderer reloads); wait a few ticks before re-rendering the world again.
    private Object lastLevel;
    private int settleTicks = 0;

    public PanoramaManager(int cameraSize, Minecraft client) {
        this.client = client;
        this.cameraSize = cameraSize;
        // Six CPU-side RGBA buffers, one per cube face.
        this.buffers = IntStream.range(0, 6)
                .mapToObj(i -> BufferUtils.createByteBuffer(this.cameraSize * this.cameraSize * 4))
                .toArray(ByteBuffer[]::new);
    }

    public ByteBuffer[] getBuffers() {
        return buffers;
    }

    /** True once every face of the most recent capture has been copied back to the CPU. */
    public boolean hasFreshData() {
        return freshData;
    }

    /** Marks the current set of faces as consumed so the next capture can be started. */
    public void consume() {
        freshData = false;
    }

    public void take() {
        if (!RenderSystem.isOnRenderThread()) return;
        // A previous capture is still being read back - don't stomp on those buffers.
        if (pendingFaces > 0) return;

        final LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameRenderer == null) return;

        // Settle after a dimension change / (re)join before poking the world renderer again.
        if (client.level != lastLevel) {
            lastLevel = client.level;
            settleTicks = 30;
        }
        if (settleTicks > 0) {
            settleTicks--;
            return;
        }
        if (!player.isAlive()) return;

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
            pendingFaces = 6;

            camera.enablePanoramicMode();
            window.setWidth(cameraSize);
            window.setHeight(cameraSize);
            target.resize(cameraSize, cameraSize);

            for (int face = 0; face < 6; face++) {
                switch (face) {
                    case 0 -> { player.setYRot(yaw); player.setXRot(0.0F); }
                    case 1 -> { player.setYRot((yaw + 90.0F) % 360.0F); player.setXRot(0.0F); }
                    case 2 -> { player.setYRot((yaw + 180.0F) % 360.0F); player.setXRot(0.0F); }
                    case 3 -> { player.setYRot((yaw - 90.0F) % 360.0F); player.setXRot(0.0F); }
                    case 4 -> { player.setYRot(yaw); player.setXRot(-90.0F); }
                    default -> { player.setYRot(yaw); player.setXRot(90.0F); }
                }

                player.yRotO = player.getYRot();
                player.xRotO = player.getXRot();

                client.gameRenderer.update(DeltaTracker.ONE);
                client.gameRenderer.extract(DeltaTracker.ONE, true);
                client.gameRenderer.renderLevel(DeltaTracker.ONE);

                queueReadback(device, target, face);
            }
        } catch (Exception exception) {
            LOGGER.error("Couldn't take panorama images", exception);
            pendingFaces = 0;
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
            pendingFaces = Math.max(0, pendingFaces - 1);
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
            } catch (Exception e) {
                LOGGER.error("Couldn't read back panorama face {}", index, e);
            } finally {
                int remaining = --pendingFaces;
                if (remaining <= 0) {
                    pendingFaces = 0;
                    freshData = true;
                    completedCaptures++;
                }
            }
        }, 0);
    }
}
