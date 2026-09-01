package marumasa.panorama_window.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static marumasa.panorama_window.PanoramaWindow.CONFIG;
import static marumasa.panorama_window.PanoramaWindow.LOGGER;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL31.*;

public class WindowManager {

    public final long window;

    private final long mcWindow;
    private final GLCapabilities mcCapabilities;
    private GLCapabilities panoramaCapabilities;

    // Updated from the GLFW framebuffer-size callback when the user resizes the window.
    private volatile int window_width;
    private volatile int window_height;

    // Cube-face capture resolution that matches the current window size. Recomputed on resize;
    // PanoramaManager polls faceSize() and reallocates its buffers to match at a sweep boundary,
    // then the resized pixels flow in and uploadFaces() grows/shrinks each GL texture to fit.
    private volatile int faceSize;
    private final int[] texSize = new int[6];
    // Upper bound for faceSize: what the GPU can allocate, hard-capped for VRAM sanity. Large
    // enough that an 8K window (~2160 px/face wanted) captures at full resolution.
    private final int maxFaceSize;

    private WindowPos windowPos;
    private CursorPos cursorPos;
    private boolean isDragging = false;
    // Avoids re-clearing + swapping the window every tick while it is already black.
    private boolean isBlack = false;

    // The last yaw / pitch the window was drawn with; lets renderShader skip a full context switch +
    // redraw when nothing has changed (view held still, no new face data, no screenshot pending).
    private float lastYaw = Float.NaN;
    private float lastPitch = Float.NaN;

    // Set by requestCapture(...); the next shader render writes the panorama window to this file.
    private volatile Path pendingCapture;

    private static final class WindowPos {
        public final int x;
        public final int y;

        public WindowPos(long window) {
            int[] x = new int[1];
            int[] y = new int[1];
            glfwGetWindowPos(window, x, y);
            this.x = x[0];
            this.y = y[0];
        }
    }

    private static final class CursorPos {
        public final double x;
        public final double y;

        public CursorPos(long window) {
            double[] x = new double[1];
            double[] y = new double[1];
            glfwGetCursorPos(window, x, y);
            this.x = x[0];
            this.y = y[0];

        }
    }

    /** Cube-face capture resolution that keeps up with a panorama window this big (px). */
    static int faceSizeFor(int windowWidth, int windowHeight, int maxFaceSize) {
        // The window shows the full 360x180 equirectangular field: 360 deg across the width, 180 deg
        // down the height. A cube face spans 90 deg, so to roughly match the window's angular pixel
        // density a face needs about width/4 (and height/2) pixels; * 1.5 for headroom, since the
        // gnomonic face projection undersamples toward its edges. At 8K (7680x4320) that asks for
        // ~3240 px/face; the cap is the GPU's max texture size (clamped to 8192).
        int ideal = (int) Math.ceil(1.5 * Math.max(windowWidth / 4.0, windowHeight / 2.0));
        int step = 128; // quantise so a slow resize drag doesn't reallocate textures every pixel
        int rounded = ((ideal + step - 1) / step) * step;
        return Math.min(maxFaceSize, Math.max(256, rounded));
    }

    public WindowManager(int width, int height, String title, Minecraft client) {
        window_width = width;
        window_height = height;

        // Remember Minecraft's context so we can hand it back after every operation.
        this.mcWindow = client.getWindow().handle();
        this.mcCapabilities = GL.getCapabilities();

        // GLFW is already initialized by Minecraft; this is just defensive.
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        // Show the window as soon as it is created.
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
        // Let the user resize the panorama window (the shader pass just re-fits to the framebuffer).
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        // Borderless or not, depending on the config.
        glfwWindowHint(GLFW_DECORATED, CONFIG.isBorderless() ? GLFW_FALSE : GLFW_TRUE);

        // Create the window (no monitor, no shared context).
        window = glfwCreateWindow(window_width, window_height, title, 0, 0);
        if (window == 0) {
            glfwDefaultWindowHints();
            throw new IllegalStateException("Failed to create the panorama window");
        }

        // Switch to the panorama context and build its GL capabilities.
        glfwMakeContextCurrent(window);
        this.panoramaCapabilities = GL.createCapabilities();
        // How large a cube face this GPU will let us allocate (clamped so an absurd window size
        // can't try to make a multi-gigabyte texture set). GL guarantees at least 1024.
        int glMaxTexture = glGetInteger(GL_MAX_TEXTURE_SIZE);
        this.maxFaceSize = Math.min(8192, glMaxTexture > 0 ? glMaxTexture : 2048);
        // Disable v-sync for the panorama window.
        glfwSwapInterval(0);
        // Paint it black to start with.
        runBlack();
        isBlack = true;

        // Drag the window around with the left mouse button.
        glfwSetMouseButtonCallback(window, (window, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                if (action == GLFW_PRESS) {
                    isDragging = true;
                    cursorPos = new CursorPos(window);
                } else if (action == GLFW_RELEASE) {
                    isDragging = false;
                }
            }
        });

