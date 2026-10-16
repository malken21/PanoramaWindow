package marumasa.panorama_window.adapter.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import marumasa.panorama_window.adapter.Billboards;
import marumasa.panorama_window.core.Panorama;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 取り込み中は、名札をプレイヤーの位置から名札への水平の向きにする (Billboards の説明を参照)。
 *
 * <p>1.14: 名札は GameRenderer.renderNameTagInWorld で描かれ、位置 (x, y, z) はカメラからの相対位置、
 * 向き (yRot, xRot) はカメラの向き。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @WrapOperation(
            method = "renderNameTag",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderNameTagInWorld(Lnet/minecraft/client/gui/Font;Ljava/lang/String;FFFIFFZ)V")
    )
    private void panorama_window$faceThePlayer(Font font, String text, float x, float y, float z, int offset,
                                               float yRot, float xRot, boolean sneaking, Operation<Void> original) {
        if (Panorama.isTaking() && Billboards.hasDirection(x, z)) {
            original.call(font, text, x, y, z, offset, Billboards.yawTowards(x, z), 0.0F, sneaking);
            return;
        }
        original.call(font, text, x, y, z, offset, yRot, xRot, sneaking);
    }
}
