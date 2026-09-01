package marumasa.panorama_window.mixin;

import marumasa.panorama_window.client.PanoramaManager;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the panorama mod captures its six world-axis cube faces, every billboard (name tags,
 * fire, XP orbs, thrown items, fishing bobbers, the conduit eye, ...) is normally turned to face
 * the axis of whichever face is being rendered. A billboard that lands on a face boundary is then
 * drawn with two different orientations in the two faces and tears along the seam; one away from a
 * face centre is skewed.
 *
 * <p>This forces {@code cameraRenderState.orientation} to one fixed horizontal direction - the
 * player's view yaw latched at the start of the sweep - so all six faces agree. Pitch is left at
 * zero: billboards stay world-upright and the panorama window shader tilts them with the view when
 * the player looks up or down. Name tags are refined further, per entity, in
 * {@link SubmitNodeCollectionMixin}.
 *
 * <p>Only active during {@link PanoramaManager#isTaking()}; the normal Minecraft view is untouched.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void panorama_window$stabiliseBillboards(CameraRenderState cameraState, float partialTicks, CallbackInfo ci) {
        if (!PanoramaManager.isTaking()) {
            return;
        }
        // Matches Camera.setRotation(yaw, 0): rotationYXZ(PI - yaw, 0, 0) reduces to a pure Y turn.
        float yawRadians = Mth.PI - PanoramaManager.billboardYawDegrees * Mth.DEG_TO_RAD;
        cameraState.orientation.rotationY(yawRadians);
    }
}
