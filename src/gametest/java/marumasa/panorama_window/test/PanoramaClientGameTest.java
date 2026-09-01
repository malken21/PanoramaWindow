package marumasa.panorama_window.test;

import marumasa.panorama_window.client.PanoramaManager;
import marumasa.panorama_window.client.PanoramaWindowClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end test for the ported panorama pipeline.
 *
 * <p>Builds a landmark ring of four coloured pillars in a normal world, then while the player
 * rotates, walks and travels between the Overworld, the Nether and the End it repeatedly:
 * <ul>
 *   <li>checks that the mod keeps finishing full six-face cube-map captures (no crash / no stall),</li>
 *   <li>saves the real contents of the separate panorama window to a PNG,</li>
 *   <li>saves the normal Minecraft view for comparison.</li>
 * </ul>
 *
 * <p>Finally it asserts that the panorama images contain a rendered scene (not blank), change when
 * the camera rotates and when the player moves, and clearly differ between dimensions.
 */
public class PanoramaClientGameTest implements FabricClientGameTest {

    private Path outDir;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitTicks(20);
        outDir = context.computeOnClient(client -> client.gameDirectory.toPath()
                .toAbsolutePath().normalize().resolve("panorama-test-output"));

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();

            context.waitTicks(60);
            context.waitFor(client -> PanoramaWindowClient.isReady());
            context.waitFor(client -> PanoramaManager.completedCaptures > 0);

            buildOverworldLandmarks(server);
            singleplayer.getConnection().waitForChunksRender();
            faceNorth(context);
            context.waitTicks(30);

            // ---- Overworld: baseline ----
            long captures = PanoramaManager.completedCaptures;
            savePanorama(context, "01-overworld-north.png");
            mcShot(context, "mc-01-overworld-north");

            // ---- Overworld: rotate the camera ----
            context.runOnClient(client -> client.player.setYRot(135.0F));
            context.waitTicks(20);
            captures = assertKeptCapturing(captures, "after rotating the camera");
            savePanorama(context, "02-overworld-rot135.png");

            // ---- Overworld: walk towards the north pillar ----
            faceNorth(context);
            context.waitTicks(10);
            savePanorama(context, "03-overworld-before-walk.png");
            context.getInput().holdKeyFor(options -> options.keyUp, 50);
            context.waitTicks(20);
            captures = assertKeptCapturing(captures, "while walking");
            savePanorama(context, "04-overworld-after-walk.png");
            mcShot(context, "mc-04-overworld-after-walk");

            // ---- Travel to the Nether ----
            server.runCommand("execute in minecraft:the_nether run fill -10 62 -10 10 62 10 minecraft:obsidian");
            server.runCommand("execute in minecraft:the_nether run fill -9 63 -9 9 90 9 minecraft:air");
            server.runCommand("execute as @a in minecraft:the_nether run tp @s 0.5 64.0 0.5");
            context.waitTicks(10); // let the dimension swap happen; the mod deliberately pauses here
            singleplayer.getConnection().waitForChunksRender();
            faceNorth(context);
            context.waitTicks(50); // past the mod's post-dimension-change settle
            captures = assertKeptCapturing(captures, "after entering the Nether");
            savePanorama(context, "05-nether.png");
            mcShot(context, "mc-05-nether");

            // ---- Travel to the End ----
            server.runCommand("execute in minecraft:the_end run fill -10 62 -10 10 62 10 minecraft:end_stone");
            server.runCommand("execute as @a in minecraft:the_end run tp @s 0.5 64.0 0.5");
            context.waitTicks(10);
            singleplayer.getConnection().waitForChunksRender();
            faceNorth(context);
            context.waitTicks(50);
            captures = assertKeptCapturing(captures, "after entering the End");
            savePanorama(context, "06-end.png");
            mcShot(context, "mc-06-end");

            // ---- Back to the Overworld ----
            server.runCommand("execute as @a in minecraft:overworld run tp @s 0.5 100.5 0.5");
            context.waitTicks(10);
            singleplayer.getConnection().waitForChunksRender();
            faceNorth(context);
            context.waitTicks(50);
            assertKeptCapturing(captures, "back in the Overworld");
            savePanorama(context, "07-overworld-return.png");

            // ---- checks ----
            assertHasContent("01-overworld-north.png");
            assertHasContent("04-overworld-after-walk.png");
            assertHasContent("05-nether.png");
            assertHasContent("06-end.png");
            assertHasContent("07-overworld-return.png");

            assertDiffers("01-overworld-north.png", "02-overworld-rot135.png", "rotating the camera");
            assertDiffers("03-overworld-before-walk.png", "04-overworld-after-walk.png", "walking");
            assertDiffers("04-overworld-after-walk.png", "05-nether.png", "travelling to the Nether");
            assertDiffers("05-nether.png", "06-end.png", "travelling to the End");
            assertDiffers("06-end.png", "07-overworld-return.png", "returning to the Overworld");
        }

        context.waitTicks(20);
    }

    private void buildOverworldLandmarks(TestServerContext server) {
        server.runCommand("gamerule doDaylightCycle false");
        server.runCommand("gamerule doWeatherCycle false");
        server.runCommand("gamerule doMobSpawning false");
        server.runCommand("time set 6000");
        server.runCommand("weather clear");
        server.runCommand("gamemode creative @a");
        server.runCommand("execute as @a in minecraft:overworld run tp @s 0.5 100.5 0.5");
        server.runCommand("fill -10 99 -10 10 99 10 minecraft:smooth_stone");
        // Four distinctly coloured pillars, one per cardinal direction (north = -Z).
        server.runCommand("fill -1 100 -20 1 130 -16 minecraft:gold_block");
        server.runCommand("fill 16 100 -1 20 130 1 minecraft:redstone_block");
        server.runCommand("fill -1 100 16 1 130 20 minecraft:lapis_block");
        server.runCommand("fill -20 100 -1 -16 130 1 minecraft:emerald_block");
        server.runCommand("execute as @a in minecraft:overworld run tp @s 0.5 100.5 0.5");
    }

    private void faceNorth(ClientGameTestContext context) {
        context.runOnClient(client -> {
            client.player.setXRot(0.0F);
            client.player.setYRot(180.0F); // yaw 180 = facing -Z = north
        });
    }

    private long assertKeptCapturing(long previous, String what) {
        long now = PanoramaManager.completedCaptures;
        if (now <= previous) {
            throw new AssertionError("Panorama capture stalled " + what + " (was " + previous + ", now " + now + ")");
        }
        return now;
    }

    private void mcShot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withDestinationDir(outDir));
    }

    private void savePanorama(ClientGameTestContext context, String name) {
        Path path = outDir.resolve(name);
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }

        context.runOnClient(client -> PanoramaWindowClient.requestPanoramaScreenshot(path));
        // The screenshot is written on the next shader render (fresh cube-map data arrives a frame late).
        context.waitFor(client -> Files.exists(path));
    }

    private void assertHasContent(String name) {
        BufferedImage image = read(name);
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
            throw new AssertionError("Panorama image " + name + " looks blank / uniform (luma mean="
                    + String.format("%.1f", mean) + ", stddev=" + String.format("%.2f", stdDev) + ")");
        }
    }

    private void assertDiffers(String a, String b, String cause) {
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
            throw new AssertionError("Panorama image did not change after " + cause + " (" + a + " vs " + b
                    + ", mean channel diff=" + String.format("%.2f", meanDiff) + ")");
        }
    }

    private BufferedImage read(String name) {
        Path png = outDir.resolve(name);
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
