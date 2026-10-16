package marumasa.panorama_window.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.panorama_window.client.PanoramaManager;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Turn entity name tags to face the player (not the cube-face axis) while the panorama mod is
 * capturing its six faces.
 *
 * <p>When {@code submitNameTag} runs, the pose is a pure translation - the tag anchor relative to
 * the camera, in world-aligned space, no rotation yet. The player does not move while looking
 * around, so the horizontal camera-&gt;tag direction is the same in every face. Billboarding each
 * tag to that direction (instead of {@code camera.orientation}) means a tag that straddles two
 * faces no longer tears at the seam, and a tag you turn to look at is square-on and readable.
 *
 * <p>No-op for the normal Minecraft view: only runs during {@link PanoramaManager#isTaking()}.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin {

    @WrapOperation(
            method = "submitNameTag",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V"
            )
    )
    private void panorama_window$faceThePlayer(PoseStack poseStack, Quaternionfc cameraOrientation, Operation<Void> original) {
        if (PanoramaManager.isTaking()) {
            Matrix4f pose = poseStack.last().pose();
            float x = pose.m30();
            float z = pose.m32();
            if (x * x + z * z > 1.0e-6f) {
                // Yaw of a camera looking from the player towards (x, z); matches Camera.setRotation.
                float yaw = (float) Math.atan2(-x, z);
                original.call(poseStack, new Quaternionf().rotationY(Mth.PI - yaw));
                return;
            }
        }
        original.call(poseStack, cameraOrientation);
    }
}
