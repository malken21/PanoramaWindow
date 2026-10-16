package marumasa.panorama_window.adapter.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 色のテクスチャの名前。1.14 - 1.16.1 では公開のフィールド、1.16.2 からは非公開のフィールドとメソッドなので、
 * どちらでも読めるようにフィールドから直接読む。
 */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {

    @Accessor("colorTextureId")
    int panorama_window$getColorTextureId();
}
