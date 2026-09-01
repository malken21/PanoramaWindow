package marumasa.panorama_window.test;

import marumasa.panorama_window.client.PanoramaManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Smoke test for the ported panorama capture path: creates a singleplayer world, lets the mod
 * run its per-tick six-face capture for a while, and asserts that full cube-map sets are being
 * read back without the client crashing.
 */
public class PanoramaClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitTicks(20);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            // Let the world settle and the panorama window get created.
            context.waitTicks(80);

            long before = PanoramaManager.completedCaptures;

            // The mod captures once per client tick; give it plenty of room.
            context.waitTicks(120);

            long after = PanoramaManager.completedCaptures;
            if (after <= before) {
                throw new AssertionError(
                        "Panorama capture never completed a full cube-map set (before=" + before + ", after=" + after + ")");
            }

            context.takeScreenshot("panorama-in-world");

            // Spin the player and make sure the capture keeps up without throwing.
            context.runOnClient(client -> {
                if (client.player != null) {
                    client.player.setYRot(client.player.getYRot() + 120.0F);
                }
            });
            context.waitTicks(60);

            if (PanoramaManager.completedCaptures <= after) {
                throw new AssertionError("Panorama capture stalled after rotating the player");
            }

            context.takeScreenshot("panorama-after-rotation");
        }

        context.waitTicks(20);
    }
}
