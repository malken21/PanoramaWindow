package marumasa.panorama_window.adapter.mixin;

import marumasa.panorama_window.adapter.Hooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * tick・フレーム・終了のきっかけ。どのバージョンにも同じ名前のメソッドがある。
 *
 * <p>面の取り込みはワールドを描き直すので、フレームの描画の途中ではなく tick の終わりに行う
 * (26.1 以降の描画は、描画の途中で入れ子にできない)。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void panorama_window$tick(CallbackInfo ci) {
        Hooks.onTick();
    }

    @Inject(method = "runTick", at = @At("TAIL"))
    private void panorama_window$frame(boolean renderLevel, CallbackInfo ci) {
        Hooks.onFrame();
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void panorama_window$stop(CallbackInfo ci) {
        Hooks.onStop();
    }
}
