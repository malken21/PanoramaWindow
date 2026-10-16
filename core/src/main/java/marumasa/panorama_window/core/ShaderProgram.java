package marumasa.panorama_window.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL20.*;

/**
 * パノラマウィンドウのシェーダー。ソースは GLSL 1.50 で書いてあり、OpenGL 2.1 しか使えないコンテキスト
 * (macOS の Minecraft 1.14 - 1.16 など) では GLSL 1.20 の書き方に直してからコンパイルする。
 */
final class ShaderProgram {

    static final int ATTRIBUTE_POSITION = 0;
    static final int ATTRIBUTE_TEXCOORD = 1;

    private final int program;

    ShaderProgram(boolean modern) {
        String vertex = read("shaders/quad.vsh");
        String fragment = read("shaders/panorama.fsh");
        if (modern) {
            vertex = "#version 150\n" + vertex;
            fragment = "#version 150\nout vec4 FragColor;\n#define SAMPLE(t, c) textureLod(t, c, uLod)\n" + fragment;
        } else {
            // in / out の宣言は行の先頭に書いてある。GLSL 1.20 のフラグメントシェーダーではミップマップの段を指定できない
            vertex = "#version 120\n" + vertex.replaceAll("(?m)^in ", "attribute ").replaceAll("(?m)^out ", "varying ");
            fragment = "#version 120\n#define SAMPLE(t, c) texture2D(t, c)\n" + fragment.replaceAll("(?m)^in ", "varying ")
                    .replace("FragColor", "gl_FragColor");
        }

        final int vertexShader = compile(GL_VERTEX_SHADER, vertex);
        final int fragmentShader = compile(GL_FRAGMENT_SHADER, fragment);
        program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glBindAttribLocation(program, ATTRIBUTE_POSITION, "inPosition");
        glBindAttribLocation(program, ATTRIBUTE_TEXCOORD, "inTexCoord");
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            Log.warn("Panorama shader failed to link: {}", glGetProgramInfoLog(program, 1024));
        }
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
    }

    int id() {
        return program;
    }

    private static int compile(int type, String source) {
        final int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            Log.warn("Panorama shader failed to compile: {}", glGetShaderInfoLog(shader, 1024));
        }
        return shader;
    }

    static String read(String path) {
        try (InputStream in = ShaderProgram.class.getResourceAsStream("/assets/panorama_window/" + path)) {
            if (in == null) throw new IllegalStateException("Resource not found: " + path);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
