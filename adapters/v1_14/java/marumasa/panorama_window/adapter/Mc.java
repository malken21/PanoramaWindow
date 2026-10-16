package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import marumasa.panorama_window.adapter.mixin.GameRendererAccessor;
import marumasa.panorama_window.adapter.mixin.RenderTargetAccessor;
import marumasa.panorama_window.adapter.mixin.WindowAccessor;
import marumasa.panorama_window.core.Game;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Minecraft 1.14 - 1.14.4 に固有の部分。
 */
final class Mc {

    private static boolean bobView;
    private static int savedWidth;
    private static int savedHeight;

    private Mc() {
    }

    static long windowHandle(Minecraft mc) {
        return mc.window == null ? 0 : mc.window.getWindow();
    }

    static boolean usesOpenGl() {
        return true;
    }

    static double x(Entity entity) {
        return entity.x;
    }

    static double y(Entity entity) {
        return entity.y;
    }

    static double z(Entity entity) {
        return entity.z;
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
        return mc.window.getWidth();
    }

    static int framebufferHeight(Minecraft mc) {
        return mc.window.getHeight();
    }

    static boolean fragileTargets(Minecraft mc) {
        // Fabulous! (描画先を複数使う透明の描き方) は 1.16 から
        return false;
    }

    static void beginCapture(Minecraft mc, int size) {
        final GameRendererAccessor renderer = (GameRendererAccessor) mc.gameRenderer;
        renderer.panorama_window$setRenderHand(false);
        renderer.panorama_window$setRenderBlockOutline(false);
        bobView = mc.options.bobView;
        mc.options.bobView = false;
        if (size > 0) {
            final Window window = mc.window;
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
        final WindowAccessor window = (WindowAccessor) (Object) mc.window;
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
