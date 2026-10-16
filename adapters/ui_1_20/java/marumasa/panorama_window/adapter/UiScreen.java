// tools/gen_ui_adapters.py が MarumaSign の実装から作ったファイル。直すときはスクリプトを直して作り直す
package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.systems.RenderSystem;
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
        RenderSystem.enableBlend();
        graphics.pose().pushPose();
        graphics.pose().scale(unscale, unscale, 1f);
        graphics.blit(texture.id, ui.originX(), ui.originY(), 0f, 0f,
                ui.pixelWidth(), ui.pixelHeight(), ui.pixelWidth(), ui.pixelHeight());
        graphics.pose().popPose();
        RenderSystem.disableBlend();
    }
}
