package marumasa.panorama_window.adapter.mixin;

import com.mojang.math.Vector3f;
import marumasa.panorama_window.core.Panorama;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 取り込み中は、ビルボード (火・経験値オーブ・投げたアイテム・釣りの浮きなど) をすべての面で同じ水平の向きにする
 * (Billboards の説明を参照)。
 *
 * <p>1.15 - 1.19.2: prepare がカメラの回転 (com.mojang.math.Quaternion) をそのまま cameraOrientation にする。
 * カメラ自身の回転は変えずに、別の回転に差し替える。Camera.setRotation(yaw, 0) と同じ式: YP(-yaw)。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "prepare", at = @At("TAIL"))
    private void panorama_window$stabiliseBillboards(CallbackInfo ci) {
        if (!Panorama.isTaking()) return;
        ((EntityRenderDispatcher) (Object) this).overrideCameraOrientation(
                Vector3f.YP.rotationDegrees(-Panorama.billboardYaw()));
    }
}
