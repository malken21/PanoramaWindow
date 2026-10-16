package marumasa.panorama_window.adapter.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import marumasa.panorama_window.adapter.Billboards;
import marumasa.panorama_window.core.Panorama;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 取り込み中は、名札をプレイヤーの位置から名札への水平の向きにする (Billboards の説明を参照)。
 * 1.21 - 1.21.1: renderNameTag の最初の引数は Entity。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @WrapOperation(
            method = "renderNameTag",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;cameraOrientation()Lorg/joml/Quaternionf;")
    )
    private Quaternionf panorama_window$faceThePlayer(EntityRenderDispatcher dispatcher, Operation<Quaternionf> original,
                                                    Entity entity) {
        if (Panorama.isTaking() && dispatcher.camera != null) {
            final Vec3 camera = dispatcher.camera.getPosition();
            final double dx = entity.getX() - camera.x;
            final double dz = entity.getZ() - camera.z;
            if (Billboards.hasDirection(dx, dz)) {
                return new Quaternionf().rotationY((float) Math.PI - (float) Math.toRadians(Billboards.yawTowards(dx, dz)));
            }
        }
        return original.call(dispatcher);
    }
}
