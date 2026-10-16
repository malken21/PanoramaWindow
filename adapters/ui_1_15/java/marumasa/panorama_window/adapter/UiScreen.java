// tools/gen_ui_adapters.py が MarumaSign の実装から作ったファイル。直すときはスクリプトを直して作り直す
package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * PanoramaWindow の設定画面の描画部分。
 */
final class UiScreen extends UiScreenBase {

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        final Tex texture = prepare();
        if (texture == null) return;

        final float unscale = unscale();
        minecraft.getTextureManager().bind(texture.id);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.pushMatrix();
        RenderSystem.scalef(unscale, unscale, 1f);
        blit(ui.originX(), ui.originY(), 0f, 0f,
                ui.pixelWidth(), ui.pixelHeight(), ui.pixelWidth(), ui.pixelHeight());
        RenderSystem.popMatrix();
        RenderSystem.disableBlend();
    }
}
