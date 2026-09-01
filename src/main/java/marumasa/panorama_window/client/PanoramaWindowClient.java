package marumasa.panorama_window.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.TitleScreen;

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

        // Minecraft 26.1+ splits world rendering into an "extract" and a "render" phase that
        // both run inside a non-reentrant frame graph, so the panorama can no longer be
        // captured from inside a world-render event. Instead we mirror what the vanilla
        // panorama screenshot does and drive the extra passes from the client tick, which
        // runs between frames where re-rendering the level is safe.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (windows == null || panorama == null) return;
            if (client.level == null || client.player == null) {
                windows.renderBlack();
                return;
            }
            // Draw the most recently finished cube-map set (its faces arrive a frame after capture)...
            if (panorama.hasFreshData()) {
                windows.renderShader(panorama.getBuffers());
                panorama.consume();
            }
            // ...then start capturing the next one.
            panorama.take();
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (windows == null || panorama == null) {
                windows = new WindowManager(CONFIG.getWidth(), CONFIG.getHeight(), "Panorama", CONFIG.getCameraSize(), client);
                panorama = new PanoramaManager(CONFIG.getCameraSize(), client);
            } else if (screen instanceof TitleScreen) {
                // The title screen is shown -> paint the panorama window black.
                windows.renderBlack();
            }
        });
    }
}
