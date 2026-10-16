// tools/gen_ui_adapters.py が MarumaSign の実装から作ったファイル。直すときはスクリプトを直して作り直す
package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import marumasa.panorama_window.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * テクスチャのハンドル。Minecraft 1.15 - 1.15.2 の作り方。
 */
final class Tex {

    private static final AtomicInteger nextId = new AtomicInteger();
    // テクスチャを登録するメソッド。1.15.2 で戻り値の型が変わったので、どのバージョンでも動くように引数の型で探す
    private static Method register;

    final ResourceLocation id;
    private final DynamicTexture texture;

    private Tex(ResourceLocation id, DynamicTexture texture) {
        this.id = id;
        this.texture = texture;
    }

    private static void copy(NativeImage image, int width, int height, int[] argb) {
        for (int y = 0; y < height; y++) {
            final int row = y * width;
            for (int x = 0; x < width; x++) {
                final int color = argb[row + x];
                image.setPixelRGBA(x, y, (color & 0xFF00FF00) | ((color & 0xFF) << 16) | ((color >> 16) & 0xFF));
            }
        }
    }

    static Tex create(Minecraft mc, int width, int height, int[] argb) {
        final NativeImage image = new NativeImage(width, height, false);
        copy(image, width, height, argb);
        final ResourceLocation id = new ResourceLocation("panorama_window", "dynamic/" + nextId.incrementAndGet());
        final DynamicTexture texture = new DynamicTexture(image);
        if (register == null) {
            register = Reflect.methodWithParameters(TextureManager.class, ResourceLocation.class, AbstractTexture.class);
        }
        Reflect.invoke(register, mc.getTextureManager(), id, texture);
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
