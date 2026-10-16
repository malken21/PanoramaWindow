// tools/gen_ui_adapters.py が MarumaSign の実装から作ったファイル。直すときはスクリプトを直して作り直す
package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;

/**
 * PanoramaWindow の設定画面の描画部分。
 */
final class UiScreen extends UiScreenBase {

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        final Tex texture = prepare();
        if (texture == null) return;

        final float unscale = unscale();
        RenderSystem.setShaderTexture(0, texture.id);
        RenderSystem.enableBlend();
        poseStack.pushPose();
        poseStack.scale(unscale, unscale, 1f);
        GuiComponent.blit(poseStack, ui.originX(), ui.originY(), 0f, 0f,
                ui.pixelWidth(), ui.pixelHeight(), ui.pixelWidth(), ui.pixelHeight());
        poseStack.popPose();
        RenderSystem.disableBlend();
    }
}
