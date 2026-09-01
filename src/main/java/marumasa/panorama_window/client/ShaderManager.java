package marumasa.panorama_window.client;

import marumasa.panorama_window.util.ModResource;

import static marumasa.panorama_window.PanoramaWindow.LOGGER;
import static org.lwjgl.opengl.GL31.*;

public class ShaderManager {

    private final int shaderProgram;

    public ShaderManager() {
        // The GL capabilities for this context are set up by WindowManager before this runs.
        String vertexShaderSource, fragmentShaderSource;
        vertexShaderSource = ModResource.readString("shaders/quad.vsh");
        fragmentShaderSource = ModResource.readString("shaders/panorama.fsh");

        // バーテックスシェーダー コンパイル
        final int vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, vertexShaderSource);
        glCompileShader(vertexShader);
        // コンパイルエラーのチェック
        if (glGetShaderi(vertexShader, GL_COMPILE_STATUS) == GL_FALSE)
            LOGGER.warn(glGetShaderInfoLog(vertexShader, 1024));

        // フラグメントシェーダー コンパイル
        final int fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentShaderSource);
        glCompileShader(fragmentShader);
        // コンパイルエラーのチェック
        if (glGetShaderi(fragmentShader, GL_COMPILE_STATUS) == GL_FALSE)
            LOGGER.warn(glGetShaderInfoLog(fragmentShader, 1024));

        // シェーダーリンク
        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, vertexShader);
        glAttachShader(shaderProgram, fragmentShader);
        glLinkProgram(shaderProgram);
        // リンクエラーのチェック
        if (glGetProgrami(shaderProgram, GL_LINK_STATUS) == GL_FALSE)
            LOGGER.warn(glGetProgramInfoLog(shaderProgram, 1024));
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
    }

    public int getShaderProgram() {
        return shaderProgram;
    }
}
