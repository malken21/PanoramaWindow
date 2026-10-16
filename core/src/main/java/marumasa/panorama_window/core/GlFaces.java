package marumasa.panorama_window.core;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30.*;

/**
 * 立方体の 6 面のテクスチャ。Minecraft の OpenGL コンテキストで作り、パノラマウィンドウ (コンテキストを共有している) が
 * そのまま読む。面を描いたら、メインの描画先から GPU の中だけでコピーする (CPU への読み出しも再転送もない)。
 *
 * <p>Minecraft はテクスチャや FBO の結び付けを覚えておく (GlStateManager) ので、ここで変えた結び付けは必ず元に戻す。
 * どのバージョンの Minecraft の描画の仕組みとも関わらないよう、生の OpenGL だけを使う。
 */
final class GlFaces {

    private final int[] textures = new int[6];
    private int size;
    private int readFramebuffer;

    /** 面のテクスチャの名前 (0 = まだ無い)。並びは 0=前(北) 1=右(東) 2=後(南) 3=左(西) 4=上 5=下 */
    int[] textures() {
        return textures;
    }

    int size() {
        return size;
    }

    /** 面の大きさが変わったら作り直す */
    void ensure(int newSize) {
        if (newSize == size && textures[0] != 0) return;
        delete();
        size = newSize;

        final int previousUnit = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        final int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        final int previousUnpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            // PBO が結び付いたままだと、null が「PBO の先頭」と解釈されてしまう
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < 6; i++) {
                textures[i] = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, textures[i]);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
                // ウィンドウが小さいときに縮小してもちらつかないよう、ミップマップを使う
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glGenerateMipmap(GL_TEXTURE_2D);
            }
        } finally {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, previousUnpack);
            glBindTexture(GL_TEXTURE_2D, previousTexture);
            glActiveTexture(previousUnit);
        }
    }

    /**
     * メインの描画先の色のテクスチャ {@code source} の (x, y) から、面の大きさの正方形を面 {@code face} にコピーする。
     */
    void copy(int face, int source, int x, int y) {
        final int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        final int previousUnit = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        final int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            if (readFramebuffer == 0) readFramebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, source, 0);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            glBindTexture(GL_TEXTURE_2D, textures[face]);
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, x, y, size, size);
            glGenerateMipmap(GL_TEXTURE_2D);
            // Minecraft のテクスチャを掴んだままにしない
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        } finally {
            glBindTexture(GL_TEXTURE_2D, previousTexture);
            glActiveTexture(previousUnit);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
        }
    }

    /** 面のテクスチャを PNG に書き出す (動作確認用。Minecraft のコンテキストで呼ぶ) */
    void dump(java.nio.file.Path dir) {
        final int previousUnit = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        final int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        final ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(size * size * 4);
        try {
            for (int face = 0; face < 6; face++) {
                if (textures[face] == 0) continue;
                glBindTexture(GL_TEXTURE_2D, textures[face]);
                glPixelStorei(GL_PACK_ALIGNMENT, 1);
                pixels.clear();
                glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                final java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        final int i = (x + y * size) * 4;
                        image.setRGB(x, size - 1 - y, ((pixels.get(i) & 0xFF) << 16) | ((pixels.get(i + 1) & 0xFF) << 8) | (pixels.get(i + 2) & 0xFF));
                    }
                }
                try {
                    java.nio.file.Files.createDirectories(dir);
                    javax.imageio.ImageIO.write(image, "png", dir.resolve("face-" + face + ".png").toFile());
                } catch (java.io.IOException e) {
                    Log.error("Couldn't write a face", e);
                }
            }
        } finally {
            glBindTexture(GL_TEXTURE_2D, previousTexture);
            glActiveTexture(previousUnit);
        }
    }

    void delete() {
        for (int i = 0; i < 6; i++) {
            if (textures[i] != 0) {
                glDeleteTextures(textures[i]);
                textures[i] = 0;
            }
        }
        size = 0;
    }
}
