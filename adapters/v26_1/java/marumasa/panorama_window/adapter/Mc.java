package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.textures.GpuTexture;
import marumasa.panorama_window.core.Game;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Minecraft 26.1 - 26.1.2 に固有の部分。
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
        final RenderTarget target = mc.getMainRenderTarget();
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
        mc.gameRenderer.getMainCamera().enablePanoramicMode();
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
        mc.gameRenderer.update(DeltaTracker.ONE, true);
        mc.gameRenderer.extract(DeltaTracker.ONE, true);
        mc.gameRenderer.renderLevel(DeltaTracker.ONE);
    }

    static void endCapture(Minecraft mc) {
        mc.gameRenderer.setRenderBlockOutline(true);
        mc.gameRenderer.getMainCamera().disablePanoramicMode();
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
        mc.getMainRenderTarget().resize(width, height);
    }

    static int mainColorTexture(Minecraft mc) {
        final GpuTexture color = mc.getMainRenderTarget().getColorTexture();
        return color instanceof GlTexture ? ((GlTexture) color).glId() : -1;
    }

    static boolean readMainColor(Minecraft mc, int x, int y, int size, Game.PixelSink sink) {
        return false;
    }
}
