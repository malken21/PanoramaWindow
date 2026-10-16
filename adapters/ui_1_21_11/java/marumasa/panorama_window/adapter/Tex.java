// tools/gen_ui_adapters.py が MarumaSign の実装から作ったファイル。直すときはスクリプトを直して作り直す
package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * テクスチャのハンドル。Minecraft 1.21.11 以降の作り方。
 */
final class Tex {

    private static final AtomicInteger nextId = new AtomicInteger();

    final Identifier id;
    private final DynamicTexture texture;

    private Tex(Identifier id, DynamicTexture texture) {
        this.id = id;
        this.texture = texture;
    }

    private static void copy(NativeImage image, int width, int height, int[] argb) {
        for (int y = 0; y < height; y++) {
            final int row = y * width;
            for (int x = 0; x < width; x++) {
                image.setPixel(x, y, argb[row + x]);
            }
        }
    }

    static Tex create(Minecraft mc, int width, int height, int[] argb) {
        final NativeImage image = new NativeImage(width, height, false);
        copy(image, width, height, argb);
        final Identifier id = Identifier.fromNamespaceAndPath("panorama_window", "dynamic/" + nextId.incrementAndGet());
        final DynamicTexture texture = new DynamicTexture(() -> "panorama_window", image);
        mc.getTextureManager().register(id, texture);
        return new Tex(id, texture);
    }

    void update(int width, int height, int[] argb) {
        copy(texture.getPixels(), width, height, argb);
        texture.upload();
    }

    void release(Minecraft mc) {
        mc.getTextureManager().release(id);
    }
}
