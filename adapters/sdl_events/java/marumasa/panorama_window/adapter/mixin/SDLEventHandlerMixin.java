package marumasa.panorama_window.adapter.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.SDLEventHandler;
import com.mojang.blaze3d.platform.Window;
import marumasa.panorama_window.adapter.Hooks;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.3 から、Minecraft は SDL3 のイベントをすべてのウィンドウの分まとめて受け取り、ウィンドウのイベント
 * (閉じる・大きさ・移動・最小化など) を、どのウィンドウのものかを見ずに自分のウィンドウとして扱う。
 * そのままだと、パノラマウィンドウを閉じると Minecraft が終了し、大きさを変えると Minecraft の描画先の大きさが狂う。
 * そこで、パノラマウィンドウのイベントは Minecraft に渡さない (入力のイベントは Minecraft 側でウィンドウを見分けている)。
 */
@Mixin(SDLEventHandler.class)
public abstract class SDLEventHandlerMixin {

    @WrapOperation(
            method = "pollEvents",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/Window;handleEvent(Lorg/lwjgl/sdl/SDL_Event;)V")
    )
    private void panorama_window$skipPanoramaWindow(Window window, SDL_Event event, Operation<Void> original) {
        if (Hooks.isPanoramaWindow(SDLEvents.SDL_GetWindowFromEvent(event))) return;
        original.call(window, event);
    }
}
