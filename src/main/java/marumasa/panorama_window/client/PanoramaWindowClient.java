package marumasa.panorama_window.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.util.Mth;

import java.nio.file.Path;

import static marumasa.panorama_window.PanoramaWindow.CONFIG;

public class PanoramaWindowClient implements ClientModInitializer {

    static WindowManager windows;
    static PanoramaManager panorama;

    /** True once the panorama window and capture manager are both live. */
    public static boolean isReady() {
        return windows != null && panorama != null;
    }

    /** Asks the mod to save the next panorama-window frame to {@code path} (used by the client game test). */
    public static void requestPanoramaScreenshot(Path path) {
        if (windows != null) {
            windows.requestCapture(path);
        }
    }

    @Override
    public void onInitializeClient() {

        // Capturing the six cube faces re-renders the world, which the 26.1 frame graph only allows
        // between frames - so it is driven from the client tick. The faces are world-axis-aligned and
        // only a few are refreshed per eligible tick (see PanoramaManager), so there is no periodic
        // six-render stall and the capture rate no longer limits how smoothly the view can turn.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (windows == null || panorama == null) return;
            if (client.level == null || client.player == null) {
                windows.renderBlack();
                return;
            }
            if (CONFIG.isPauseWhenHidden() && windows.isMinimized()) return;
            panorama.take();
            // Fallback in case END_MAIN doesn't run this tick; the per-frame hook below is what
            // actually keeps turning smooth. renderShader() no-ops when nothing has changed.
            present(client);
        });

        // Presenting the panorama window is cheap - upload only the faces that changed, then one
        // full-screen pass that rotates the sampled longitude to the current view yaw. Doing it every
        // rendered frame keeps turning smooth even though the faces themselves refresh slowly.
        LevelRenderEvents.END_MAIN.register(context -> present(Minecraft.getInstance()));

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (windows == null || panorama == null) {
                windows = new WindowManager(CONFIG.getWidth(), CONFIG.getHeight(), "Panorama", CONFIG.getCameraSize(), client);
                panorama = new PanoramaManager(CONFIG.getCameraSize(), CONFIG.getCaptureInterval(), CONFIG.getFacesPerCapture(), client);
            } else if (screen instanceof TitleScreen) {
                // The title screen is shown -> paint the panorama window black.
                windows.renderBlack();
            }
        });
    }

    private static void present(Minecraft client) {
        if (windows == null || panorama == null) return;
        // take() re-renders the level six times from the client tick, and each of those renderLevel()
        // calls fires LevelRenderEvents.END_MAIN again. Skip those: the player is mid-rotation to a
        // cube face, and switching the GL context inside a capture pass is asking for trouble.
        if (PanoramaManager.isTaking()) return;
        if (client.level == null || client.player == null) return;
        if (CONFIG.isPauseWhenHidden() && windows.isMinimized()) return;
        // Hold the window black until every face has been captured once, otherwise it shows gaps.
        if (!panorama.hasFirstSweep()) {
            windows.renderBlack();
            return;
        }
        // Use the body yaw (getYRot), not getViewYRot(): on a LivingEntity the latter is the head
        // yaw, which setYRot()/teleports don't update. The capture (PanoramaManager) also rotates by
        // getYRot(), so this matches it. Interpolate between ticks for a smooth turn, but only when
        // yRotO looks valid - it lags for a tick or two after setYRot()/a teleport.
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        float yaw = client.player.getYRot();
        float perTickTurn = Mth.wrapDegrees(yaw - client.player.yRotO);
        float yawDegrees = Math.abs(perTickTurn) < 30.0F ? yaw - perTickTurn * (1.0F - partialTick) : yaw;
        // Face 0 ("front") is captured looking north (yaw 180); shift so a north-facing view is centred.
        float yawRadians = (float) Math.toRadians(yawDegrees - 180.0F);
        // Same tick interpolation for pitch. The faces cover the full sphere (top / bottom included),
        // so following the player's pitch is just another rotation in the window shader - no re-render.
        float pitch = client.player.getXRot();
        float perTickPitch = pitch - client.player.xRotO;
        float pitchDegrees = Math.abs(perTickPitch) < 30.0F ? pitch - perTickPitch * (1.0F - partialTick) : pitch;
        float pitchRadians = (float) Math.toRadians(pitchDegrees);
        windows.renderShader(panorama.getBuffers(), yawRadians, pitchRadians, panorama.drainDirtyFaces());
    }
}
