package marumasa.panorama_window.test;

import marumasa.panorama_window.client.PanoramaManager;
import marumasa.panorama_window.client.PanoramaWindowClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end smoke test for the ported panorama pipeline.
 *
 * <p>Creates a singleplayer world and then, while the player rotates, looks around and walks,
 * repeatedly:
 * <ul>
 *   <li>checks that the mod keeps completing full six-face cube-map captures (no crash / no stall),</li>
 *   <li>saves the actual contents of the separate panorama window to a PNG,</li>
 *   <li>saves the normal Minecraft view for comparison.</li>
 * </ul>
 *
 * <p>Finally it asserts that the panorama images actually contain a rendered scene (they are not
 * blank / uniform) and that they change when the camera rotates and when the player moves.
 */
public class PanoramaClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitTicks(20);

        Path outDir = context.computeOnClient(client -> client.gameDirectory.toPath()
                .toAbsolutePath().normalize().resolve("panorama-test-output"));

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            // Let the world load and the panorama window get created.
            context.waitTicks(80);
            context.waitFor(client -> PanoramaWindowClient.isReady());
            context.waitFor(client -> PanoramaManager.completedCaptures > 0);

            // --- baseline, facing spawn direction ---
            context.runOnClient(client -> {
                client.player.setXRot(0.0F);
                client.player.setYRot(0.0F);
            });
            context.waitTicks(20);
            long captures = PanoramaManager.completedCaptures;
            savePanorama(context, outDir.resolve("01-spawn-north.png"));
            context.takeScreenshot(TestScreenshotOptions.of("mc-01-spawn-north").withDestinationDir(outDir));

            // --- rotate the camera 120 degrees ---
            context.runOnClient(client -> client.player.setYRot(120.0F));
            context.waitTicks(20);
            captures = assertKeptCapturing(captures, "after rotating yaw");
            savePanorama(context, outDir.resolve("02-yaw-120.png"));
            context.takeScreenshot(TestScreenshotOptions.of("mc-02-yaw-120").withDestinationDir(outDir));

            // --- look up ---
            context.runOnClient(client -> client.player.setXRot(-75.0F));
            context.waitTicks(20);
            captures = assertKeptCapturing(captures, "after pitching up");
            savePanorama(context, outDir.resolve("03-look-up.png"));

            // --- walk forward for 3 seconds ---
            context.runOnClient(client -> {
                client.player.setXRot(0.0F);
                client.player.setYRot(0.0F);
            });
            context.waitTicks(10);
            savePanorama(context, outDir.resolve("04-before-walk.png"));
            context.getInput().holdKeyFor(options -> options.keyUp, 60);
            context.waitTicks(20);
            captures = assertKeptCapturing(captures, "while walking forward");
            savePanorama(context, outDir.resolve("05-after-walk.png"));
            context.takeScreenshot(TestScreenshotOptions.of("mc-05-after-walk").withDestinationDir(outDir));

            // --- verify the images actually contain a scene and react to the camera ---
            assertHasContent(outDir.resolve("01-spawn-north.png"));
            assertHasContent(outDir.resolve("05-after-walk.png"));
            assertDiffers(outDir.resolve("01-spawn-north.png"), outDir.resolve("02-yaw-120.png"), "rotating the camera");
            assertDiffers(outDir.resolve("04-before-walk.png"), outDir.resolve("05-after-walk.png"), "walking forward");
        }

        context.waitTicks(20);
    }

    private long assertKeptCapturing(long previous, String what) {
        long now = PanoramaManager.completedCaptures;
        if (now <= previous) {
            throw new AssertionError("Panorama capture stalled " + what + " (was " + previous + ", now " + now + ")");
        }
        return now;
    }

    private void savePanorama(ClientGameTestContext context, Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }

        context.runOnClient(client -> PanoramaWindowClient.requestPanoramaScreenshot(path));
        // The screenshot is written on the next shader render (fresh cube-map data arrives a frame late).
        context.waitFor(client -> Files.exists(path));
    }

    private void assertHasContent(Path png) {
        BufferedImage image = read(png);
        double sum = 0.0;
        double sumSq = 0.0;
        long count = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                int rgb = image.getRGB(x, y);
                int luma = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
                sum += luma;
                sumSq += (double) luma * luma;
                count++;
            }
        }
        double mean = sum / count;
        double stdDev = Math.sqrt(Math.max(0.0, sumSq / count - mean * mean));
        if (stdDev < 3.0) {
            throw new AssertionError("Panorama image " + png.getFileName()
                    + " looks blank / uniform (luma mean=" + String.format("%.1f", mean)
                    + ", stddev=" + String.format("%.2f", stdDev) + ")");
        }
    }

    private void assertDiffers(Path a, Path b, String cause) {
        BufferedImage imageA = read(a);
        BufferedImage imageB = read(b);
        long diff = 0;
        long count = 0;
        for (int y = 0; y < Math.min(imageA.getHeight(), imageB.getHeight()); y += 4) {
            for (int x = 0; x < Math.min(imageA.getWidth(), imageB.getWidth()); x += 4) {
                int rgbA = imageA.getRGB(x, y);
                int rgbB = imageB.getRGB(x, y);
                diff += Math.abs(((rgbA >> 16) & 0xFF) - ((rgbB >> 16) & 0xFF));
                diff += Math.abs(((rgbA >> 8) & 0xFF) - ((rgbB >> 8) & 0xFF));
                diff += Math.abs((rgbA & 0xFF) - (rgbB & 0xFF));
                count += 3;
            }
        }
        double meanDiff = (double) diff / count;
        if (meanDiff < 2.0) {
            throw new AssertionError("Panorama image did not change after " + cause
                    + " (" + a.getFileName() + " vs " + b.getFileName()
                    + ", mean channel diff=" + String.format("%.2f", meanDiff) + ")");
        }
    }

    private BufferedImage read(Path png) {
        try {
            BufferedImage image = ImageIO.read(png.toFile());
            if (image == null) {
                throw new AssertionError("Not a readable image: " + png);
            }
            return image;
        } catch (IOException e) {
            throw new AssertionError("Couldn't read " + png, e);
        }
    }
}
