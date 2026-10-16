package marumasa.panorama_window.core;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GLFW のウィンドウ (Minecraft 1.14 - 26.2)。
 */
final class GlfwSurface extends Surface {

    private long window;
    private long saved;
    private double grabX;
    private double grabY;
    private boolean dragging;

    @Override
    void create(String title, int width, int height, boolean visible, boolean decorated, long mcWindow, boolean share) {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, visible ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_DECORATED, decorated ? GLFW_TRUE : GLFW_FALSE);
        if (share) {
            // 共有するコンテキストは、版とプロファイルを Minecraft と揃える必要がある (macOS では特に)
            if (glfwGetWindowAttrib(mcWindow, GLFW_OPENGL_PROFILE) == GLFW_OPENGL_CORE_PROFILE) {
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, glfwGetWindowAttrib(mcWindow, GLFW_CONTEXT_VERSION_MAJOR));
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, glfwGetWindowAttrib(mcWindow, GLFW_CONTEXT_VERSION_MINOR));
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, glfwGetWindowAttrib(mcWindow, GLFW_OPENGL_FORWARD_COMPAT));
            }
        } else {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        }
        window = glfwCreateWindow(width, height, title, 0, share ? mcWindow : 0);
        glfwDefaultWindowHints();
        if (window == 0) throw new IllegalStateException("Failed to create the panorama window");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);

        // 左ボタンでドラッグすると、ウィンドウを動かせる (枠なしのときのため)
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button != GLFW_MOUSE_BUTTON_LEFT) return;
            if (action == GLFW_PRESS) {
                final double[] x = new double[1];
                final double[] y = new double[1];
                glfwGetCursorPos(win, x, y);
                grabX = x[0];
                grabY = y[0];
                dragging = true;
            } else if (action == GLFW_RELEASE) {
                dragging = false;
            }
        });
        glfwSetCursorPosCallback(window, (win, x, y) -> {
            if (!dragging) return;
            final int[] wx = new int[1];
            final int[] wy = new int[1];
            glfwGetWindowPos(win, wx, wy);
            glfwSetWindowPos(win, wx[0] + (int) (x - grabX), wy[0] + (int) (y - grabY));
        });
    }

    @Override
    void saveCurrent() {
        saved = glfwGetCurrentContext();
    }

    @Override
    void makeCurrent() {
        glfwMakeContextCurrent(window);
    }

    @Override
    void restoreSaved() {
        glfwMakeContextCurrent(saved);
    }

    @Override
    void swapBuffers() {
        glfwSwapBuffers(window);
    }

    @Override
    int[] framebufferSize() {
        final int[] w = new int[1];
        final int[] h = new int[1];
        glfwGetFramebufferSize(window, w, h);
        return new int[]{w[0], h[0]};
    }

    @Override
    int[] windowSize() {
        final int[] w = new int[1];
        final int[] h = new int[1];
        glfwGetWindowSize(window, w, h);
        return new int[]{w[0], h[0]};
    }

    @Override
    boolean minimized() {
        return glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_TRUE;
    }

    @Override
    void setVisible(boolean visible) {
        if (visible) {
            glfwShowWindow(window);
        } else {
            glfwHideWindow(window);
        }
    }

    @Override
    void setDecorated(boolean decorated) {
        glfwSetWindowAttrib(window, GLFW_DECORATED, decorated ? GLFW_TRUE : GLFW_FALSE);
    }

    @Override
    long handle() {
        return window;
    }

    @Override
    boolean dragging() {
        return dragging;
    }
}
