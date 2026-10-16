package marumasa.panorama_window.adapter.mixin;

import marumasa.panorama_window.core.Panorama;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 取り込み中の FOV (縦の角度)。面より広く描くとき (オーバースキャン) は 90 度より大きくする。
 * 26.1 以降は Camera が FOV を計算する (パノラマのモードでは 90 度に固定されるので、その前に返す)。
 */
@Mixin(Camera.class)
public abstract class CameraFovMixin {

    @Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
    private void panorama_window$captureFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        final float fov = Panorama.captureFov();
        if (fov > 0.0F) cir.setReturnValue(fov);
    }
}
