// tools/gen_ui_adapters.py が MarumaSign の実装から作ったファイル。直すときはスクリプトを直して作り直す
package marumasa.panorama_window.adapter;

import net.minecraft.client.gui.GuiGraphics;

/**
 * PanoramaWindow の設定画面の描画部分。
 */
final class UiScreen extends UiScreenBase {

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        final Tex texture = prepare();
        if (texture == null) return;

        final float unscale = unscale();
        graphics.pose().pushMatrix();
        graphics.pose().scale(unscale, unscale);
        graphics.blit(texture.id,
                ui.originX(), ui.originY(), ui.originX() + ui.pixelWidth(), ui.originY() + ui.pixelHeight(),
                0f, 1f, 0f, 1f);
        graphics.pose().popMatrix();
    }
}
