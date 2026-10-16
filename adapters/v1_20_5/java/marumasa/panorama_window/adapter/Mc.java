package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import marumasa.panorama_window.core.Game;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Minecraft 1.20.5 - 1.20.6 に固有の部分。
 */
final class Mc {

    private static boolean bobView;
    private static int savedWidth;
    private static int savedHeight;

    private Mc() {
    }

    static long windowHandle(Minecraft mc) {
        return mc.getWindow() == null ? 0 : mc.getWindow().getWindow();
    }

    static boolean usesOpenGl() {
        return true;
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
        return mc.getFrameTime();
    }

    static int framebufferWidth(Minecraft mc) {
        return mc.getWindow().getWidth();
    }

    static int framebufferHeight(Minecraft mc) {
        return mc.getWindow().getHeight();
    }

    static boolean fragileTargets(Minecraft mc) {
        return mc.options.graphicsMode().get() == GraphicsStatus.FABULOUS;
    }

    static void beginCapture(Minecraft mc, int size) {
        mc.gameRenderer.setRenderHand(false);
        mc.gameRenderer.setRenderBlockOutline(false);
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
        final RenderTarget target = mc.getMainRenderTarget();
        target.bindWrite(true);
        mc.gameRenderer.renderLevel(1.0F, 0L);
        target.unbindWrite();
    }

    static void endCapture(Minecraft mc) {
        mc.gameRenderer.setRenderHand(true);
        mc.gameRenderer.setRenderBlockOutline(true);
        mc.options.bobView().set(bobView);
        if (savedWidth > 0) {
            resize(mc, savedWidth, savedHeight);
            savedWidth = 0;
        }
    }

    private static void resize(Minecraft mc, int width, int height) {
        mc.getWindow().setWidth(width);
        mc.getWindow().setHeight(height);
        mc.getMainRenderTarget().resize(width, height, Minecraft.ON_OSX);
    }

    static int mainColorTexture(Minecraft mc) {
        return mc.getMainRenderTarget().getColorTextureId();
    }

    static boolean readMainColor(Minecraft mc, int x, int y, int size, Game.PixelSink sink) {
        return false;
    }
}
