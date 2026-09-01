package marumasa.panorama_window;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static marumasa.panorama_window.PanoramaWindow.MOD_ID;

public class Config {

    private static final Path path = FabricLoader.getInstance().getConfigDir().normalize().resolve(MOD_ID + ".json");

    private int CameraSize = 512;
    private int Width = 1280;
    private int Height = 720;
    private boolean Borderless = false;
    // Minimum client ticks between two capture batches. The window still redraws every frame; this
    // only limits how often the world is re-rendered to refresh the (world-locked) cube faces.
    private int CaptureInterval = 2;
    // How many of the six cube faces are re-rendered per capture batch (1..6). Lower spreads the
    // cost over more ticks (smoother frame pacing, slower full refresh); 6 restores the old burst.
    private int FacesPerCapture = 2;
    // Skip capturing (and just hold the last frame) while the panorama window is minimized.
    private boolean PauseWhenHidden = true;

    public int getCameraSize() {
        return CameraSize;
    }

    public void setCameraSize(int cameraSize) {
        CameraSize = cameraSize;
        serialize();
    }

    public int getCaptureInterval() {
        return Math.max(1, CaptureInterval);
    }

    public void setCaptureInterval(int captureInterval) {
        CaptureInterval = Math.max(1, captureInterval);
        serialize();
    }

    public int getFacesPerCapture() {
        return Math.min(6, Math.max(1, FacesPerCapture));
    }

    public void setFacesPerCapture(int facesPerCapture) {
        FacesPerCapture = Math.min(6, Math.max(1, facesPerCapture));
        serialize();
    }

    public boolean isPauseWhenHidden() {
        return PauseWhenHidden;
    }

    public void setPauseWhenHidden(boolean pauseWhenHidden) {
        PauseWhenHidden = pauseWhenHidden;
        serialize();
    }

    public int getWidth() {
        return Width;
    }

    public void setWidth(int width) {
        Width = width;
        serialize();
    }

    public int getHeight() {
        return Height;
    }

    public void setHeight(int height) {
        Height = height;
        serialize();
    }

    public boolean isBorderless() {
        return Borderless;
    }

    public void setBorderless(boolean borderless) {
        Borderless = borderless;
        serialize();
    }

    private record JsonModel(
            int CameraSize,
            int Width,
            int Height,
            boolean Borderless,
            int CaptureInterval,
            int FacesPerCapture,
            boolean PauseWhenHidden
    ) {
    }

    private static final Gson gson = new Gson();

    private static JsonModel loadJSON() {
        try (final BufferedReader reader = Files.newBufferedReader(Config.path)) {
            return gson.fromJson(reader, JsonModel.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void saveJSON(JsonModel model) {
        try (final BufferedWriter writer = Files.newBufferedWriter(Config.path)) {
            gson.toJson(model, model.getClass(), writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void deserialize() {
        final JsonModel model = loadJSON();
        CameraSize = model.CameraSize();
        Width = model.Width();
        Height = model.Height();
        Borderless = model.Borderless();
        // Keep the defaults when an older config file predates these fields.
        if (model.CaptureInterval() > 0) {
            CaptureInterval = model.CaptureInterval();
        }
        if (model.FacesPerCapture() > 0) {
            FacesPerCapture = model.FacesPerCapture();
        }
    }

    private void serialize() {
        saveJSON(new JsonModel(
                getCameraSize(),
                getWidth(),
                getHeight(),
                isBorderless(),
                getCaptureInterval(),
                getFacesPerCapture(),
                isPauseWhenHidden()
        ));
    }

    public Config() {
        final File configFile = path.toFile();
        if (!configFile.exists()) {
            serialize();
        } else {
            deserialize();
        }
    }
}