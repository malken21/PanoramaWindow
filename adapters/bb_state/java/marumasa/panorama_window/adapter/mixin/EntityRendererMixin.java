package marumasa.panorama_window.adapter.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import marumasa.panorama_window.adapter.Billboards;
import marumasa.panorama_window.core.Panorama;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 取り込み中は、名札をプレイヤーの位置から名札への水平の向きにする (Billboards の説明を参照)。
 * 1.21.2 - 1.21.8: renderNameTag の最初の引数は EntityRenderState (位置は x, y, z)。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @WrapOperation(
            method = "renderNameTag",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;cameraOrientation()Lorg/joml/Quaternionf;")
    )
    private Quaternionf panorama_window$faceThePlayer(EntityRenderDispatcher dispatcher, Operation<Quaternionf> original,
                                                    EntityRenderState state) {
        if (Panorama.isTaking() && dispatcher.camera != null) {
            final Vec3 camera = dispatcher.camera.getPosition();
            final double dx = state.x - camera.x;
            final double dz = state.z - camera.z;
            if (Billboards.hasDirection(dx, dz)) {
                return new Quaternionf().rotationY((float) Math.PI - (float) Math.toRadians(Billboards.yawTowards(dx, dz)));
            }
        }
        return original.call(dispatcher);
    }
}
