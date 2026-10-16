package marumasa.panorama_window.core;

/**
 * パノラマウィンドウの、ウィンドウと OpenGL のコンテキストの部分。Minecraft と同じ仕組みで作る:
 * 26.2 までは GLFW ({@link GlfwSurface})、26.3 からは SDL3 (SdlSurface、別のソースセット)。
 */
abstract class Surface {

    /**
     * ウィンドウとコンテキストを作り、そのコンテキストを current にする (呼び出し側が {@link #restoreSaved()} で戻す)。
     *
     * @param mcWindow Minecraft のウィンドウ
     * @param share    Minecraft の OpenGL コンテキストと共有するか (Minecraft のコンテキストが current のときに呼ぶ)
     */
    abstract void create(String title, int width, int height, boolean visible, boolean decorated, long mcWindow, boolean share);

    /** 今 current のウィンドウとコンテキストを覚える */
    abstract void saveCurrent();

    abstract void makeCurrent();

    /** {@link #saveCurrent()} で覚えたものを current に戻す */
    abstract void restoreSaved();

    abstract void swapBuffers();

    /** 描画先の大きさ (ピクセル。高 DPI の画面ではウィンドウの大きさと違う) */
    abstract int[] framebufferSize();

    /** ウィンドウの大きさ (画面の座標) */
    abstract int[] windowSize();

    abstract boolean minimized();

    abstract void setVisible(boolean visible);

    abstract void setDecorated(boolean decorated);

    abstract long handle();

    /** ウィンドウをドラッグしている最中か (その間は毎フレーム描き直す) */
    boolean dragging() {
        return false;
    }

    static Surface forThisMinecraft() {
        try {
            Class.forName("org.lwjgl.glfw.GLFW", false, Surface.class.getClassLoader());
            return new GlfwSurface();
        } catch (ClassNotFoundException | LinkageError e) {
            // Minecraft 26.3 以降は GLFW の代わりに SDL3 を使う
            try {
                return (Surface) Class.forName("marumasa.panorama_window.core.SdlSurface").getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Neither GLFW nor SDL3 is available", ex);
            }
        }
    }
}
