package marumasa.panorama_window.adapter.mixin;

import marumasa.panorama_window.core.Panorama;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 取り込み中は、ビルボード (名札・火・経験値オーブ・投げたアイテム・釣りの浮きなど) をすべての面で同じ水平の向きにする
 * (Billboards の説明を参照)。
 *
 * <p>1.14: ビルボードは prepare が覚えたカメラの向き (playerRotY / playerRotX、度) で回す。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Shadow
    public float playerRotY;

    @Shadow
    public float playerRotX;

    @Inject(method = "prepare", at = @At("TAIL"))
    private void panorama_window$stabiliseBillboards(CallbackInfo ci) {
        if (!Panorama.isTaking()) return;
        playerRotY = Panorama.billboardYaw();
        playerRotX = 0.0F;
    }
}
