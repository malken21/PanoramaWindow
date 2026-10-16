package marumasa.panorama_window.adapter.mixin;

import marumasa.panorama_window.core.Panorama;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 取り込み中の FOV (縦の角度)。面より広く描くとき (オーバースキャン) は 90 度より大きくする。
 * Minecraft 1.14 - 1.21.1 では getFov が double を返す。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFovMixin {

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void panorama_window$captureFov(Camera camera, float partialTicks, boolean useFovSetting,
                                            CallbackInfoReturnable<Double> cir) {
        final float fov = Panorama.captureFov();
        if (fov > 0.0F) cir.setReturnValue((double) fov);
    }
}
