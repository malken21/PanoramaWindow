package marumasa.panorama_window.adapter.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.14 - 1.16 には、手やブロックの枠を描かないようにするメソッドがない。
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Accessor("renderHand")
    void panorama_window$setRenderHand(boolean value);

    @Accessor("renderBlockOutline")
    void panorama_window$setRenderBlockOutline(boolean value);
}
