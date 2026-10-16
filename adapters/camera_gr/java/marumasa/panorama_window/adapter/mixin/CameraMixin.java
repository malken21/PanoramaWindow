package marumasa.panorama_window.adapter.mixin;

import marumasa.panorama_window.core.Panorama;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 取り込み中は、ビルボード (名札・火・経験値オーブ・投げたアイテム・釣りの浮きなど) をすべての面で同じ水平の向きにする
 * (Billboards の説明を参照)。名札は NameTagMixin でさらに直す。
 *
 * <p>1.21.9 - 1.21.11: GameRenderer.extractCamera が cameraRenderState.orientation を埋める。視点の行列は
 * Camera から直接作られるので、ビルボードの向きだけが変わる。Camera.setRotation(yaw, 0) と同じ式: Y 軸まわりに PI - yaw。
 */
@Mixin(GameRenderer.class)
public abstract class CameraMixin {

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "extractCamera", at = @At("TAIL"))
    private void panorama_window$stabiliseBillboards(CallbackInfo ci) {
        if (!Panorama.isTaking()) return;
        levelRenderState.cameraRenderState.orientation =
                new org.joml.Quaternionf().rotationY((float) Math.PI - (float) Math.toRadians(Panorama.billboardYaw()));
    }
}
