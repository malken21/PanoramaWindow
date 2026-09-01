package marumasa.panorama_window.util;

import marumasa.panorama_window.PanoramaWindow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static marumasa.panorama_window.PanoramaWindow.MOD_ID;

public class ModResource {
    public static String readString(String path) {
        try (InputStream is = readStream(path)) {
            if (is == null) throw new RuntimeException("Resource not found " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load resource", e);
        }
    }

    public static InputStream readStream(String path) {
        return PanoramaWindow.class.getResourceAsStream(String.format("/assets/%s/%s", MOD_ID, path));
    }
}
