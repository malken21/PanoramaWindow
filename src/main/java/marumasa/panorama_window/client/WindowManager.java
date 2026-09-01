package marumasa.panorama_window.client;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;

import static marumasa.panorama_window.PanoramaWindow.CONFIG;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL31.*;

public class WindowManager {

    public final long window;

    private final long mcWindow;
    private final GLCapabilities mcCapabilities;
    private GLCapabilities panoramaCapabilities;

    private final int window_width;
    private final int window_height;
    private final int camera_size;

    private WindowPos windowPos;
    private CursorPos cursorPos;
    private boolean isDragging = false;

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

    public WindowManager(int width, int height, String title, int camera_size, Minecraft client) {
        window_width = width;
        window_height = height;
        this.camera_size = camera_size;

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
        // The panorama window has a fixed size.
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
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
        // Disable v-sync for the panorama window.
        glfwSwapInterval(0);
        // Paint it black to start with.
        runBlack();

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
                    this.camera_size,
                    this.camera_size,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    (ByteBuffer) null
            );
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);
        }

        // Hand the context back to Minecraft.
        releaseContext();
    }

    int[] textures = new int[6];

    private final int VBO;
    private final int VAO;
    private final ShaderManager shaderManager;

    // Sampler uniform -> texture unit. Index matches the cube face order produced by PanoramaManager:
    // 0=front, 1=right, 2=back, 3=left, 4=top, 5=bottom.
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

    public void renderShader(ByteBuffer[] buffers) {
        makeContext();
        try {
            glViewport(0, 0, window_width, window_height);

            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            int program = shaderManager.getShaderProgram();
            glUseProgram(program);

            uploadFaces(buffers, camera_size, camera_size);
            bindSamplers(program);

            glBindVertexArray(VAO);
            glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

            glfwSwapBuffers(window);
        } finally {
            releaseContext();
        }
    }

    public void renderBlack() {
        makeContext();
        try {
            glViewport(0, 0, window_width, window_height);
            runBlack();
        } finally {
            releaseContext();
        }
    }

    private void runBlack() {
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f); // clear colour
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the screen
        glfwSwapBuffers(window);
    }

    private void bindSamplers(int program) {
        for (int i = 0; i < SAMPLER_NAMES.length; i++) {
            int location = glGetUniformLocation(program, SAMPLER_NAMES[i]);
            if (location >= 0) {
                glUniform1i(location, i);
            }
        }
    }

    private void uploadFaces(ByteBuffer[] buffers, int width, int height) {
        for (int i = 0; i < 6; i++) {
            glActiveTexture(GL_TEXTURE0 + i);
            glBindTexture(GL_TEXTURE_2D, textures[i]);
            // upload the face data
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffers[i]);
        }
    }
}
