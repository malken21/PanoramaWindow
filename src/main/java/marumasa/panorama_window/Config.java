package marumasa.panorama_window;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import static marumasa.panorama_window.PanoramaWindow.LOGGER;
import static marumasa.panorama_window.PanoramaWindow.MOD_ID;

/**
 * Mod configuration, stored as {@code config/panorama_window.json}.
 *
 * <p>Loading is deliberately lenient: unknown keys are ignored, a missing key keeps its default
 * (so a file written by an older version still works), and a corrupt / unreadable file falls back
 * to defaults and is rewritten instead of crashing the game.
 *
 * <p>The cube-face capture resolution is <em>not</em> configurable - it is derived from the
 * panorama window's current size (see {@code WindowManager.faceSizeFor}) so it always matches how
 * big the window actually is.
 */
public class Config {

    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().normalize().resolve(MOD_ID + ".json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Size of the panorama window in pixels. The window is resizable; its last size is written
     *  back here on quit, so it reopens where you left it. */
    private int Width = 1280;
    private int Height = 720;

    /** Hide the window's title bar / border. */
    private boolean Borderless = false;

    /** Minimum client ticks between two capture batches. The window still redraws every frame; this
     *  only limits how often the world is re-rendered to refresh the (world-locked) cube faces. */
    private int CaptureInterval = 2;

    /** How many of the six cube faces are re-rendered per capture batch (1..6). Lower spreads the
     *  cost over more ticks (smoother frame pacing, slower full refresh); 6 restores the old burst. */
    private int FacesPerCapture = 2;

    /** Skip capturing (and just hold the last frame) while the panorama window is minimized. */
    private boolean PauseWhenHidden = true;

    public Config() {
        if (Files.exists(PATH)) {
            load();
        }
        save();
    }

    public int getWidth() {
        return Width;
    }

    public int getHeight() {
        return Height;
    }

    public boolean isBorderless() {
        return Borderless;
    }

    public int getCaptureInterval() {
        return Math.max(1, CaptureInterval);
    }

    public int getFacesPerCapture() {
        return Math.min(6, Math.max(1, FacesPerCapture));
    }

    public boolean isPauseWhenHidden() {
        return PauseWhenHidden;
    }

    /** Records the panorama window's current size so it reopens the same size next launch. */
    public void setWindowSize(int width, int height) {
        int w = clampDimension(width, 1280);
        int h = clampDimension(height, 720);
        if (w == Width && h == Height) return;
        Width = w;
        Height = h;
        save();
    }

    private static int clampDimension(int value, int fallback) {
        if (value <= 0) return fallback;
        return Math.min(15360, Math.max(64, value));
    }

    private void load() {
        try (Reader reader = Files.newBufferedReader(PATH)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            Width = clampDimension(getInt(json, "Width", Width), 1280);
            Height = clampDimension(getInt(json, "Height", Height), 720);
            Borderless = getBool(json, "Borderless", Borderless);
            CaptureInterval = Math.max(1, getInt(json, "CaptureInterval", CaptureInterval));
            FacesPerCapture = Math.min(6, Math.max(1, getInt(json, "FacesPerCapture", FacesPerCapture)));
            PauseWhenHidden = getBool(json, "PauseWhenHidden", PauseWhenHidden);
        } catch (Exception e) {
            LOGGER.warn("Couldn't read {}; using defaults and rewriting it", PATH, e);
        }
    }

    private void save() {
        JsonObject json = new JsonObject();
        json.addProperty("Width", Width);
        json.addProperty("Height", Height);
        json.addProperty("Borderless", Borderless);
        json.addProperty("CaptureInterval", CaptureInterval);
        json.addProperty("FacesPerCapture", FacesPerCapture);
        json.addProperty("PauseWhenHidden", PauseWhenHidden);
        try {
            if (PATH.getParent() != null) {
                Files.createDirectories(PATH.getParent());
            }
            Files.writeString(PATH, GSON.toJson(json));
        } catch (IOException e) {
            LOGGER.error("Couldn't write {}", PATH, e);
        }
    }

    private static int getInt(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) && json.getAsJsonPrimitive(key).isNumber()
                    ? json.getAsJsonPrimitive(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean getBool(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) && json.getAsJsonPrimitive(key).isBoolean()
                    ? json.getAsJsonPrimitive(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
