package marumasa.panorama_window.adapter;

import marumasa.panorama_window.core.Game;
import marumasa.panorama_window.core.ShaderMods;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * core の {@link Game} の実装。バージョンによって違う部分は Mc クラス (世代ごとのフォルダにある) に任せる。
 */
final class GameImpl implements Game {

    private Minecraft mc() {
        return Minecraft.getInstance();
    }

    @Override
    public Path gameDirectory() {
        return mc().gameDirectory.toPath();
    }

    @Override
    public String version() {
        // バージョンの取り方は Minecraft の版によって違うので、Fabric Loader に聞く
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    @Override
    public long windowHandle() {
        final Minecraft mc = mc();
        return mc == null ? 0 : Mc.windowHandle(mc);
    }

    @Override
    public boolean usesOpenGl() {
        return Mc.usesOpenGl();
    }

    @Override
    public boolean inWorld() {
        final Minecraft mc = mc();
        return mc != null && mc.level != null && mc.player != null;
    }

    @Override
    public Object level() {
        return mc().level;
    }

    @Override
    public boolean playerAlive() {
        return mc().player.isAlive();
    }

    @Override
    public boolean paused() {
        return mc().isPaused();
    }

    @Override
    public double playerX() {
        return Mc.x(mc().player);
    }

    @Override
    public double playerY() {
        return Mc.y(mc().player);
    }

    @Override
    public double playerZ() {
        return Mc.z(mc().player);
    }

    @Override
    public float yaw() {
        return Mc.yRot(mc().player);
    }

    @Override
    public float pitch() {
        return Mc.xRot(mc().player);
    }

    @Override
    public float yawO() {
        return mc().player.yRotO;
    }

    @Override
    public float pitchO() {
        return mc().player.xRotO;
    }

    @Override
    public void setRotation(float yaw, float pitch, float yawO, float pitchO) {
        Mc.setRotation(mc().player, yaw, pitch);
        mc().player.yRotO = yawO;
        mc().player.xRotO = pitchO;
    }

    @Override
    public float partialTick() {
        return Mc.partialTick(mc());
    }

    @Override
    public int framebufferWidth() {
        return Mc.framebufferWidth(mc());
    }

    @Override
    public int framebufferHeight() {
        return Mc.framebufferHeight(mc());
    }

    @Override
    public boolean shaderPackActive() {
        return ShaderMods.active();
    }

    @Override
    public boolean fragileTargets() {
        return Mc.fragileTargets(mc());
    }

    @Override
    public void beginCapture(int size) {
        Mc.beginCapture(mc(), size);
    }

    @Override
    public void renderLevel() {
        Mc.renderLevel(mc());
    }

    @Override
    public void endCapture() {
        Mc.endCapture(mc());
    }

    @Override
    public int mainColorTexture() {
        return Mc.mainColorTexture(mc());
    }

    @Override
    public boolean readMainColor(int x, int y, int size, PixelSink sink) {
        return Mc.readMainColor(mc(), x, y, size, sink);
    }

    @Override
    public void openSettings() {
        McUi.setScreen(mc(), new UiScreen());
    }

    @Override
    public void closeSettings() {
        if (McUi.screen(mc()) instanceof UiScreen) McUi.setScreen(mc(), null);
    }

    @Override
    public void quit() {
        mc().stop();
    }
}
