package marumasa.panorama_window.adapter.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.14 - 1.16 には Window.setWidth / setHeight がない。描画先の大きさ (getWidth の値) を直接書き換える。
 */
@Mixin(Window.class)
public interface WindowAccessor {

    @Accessor("framebufferWidth")
    void panorama_window$setFramebufferWidth(int value);

    @Accessor("framebufferHeight")
    void panorama_window$setFramebufferHeight(int value);
}
