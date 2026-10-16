package marumasa.panorama_window.adapter.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.panorama_window.adapter.Billboards;
import marumasa.panorama_window.core.Panorama;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 取り込み中は、名札をプレイヤーの位置から名札への水平の向きにする (Billboards の説明を参照)。
 *
 * <p>26.1 - 26.1.2: submitNameTag の中で camera.orientation を使って名札を回す。呼び出しの間だけ、その名札に向けた回転に
 * 差し替えて、終わったら戻す。このときの pose は回転のない平行移動だけ (カメラから見たエンティティの位置) なので、
 * それに名札の取り付け位置を足したものが、カメラから名札への向きになる。
 */
@Mixin(SubmitNodeCollection.class)
public abstract class NameTagMixin {

    private static final Quaternionf panorama_window$saved = new Quaternionf();
    private static boolean panorama_window$swapped;

    @Inject(method = "submitNameTag", at = @At("HEAD"))
    private void panorama_window$faceThePlayer(PoseStack poseStack, Vec3 attachment, int offset, Component name,
                                               boolean seeThrough, int light, double distance, CameraRenderState camera, CallbackInfo ci) {
        panorama_window$swapped = false;
        if (!Panorama.isTaking() || attachment == null) return;
        final Matrix4f pose = poseStack.last().pose();
        final double dx = pose.m30() + attachment.x;
        final double dz = pose.m32() + attachment.z;
        if (!Billboards.hasDirection(dx, dz)) return;
        panorama_window$saved.set(camera.orientation);
        camera.orientation.rotationY((float) Math.PI - (float) Math.toRadians(Billboards.yawTowards(dx, dz)));
        panorama_window$swapped = true;
    }

    @Inject(method = "submitNameTag", at = @At("RETURN"))
    private void panorama_window$restore(PoseStack poseStack, Vec3 attachment, int offset, Component name,
                                         boolean seeThrough, int light, double distance, CameraRenderState camera, CallbackInfo ci) {
        if (!panorama_window$swapped) return;
        camera.orientation.set(panorama_window$saved);
        panorama_window$swapped = false;
    }
}