        glfwSetCursorPosCallback(window, (window, xpos, ypos) -> {
            if (isDragging) {
                windowPos = new WindowPos(window);
                CursorPos currentPos = new CursorPos(window);
                double dx = currentPos.x - cursorPos.x;
                double dy = currentPos.y - cursorPos.y;
                glfwSetWindowPos(window, windowPos.x + (int) dx, windowPos.y + (int) dy);
            }
        });

        // Track the framebuffer size so the shader pass fills the whole window after a resize.
        // (Framebuffer pixels, not screen coordinates - they differ on hi-DPI displays.)
        int[] fbWidth = new int[1];
        int[] fbHeight = new int[1];
        glfwGetFramebufferSize(window, fbWidth, fbHeight);
        window_width = fbWidth[0];
        window_height = fbHeight[0];
        faceSize = faceSizeFor(window_width, window_height, maxFaceSize);
        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            if (w > 0 && h > 0) {
                window_width = w;
                window_height = h;
                faceSize = faceSizeFor(w, h, maxFaceSize);
                // Force the next renderShader to actually redraw at the new size.
                lastYaw = Float.NaN;
            }
        });

        shaderManager = new ShaderManager();

        // VAO and VBO.
        VAO = glGenVertexArrays();
        glBindVertexArray(VAO);

        float[] vertices = {
                -1.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, // bottom left
                1.0f, -1.0f, 0.0f, 1.0f, 0.0f, 0.0f, // bottom right
                1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, // top right
                -1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f  // top left
        };

        VBO = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, VBO);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        // Vertex attributes.
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12);
        glEnableVertexAttribArray(1);


        for (int i = 0; i < textures.length; i++) {
            textures[i] = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textures[i]);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA8,
                    faceSize,
                    faceSize,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    (ByteBuffer) null
            );
            texSize[i] = faceSize;
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            // GL_CLAMP was removed in GL 3.1; CLAMP_TO_EDGE keeps face edges from wrapping.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }

        // The sampler -> texture-unit mapping never changes, so bind it once instead of
        // re-querying the uniform locations on every frame.
        int program = shaderManager.getShaderProgram();
        glUseProgram(program);
        for (int i = 0; i < SAMPLER_NAMES.length; i++) {
            int location = glGetUniformLocation(program, SAMPLER_NAMES[i]);
            if (location >= 0) {
                glUniform1i(location, i);
            }
        }
        uYawLocation = glGetUniformLocation(program, "uYaw");
        uPitchLocation = glGetUniformLocation(program, "uPitch");
        glUseProgram(0);

        // Hand the context back to Minecraft.
        releaseContext();
    }

    int[] textures = new int[6];

    private final int VBO;
    private final int VAO;
    private final ShaderManager shaderManager;
    private final int uYawLocation;
    private final int uPitchLocation;

    // Sampler uniform -> texture unit. Index matches the cube face order produced by PanoramaManager:
    // 0=front(N), 1=right(E), 2=back(S), 3=left(W), 4=top, 5=bottom.
    private static final String[] SAMPLER_NAMES = {
            "texFront", "texRight", "texBack", "texLeft", "texTop", "texBottom"
    };

    private void makeContext() {
        glfwMakeContextCurrent(window);
        GL.setCapabilities(panoramaCapabilities);
    }

    private void releaseContext() {
        glfwMakeContextCurrent(mcWindow);
        GL.setCapabilities(mcCapabilities);
    }

    /** Requests that the next {@link #renderShader} write the panorama window to {@code path} as a PNG. */
    public void requestCapture(Path path) {
        this.pendingCapture = path;
    }

    /** Cube-face capture resolution the current window size wants. Polled by {@link PanoramaManager}. */
    public int faceSize() {
        return faceSize;
    }

    /** The panorama window's current size in screen coordinates (not framebuffer pixels). */
    public int[] windowSize() {
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetWindowSize(window, w, h);
        return new int[]{w[0], h[0]};
    }

    /**
     * Redraws the panorama window.
     *
     * @param buffers        the six CPU face buffers
     * @param incomingFaceSize edge length (px) of every buffer in {@code buffers}
     * @param yawRadians      horizontal view rotation (0 = "front"/north face centred)
     * @param pitchRadians    vertical view rotation (0 = horizon level)
     * @param dirtyMask       bit {@code i} set => face {@code i} has new pixels to upload
     */
    public void renderShader(ByteBuffer[] buffers, int incomingFaceSize,
                             float yawRadians, float pitchRadians, int dirtyMask) {
        boolean changed = dirtyMask != 0
                || pendingCapture != null
                || isBlack
                || Float.isNaN(lastYaw)
                || Float.isNaN(lastPitch)
                || Math.abs(yawRadians - lastYaw) > 1.0e-4f
                || Math.abs(pitchRadians - lastPitch) > 1.0e-4f
                || isDragging;
        if (!changed) return;

        makeContext();
        try {
            glViewport(0, 0, window_width, window_height);

            glClear(GL_COLOR_BUFFER_BIT);

            glUseProgram(shaderManager.getShaderProgram());
            if (uYawLocation >= 0) {
                glUniform1f(uYawLocation, yawRadians);
            }
            if (uPitchLocation >= 0) {
                glUniform1f(uPitchLocation, pitchRadians);
            }

            uploadFaces(buffers, dirtyMask, incomingFaceSize);

            glBindVertexArray(VAO);
            glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

            Path capture = pendingCapture;
            if (capture != null) {
                pendingCapture = null;
                writeWindowPng(capture);
            }

            glfwSwapBuffers(window);
            isBlack = false;
            lastYaw = yawRadians;
            lastPitch = pitchRadians;
        } finally {
            releaseContext();
        }
    }

    /** True while the panorama window is minimized (nothing on screen to update). */
    public boolean isMinimized() {
        return glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_TRUE;
    }

    private void writeWindowPng(Path path) {
        int w = window_width;
        int h = window_height;
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        glReadBuffer(GL_BACK);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

        try (NativeImage image = new NativeImage(w, h, false)) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    // glReadPixels is bottom-up; NativeImage is top-down.
                    image.setPixelABGR(x, h - 1 - y, pixels.getInt((x + y * w) * 4) | 0xFF000000);
                }
            }
            if (path.toAbsolutePath().getParent() != null) {
                Files.createDirectories(path.toAbsolutePath().getParent());
            }
            image.writeToFile(path);
            LOGGER.info("Wrote panorama window screenshot to {}", path.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Couldn't write panorama window screenshot", e);
        }
    }

    public void renderBlack() {
        if (isBlack) return;
        makeContext();
        try {
            glViewport(0, 0, window_width, window_height);
            runBlack();
            isBlack = true;
            lastYaw = Float.NaN;
            lastPitch = Float.NaN;
        } finally {
            releaseContext();
        }
    }

    private void runBlack() {
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f); // clear colour
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the screen
        glfwSwapBuffers(window);
    }

    private void uploadFaces(ByteBuffer[] buffers, int dirtyMask, int incomingFaceSize) {
        for (int i = 0; i < 6; i++) {
            if ((dirtyMask & (1 << i)) == 0) continue;
            glActiveTexture(GL_TEXTURE0 + i);
            glBindTexture(GL_TEXTURE_2D, textures[i]);
            if (texSize[i] != incomingFaceSize) {
                // Window was resized: grow/shrink this face's texture to the new capture resolution.
                // Faces not yet re-captured keep their old texture (sampled with normalised coords,
                // so it still displays fine) until their pixels arrive.
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, incomingFaceSize, incomingFaceSize, 0,
                        GL_RGBA, GL_UNSIGNED_BYTE, buffers[i]);
                texSize[i] = incomingFaceSize;
            } else {
                // Stream the new pixels into the already-allocated texture instead of
                // reallocating its storage every frame with glTexImage2D.
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, incomingFaceSize, incomingFaceSize,
                        GL_RGBA, GL_UNSIGNED_BYTE, buffers[i]);
            }
        }
    }
}
