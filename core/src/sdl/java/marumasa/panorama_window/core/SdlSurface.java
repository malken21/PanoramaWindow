package marumasa.panorama_window.core;

import org.lwjgl.BufferUtils;
import org.lwjgl.sdl.SDL_HitTest;
import org.lwjgl.sdl.SDL_Point;

import java.nio.IntBuffer;

import static org.lwjgl.sdl.SDLVideo.*;

/**
 * SDL3 のウィンドウ (Minecraft 26.3 以降。GLFW の代わりに SDL3 を使うようになった)。
 * LWJGL の SDL のバインディングでコンパイルするので、別のソースセット (sdl) にある。
 *
 * <p>Minecraft は SDL のイベントをすべてのウィンドウの分まとめて受け取るので、このウィンドウのイベントは
 * Mixin で Minecraft に渡さないようにしてある (閉じるボタンで Minecraft が終了しないように)。
 * 大きさの変化はイベントではなく、描くたびに大きさを聞いて知る。
 */
final class SdlSurface extends Surface {

    private long window;
    private long context;
    private long savedWindow;
    private long savedContext;
    // ウィンドウのどこをつかんでも動かせるようにする (枠なしのときのため)。GC されないよう持っておく
    private SDL_HitTest hitTest;

    @Override
    void create(String title, int width, int height, boolean visible, boolean decorated, long mcWindow, boolean share) {
        final IntBuffer value = BufferUtils.createIntBuffer(1);
        if (share) {
            // 共有するコンテキストは、版とプロファイルを Minecraft と揃える
            for (int attribute : new int[]{SDL_GL_CONTEXT_MAJOR_VERSION, SDL_GL_CONTEXT_MINOR_VERSION,
                    SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_FLAGS}) {
                if (SDL_GL_GetAttribute(attribute, value)) SDL_GL_SetAttribute(attribute, value.get(0));
            }
            SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 1);
        } else {
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 2);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, 1 /* SDL_GL_CONTEXT_PROFILE_CORE */);
        }
        long flags = SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE;
        if (!visible) flags |= SDL_WINDOW_HIDDEN;
        if (!decorated) flags |= SDL_WINDOW_BORDERLESS;
        window = SDL_CreateWindow(title, width, height, flags);
        if (window == 0) {
            SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 0);
            throw new IllegalStateException("Failed to create the panorama window");
        }
        context = SDL_GL_CreateContext(window);
        SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 0);
        if (context == 0) throw new IllegalStateException("Failed to create the panorama window's OpenGL context");
        SDL_GL_MakeCurrent(window, context);
        SDL_GL_SetSwapInterval(0);
        hitTest = SDL_HitTest.create((win, area, data) -> hitTest(win, SDL_Point.create(area)));
        SDL_SetWindowHitTest(window, hitTest, 0);
    }

    // 端の近くは大きさを変える、それ以外はつかんで動かす
    private static int hitTest(long win, SDL_Point point) {
        final IntBuffer w = BufferUtils.createIntBuffer(1);
        final IntBuffer h = BufferUtils.createIntBuffer(1);
        SDL_GetWindowSize(win, w, h);
        final int edge = 8;
        final boolean left = point.x() < edge;
        final boolean right = point.x() >= w.get(0) - edge;
        final boolean top = point.y() < edge;
        final boolean bottom = point.y() >= h.get(0) - edge;
        if (top && left) return SDL_HITTEST_RESIZE_TOPLEFT;
        if (top && right) return SDL_HITTEST_RESIZE_TOPRIGHT;
        if (bottom && left) return SDL_HITTEST_RESIZE_BOTTOMLEFT;
        if (bottom && right) return SDL_HITTEST_RESIZE_BOTTOMRIGHT;
        if (top) return SDL_HITTEST_RESIZE_TOP;
        if (bottom) return SDL_HITTEST_RESIZE_BOTTOM;
        if (left) return SDL_HITTEST_RESIZE_LEFT;
        if (right) return SDL_HITTEST_RESIZE_RIGHT;
        return SDL_HITTEST_DRAGGABLE;
    }

    @Override
    void saveCurrent() {
        savedWindow = SDL_GL_GetCurrentWindow();
        savedContext = SDL_GL_GetCurrentContext();
    }

    @Override
    void makeCurrent() {
        SDL_GL_MakeCurrent(window, context);
    }

    @Override
    void restoreSaved() {
        SDL_GL_MakeCurrent(savedWindow, savedContext);
    }

    @Override
    void swapBuffers() {
        SDL_GL_SwapWindow(window);
    }

    @Override
    int[] framebufferSize() {
        final IntBuffer w = BufferUtils.createIntBuffer(1);
        final IntBuffer h = BufferUtils.createIntBuffer(1);
        SDL_GetWindowSizeInPixels(window, w, h);
        return new int[]{w.get(0), h.get(0)};
    }

    @Override
    int[] windowSize() {
        final IntBuffer w = BufferUtils.createIntBuffer(1);
        final IntBuffer h = BufferUtils.createIntBuffer(1);
        SDL_GetWindowSize(window, w, h);
        return new int[]{w.get(0), h.get(0)};
    }

    @Override
    boolean minimized() {
        return (SDL_GetWindowFlags(window) & SDL_WINDOW_MINIMIZED) != 0;
    }

    @Override
    void setVisible(boolean visible) {
        if (visible) {
            SDL_ShowWindow(window);
        } else {
            SDL_HideWindow(window);
        }
    }

    @Override
    void setDecorated(boolean decorated) {
        SDL_SetWindowBordered(window, decorated);
    }

    @Override
    long handle() {
        return window;
    }
}
