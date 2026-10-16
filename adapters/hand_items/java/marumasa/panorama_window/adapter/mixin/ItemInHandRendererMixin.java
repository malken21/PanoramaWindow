package marumasa.panorama_window.adapter.mixin;

import marumasa.panorama_window.core.Panorama;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 取り込み中は手 (持っている物) を描かない。
 *
 * <p>ふつうは {@code GameRenderer.setRenderHand(false)} で足りるが、OptiFine (OptiFabric) でシェーダーパックを
 * 使っていると、手はワールドの描画の途中で OptiFine 自身が描き ({@code ShadersRender.renderHand0})、その設定を見ない。
 * どの経路でも最後に通るここで止める。OptiFabric がある Minecraft 1.16 - 1.20.4 だけに入れる。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void panorama_window$hideHandWhileCapturing(CallbackInfo ci) {
        if (Panorama.isTaking()) ci.cancel();
    }
}
