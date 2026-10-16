package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.panorama_window.adapter.mixin.GameRendererAccessor;
import marumasa.panorama_window.adapter.mixin.RenderTargetAccessor;
import marumasa.panorama_window.adapter.mixin.WindowAccessor;
import marumasa.panorama_window.core.Game;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Minecraft 1.16 - 1.16.5 に固有の部分。
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
        return entity.yRot;
    }

    static float xRot(Entity entity) {
        return entity.xRot;
    }

    static void setRotation(Entity entity, float yaw, float pitch) {
        entity.yRot = yaw;
        entity.xRot = pitch;
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
        return mc.options.graphicsMode == GraphicsStatus.FABULOUS;
    }

    static void beginCapture(Minecraft mc, int size) {
        final GameRendererAccessor renderer = (GameRendererAccessor) mc.gameRenderer;
        renderer.panorama_window$setRenderHand(false);
        renderer.panorama_window$setRenderBlockOutline(false);
        bobView = mc.options.bobView;
        mc.options.bobView = false;
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
        mc.gameRenderer.renderLevel(1.0F, 0L, new PoseStack());
        target.unbindWrite();
    }

    static void endCapture(Minecraft mc) {
        final GameRendererAccessor renderer = (GameRendererAccessor) mc.gameRenderer;
        renderer.panorama_window$setRenderHand(true);
        renderer.panorama_window$setRenderBlockOutline(true);
        mc.options.bobView = bobView;
        if (savedWidth > 0) {
            resize(mc, savedWidth, savedHeight);
            savedWidth = 0;
        }
    }

    private static void resize(Minecraft mc, int width, int height) {
        final WindowAccessor window = (WindowAccessor) (Object) mc.getWindow();
        window.panorama_window$setFramebufferWidth(width);
        window.panorama_window$setFramebufferHeight(height);
        mc.getMainRenderTarget().resize(width, height, Minecraft.ON_OSX);
    }

    static int mainColorTexture(Minecraft mc) {
        return ((RenderTargetAccessor) mc.getMainRenderTarget()).panorama_window$getColorTextureId();
    }

    static boolean readMainColor(Minecraft mc, int x, int y, int size, Game.PixelSink sink) {
        return false;
    }
}
