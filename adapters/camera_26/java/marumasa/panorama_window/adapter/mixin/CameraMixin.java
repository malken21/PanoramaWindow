package marumasa.panorama_window.adapter.mixin;

import marumasa.panorama_window.core.Panorama;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 取り込み中は、ビルボード (名札・火・経験値オーブ・投げたアイテム・釣りの浮き・コンジットの目など) を
 * すべての面で同じ水平の向きにする (Billboards の説明を参照)。名札は NameTagMixin でさらに直す。
 *
 * <p>26.1 - 26.2: Camera.extractRenderState が cameraRenderState.orientation を埋める。視点の行列はここからは
 * 作られないので、ビルボードの向きだけが変わる。Camera.setRotation(yaw, 0) と同じ式: Y 軸まわりに PI - yaw。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void panorama_window$stabiliseBillboards(CameraRenderState cameraState, float partialTicks, CallbackInfo ci) {
        if (!Panorama.isTaking()) return;
        cameraState.orientation.rotationY((float) Math.PI - (float) Math.toRadians(Panorama.billboardYaw()));
    }
}
