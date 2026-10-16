package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import marumasa.panorama_window.core.Game;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

/**
 * Minecraft 26.2 に固有の部分。
 * 26.2 から Vulkan も選べるので、OpenGL でないときは CPU に読み出して送る。
 */
final class Mc {

    private static boolean bobView;
    private static int savedWidth;
    private static int savedHeight;


    private Mc() {
    }

    static long windowHandle(Minecraft mc) {
        return mc.getWindow() == null ? 0 : mc.getWindow().handle();
    }

    static boolean usesOpenGl() {
        final Minecraft mc = Minecraft.getInstance();
        final RenderTarget target = mc.gameRenderer == null ? null : mc.gameRenderer.mainRenderTarget();
        return target != null && target.getColorTexture() instanceof GlTexture;
    }

    static double x(Entity entity) {
        return entity.getX();
    }

    static double y(Entity entity) {
        return entity.getY();
    }

    static double z(Entity entity) {
        return entity.getZ();
    }

    static float yRot(Entity entity) {
        return entity.getYRot();
    }

    static float xRot(Entity entity) {
        return entity.getXRot();
    }

    static void setRotation(Entity entity, float yaw, float pitch) {
        entity.setYRot(yaw);
        entity.setXRot(pitch);
    }

    static float partialTick(Minecraft mc) {
        return mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }

    static int framebufferWidth(Minecraft mc) {
        return mc.getWindow().getWidth();
    }

    static int framebufferHeight(Minecraft mc) {
        return mc.getWindow().getHeight();
    }

    static boolean fragileTargets(Minecraft mc) {
        return mc.options.improvedTransparency().get();
    }

    static void beginCapture(Minecraft mc, int size) {
        mc.gameRenderer.setRenderBlockOutline(false);
        // パノラマのモード: 手を描かず、霧の色の基準になる向きを固定する (面ごとに空の色がずれない)
        mc.gameRenderer.mainCamera().enablePanoramicMode();
        bobView = mc.options.bobView().get();
        mc.options.bobView().set(false);
        if (size > 0) {
            final Window window = mc.getWindow();
            savedWidth = window.getWidth();
            savedHeight = window.getHeight();
            resize(mc, size, size);
        } else {
            savedWidth = 0;
        }
    }

    static void renderLevel(Minecraft mc) {
        // 26.1 以降は「状態を取り出す」と「描く」が分かれている
        mc.gameRenderer.update(DeltaTracker.ONE);
        mc.gameRenderer.extract(DeltaTracker.ONE, true);
        mc.gameRenderer.renderLevel(DeltaTracker.ONE);
    }

    static void endCapture(Minecraft mc) {
        mc.gameRenderer.setRenderBlockOutline(true);
        mc.gameRenderer.mainCamera().disablePanoramicMode();
        mc.options.bobView().set(bobView);
        if (savedWidth > 0) {
            resize(mc, savedWidth, savedHeight);
            savedWidth = 0;
        }
    }

    private static void resize(Minecraft mc, int width, int height) {
        final Window window = mc.getWindow();
        window.setWidth(width);
        window.setHeight(height);
        mc.gameRenderer.mainRenderTarget().resize(width, height);
    }

    static int mainColorTexture(Minecraft mc) {
        final GpuTexture color = mc.gameRenderer.mainRenderTarget().getColorTexture();
        return color instanceof GlTexture ? ((GlTexture) color).glId() : -1;
    }

    static boolean readMainColor(Minecraft mc, int x, int y, int size, final Game.PixelSink sink) {
        final RenderTarget target = mc.gameRenderer.mainRenderTarget();
        final GpuTexture color = target.getColorTexture();
        if (color == null) return false;
        // 読み出しは後で終わるので、面ごとに別の置き場所を使い、読み終えたら閉じる
        final GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "panorama_window readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) size * size * 4L);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(color, buffer, 0L, () -> {
            try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
                final ByteBuffer src = view.data();
                final ByteBuffer copy = BufferUtils.createByteBuffer(src.remaining());
                copy.put(src).flip();
                sink.accept(copy);
            } finally {
                buffer.close();
            }
        }, 0, x, y, size, size);
        return true;
    }
}
