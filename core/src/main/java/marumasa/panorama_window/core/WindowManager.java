package marumasa.panorama_window.core;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import marumasa.panorama_window.core.spout.SpoutSender;
import org.lwjgl.opengl.GLCapabilities;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * パノラマを映す、Minecraft とは別のウィンドウ。
 *
 * <p>Minecraft が OpenGL を使っているときは、Minecraft のコンテキストと共有したコンテキストを作り、
 * Minecraft 側で作った面のテクスチャ ({@link GlFaces}) をそのまま読む。Minecraft が Vulkan を使っているとき
 * (26.2 以降で選べる) は共有できないので、自分のテクスチャを持ち、CPU に読み出した面をここへ送る。
 */
public final class WindowManager {

    // 面のテクスチャのユニット。並びは取り込みの面の並びと同じ: 0=前(北) 1=右(東) 2=後(南) 3=左(西) 4=上 5=下
    private static final String[] SAMPLER_NAMES = {
            "texFront", "texRight", "texBack", "texLeft", "texTop", "texBottom"
    };

    private final Surface surface = Surface.forThisMinecraft();
    private final long window;

    private final boolean shared;
    private final GLCapabilities capabilities;
    private final boolean hasVertexArrays;
    private final ShaderProgram shader;
    private final int vertexArray;
    private final int vertexBuffer;
    private final int uYaw;
    private final int uPitch;
    private final int uLod;
    private final int maxFaceSize;

    // ウィンドウの描画先の大きさ (ピクセル)。大きさを変えると更新される
    private volatile int width;
    private volatile int height;
    // 今のウィンドウの大きさに合う面の解像度
    private volatile int faceSize;

    // 共有しないときに使う自分のテクスチャ
    private final int[] ownTextures = new int[6];
    private final int[] ownSizes = new int[6];


    // 黒く塗ってあるか (毎 tick 塗り直さないため)
    private boolean black;
    // 前回描いたときの向き。何も変わっていなければ描き直さない
    private float lastYaw = Float.NaN;
    private float lastPitch = Float.NaN;

    // 次に描いたときに、ウィンドウの中身をこのファイルに書き出す (動作確認用)
    private volatile Path pendingCapture;

    /**
     * @param mcWindow Minecraft のウィンドウ
     * @param share    Minecraft の OpenGL コンテキストと共有するか
     */
    public WindowManager(Config config, long mcWindow, boolean share) {
        this.shared = share;
        this.visible = config.isShowWindow();
        final GLCapabilities previousCapabilities = currentCapabilities();
        surface.saveCurrent();
        surface.create("Panorama", Math.max(64, config.getWidth()), Math.max(64, config.getHeight()),
                config.isShowWindow(), !config.isBorderless(), mcWindow, share);
        window = surface.handle();
        capabilities = GL.createCapabilities();
        try {
            hasVertexArrays = capabilities.OpenGL30;
            final boolean modernShaders = capabilities.OpenGL32;
            final int glMaxTexture = glGetInteger(GL_MAX_TEXTURE_SIZE);
            maxFaceSize = Math.min(8192, glMaxTexture > 0 ? glMaxTexture : 2048);

            shader = new ShaderProgram(modernShaders);
            vertexArray = hasVertexArrays ? glGenVertexArrays() : 0;
            if (hasVertexArrays) glBindVertexArray(vertexArray);
            // 画面全体を覆う四角形 (位置 xyz と テクスチャ座標 uv)
            final FloatBuffer vertices = BufferUtils.createFloatBuffer(20);
            vertices.put(new float[]{
                    -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
                    1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
                    1.0f, 1.0f, 0.0f, 1.0f, 1.0f,
                    -1.0f, 1.0f, 0.0f, 0.0f, 1.0f
            }).flip();
            vertexBuffer = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
            setUpAttributes();

            final int program = shader.id();
            glUseProgram(program);
            for (int i = 0; i < SAMPLER_NAMES.length; i++) {
                final int location = glGetUniformLocation(program, SAMPLER_NAMES[i]);
                if (location >= 0) glUniform1i(location, i);
            }
            uYaw = glGetUniformLocation(program, "uYaw");
            uPitch = glGetUniformLocation(program, "uPitch");
            uLod = glGetUniformLocation(program, "uLod");
            glUseProgram(0);

            final int[] size = surface.framebufferSize();
            width = size[0];
            height = size[1];
            faceSize = faceSizeFor(width, height, maxFaceSize);

            clearBlack();
            black = true;
        } finally {
            restore(previousCapabilities);
        }
    }

    // 描画先の大きさが変わっていたら覚え直す
    private boolean pollSize() {
        final int[] size = surface.framebufferSize();
        if (size[0] <= 0 || size[1] <= 0 || (size[0] == width && size[1] == height)) return false;
        width = size[0];
        height = size[1];
        faceSize = faceSizeFor(width, height, maxFaceSize);
        return true;
    }

    /** このウィンドウの大きさで見て粗くならない、面の解像度 (ピクセル) */
    static int faceSizeFor(int windowWidth, int windowHeight, int maxFaceSize) {
        // ウィンドウは横 360 度・縦 180 度を映し、面は 90 度ぶん。面の中央で同じ細かさにするには、
        // 幅の 1/4 (高さの 1/2) が要る。面の端は粗くなるので 1.5 倍の余裕を持たせる
        final int ideal = (int) Math.ceil(1.5 * Math.max(windowWidth / 4.0, windowHeight / 2.0));
        final int step = 128; // ゆっくり大きさを変えている間に、毎ピクセル作り直さないよう丸める
        final int rounded = ((ideal + step - 1) / step) * step;
        return Math.min(maxFaceSize, Math.max(256, rounded));
    }

    /** 今のウィンドウの大きさに合う面の解像度 */
    public int faceSize() {
        return faceSize;
    }

    /** OpenGL で確保できる面の最大の大きさ */
    public int maxFaceSize() {
        return maxFaceSize;
    }

    /** ウィンドウの大きさ (画面の座標で、描画先のピクセル数ではない) */
    public int[] windowSize() {
        return surface.windowSize();
    }

    public boolean isMinimized() {
        return surface.minimized();
    }

    /** ウィンドウ (GLFW なら GLFWwindow*、SDL3 なら SDL_Window*) */
    public long handle() {
        return window;
    }

    /** 次に描いたときに、ウィンドウの中身を PNG に書き出す */
    public void requestCapture(Path path) {
        pendingCapture = path;
    }

    /**
     * 共有しないとき、CPU に読み出した面をウィンドウのテクスチャに送る。
     *
     * @param rgba 下の行から並んだ RGBA
     */
    public void uploadFace(int face, ByteBuffer rgba, int size) {
        final GLCapabilities previousCapabilities = currentCapabilities();
        surface.saveCurrent();
        makeCurrent();
        try {
            glActiveTexture(GL_TEXTURE0);
            if (ownTextures[face] == 0) ownTextures[face] = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, ownTextures[face]);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            if (ownSizes[face] != size) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                ownSizes[face] = size;
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
            }
            glGenerateMipmap(GL_TEXTURE_2D);
            glBindTexture(GL_TEXTURE_2D, 0);
        } finally {
            restore(previousCapabilities);
        }
    }

    /**
     * パノラマを描き直す。ウィンドウを隠しているときも、Spout2 で送るなら描く。
     *
     * @param faces      共有しているときの面のテクスチャ (共有していなければ無視する)
     * @param faceSize   面の大きさ (ピクセル)
     * @param yaw        横の向き (ラジアン、0 で北の面が中央)
     * @param pitch      縦の向き (ラジアン、0 で水平)
     * @param newPixels  前回から新しくなった面があるか
     */
    public void render(int[] faces, int faceSize, float yaw, float pitch, boolean newPixels) {
        final boolean resized = pollSize();
        final boolean changed = newPixels
                || resized
                || pendingCapture != null
                || black
                || surface.dragging()
                || spoutDirty
                || Float.isNaN(lastYaw)
                || Math.abs(yaw - lastYaw) > 1.0e-4f
                || Math.abs(pitch - lastPitch) > 1.0e-4f;
        if (!changed) return;
        final boolean toWindow = visible && !isMinimized();
        if (!toWindow && !spoutWanted) return;

        final GLCapabilities previousCapabilities = currentCapabilities();
        surface.saveCurrent();
        makeCurrent();
        try {
            final int[] textures = shared ? faces : ownTextures;
            if (toWindow) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                drawPanorama(textures, faceSize, yaw, pitch, width, height);
                final Path capture = pendingCapture;
                if (capture != null) {
                    pendingCapture = null;
                    writePng(capture);
                }
                surface.swapBuffers();
            }
            spoutDirty = false;
            if (spoutWanted) sendSpout(textures, faceSize, yaw, pitch);
            black = false;
            lastYaw = yaw;
            lastPitch = pitch;
        } finally {
            restore(previousCapabilities);
        }
    }

    // 今結び付いている描画先に、w x h の大きさでパノラマを描く
    private void drawPanorama(int[] textures, int faceSize, float yaw, float pitch, int w, int h) {
        glViewport(0, 0, w, h);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        glUseProgram(shader.id());
        if (uYaw >= 0) glUniform1f(uYaw, yaw);
        if (uPitch >= 0) glUniform1f(uPitch, pitch);
        if (uLod >= 0) glUniform1f(uLod, lodFor(faceSize, w, h));

        for (int i = 0; i < 6; i++) {
            glActiveTexture(GL_TEXTURE0 + i);
            glBindTexture(GL_TEXTURE_2D, textures[i]);
        }

        if (hasVertexArrays) {
            glBindVertexArray(vertexArray);
        } else {
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            setUpAttributes();
        }
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        for (int i = 5; i >= 0; i--) {
            glActiveTexture(GL_TEXTURE0 + i);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
        glUseProgram(0);
    }

    // ---------- Spout2 ----------

    // 設定画面などから変えられる、Spout2 の送り方 (次に描くときに反映する)
    private volatile boolean spoutWanted;
    private volatile String spoutName = Config.DEFAULT_SPOUT_NAME;
    private volatile int spoutWidth = 3840;
    private volatile boolean spoutDirty;
    private volatile String spoutStatus = "";
    private SpoutSender spout;
    private boolean spoutFailed;
    private int spoutFramebuffer;
    private int spoutTexture;
    private int spoutTextureWidth;

    /** Spout2 で送るかどうかと、送り方を変える */
    public void configureSpout(boolean enabled, String name, int outputWidth) {
        if (!name.equals(spoutName) || enabled != spoutWanted) spoutFailed = false;
        spoutWanted = enabled;
        spoutName = name;
        spoutWidth = outputWidth;
        spoutDirty = true;
        if (!enabled) spoutStatus = "";
    }

    /** Spout2 の状態 (設定画面に出す)。先頭が "!" ならうまく行っていない */
    public String spoutStatus() {
        return spoutStatus;
    }

    private void sendSpout(int[] textures, int faceSize, float yaw, float pitch) {
        if (spoutFailed) return;
        try {
            if (spout != null && !spout.requestedName().equals(spoutName)) {
                spout.close();
                spout = null;
            }
            if (spout == null) {
                if (!SpoutSender.available()) {
                    spoutFailed = true;
                    spoutStatus = "!" + Lang.get("ui.spout.unavailable");
                    return;
                }
                spout = new SpoutSender(spoutName);
                Log.info("Spout2 sender \"{}\" started", spout.name());
            }
            final int w = spoutWidth;
            final int h = w / 2;
            if (spoutTexture == 0 || spoutTextureWidth != w) {
                if (spoutTexture != 0) glDeleteTextures(spoutTexture);
                spoutTexture = glGenTextures();
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, spoutTexture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glBindTexture(GL_TEXTURE_2D, 0);
                if (spoutFramebuffer == 0) spoutFramebuffer = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, spoutFramebuffer);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, spoutTexture, 0);
                spoutTextureWidth = w;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, spoutFramebuffer);
            drawPanorama(textures, faceSize, yaw, pitch, w, h);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (spout.send(spoutTexture, w, h)) {
                spoutStatus = Lang.get("ui.spout.sending", spout.name(), w, h);
            }
        } catch (RuntimeException | LinkageError e) {
            spoutFailed = true;
            spoutStatus = "!" + Lang.get("ui.spout.failed");
            Log.error("Spout2 output failed", e);
            closeSpout();
        }
    }

    private void closeSpout() {
        if (spout != null) {
            try {
                spout.close();
            } catch (RuntimeException | LinkageError e) {
                Log.error("Couldn't close the Spout2 sender", e);
            }
            spout = null;
        }
    }

    /** Spout2 で送るのをやめる (ゲームの終了時など)。ウィンドウのコンテキストで後片付けする */
    public void stopSpout() {
        spoutWanted = false;
        final GLCapabilities previousCapabilities = currentCapabilities();
        surface.saveCurrent();
        makeCurrent();
        try {
            closeSpout();
        } finally {
            restore(previousCapabilities);
        }
    }

    // ---------- ウィンドウの見た目 ----------

    private volatile boolean visible = true;

    /** ウィンドウを表示する・隠す */
    public void setVisible(boolean value) {
        if (value == visible) return;
        visible = value;
        surface.setVisible(value);
        if (value) lastYaw = Float.NaN;
    }

    /** 枠とタイトルバーを付ける・外す */
    public void setDecorated(boolean decorated) {
        surface.setDecorated(decorated);
    }
    /** ウィンドウを黒く塗る (ワールドに入っていないとき) */
    public void renderBlack() {
        if (black) return;
        final GLCapabilities previousCapabilities = currentCapabilities();
        surface.saveCurrent();
        makeCurrent();
        try {
            glViewport(0, 0, width, height);
            clearBlack();
            black = true;
            lastYaw = Float.NaN;
            lastPitch = Float.NaN;
        } finally {
            restore(previousCapabilities);
        }
    }

    // ウィンドウの 1 ピクセルに入る、面の中央のテクセルの数から、ミップマップの段を決める
    private static float lodFor(int faceSize, int width, int height) {
        final double texelsPerPixel = Math.max(4.0 * faceSize / Math.max(1, width), 2.0 * faceSize / Math.max(1, height));
        return (float) Math.max(0.0, Math.log(texelsPerPixel) / Math.log(2.0));
    }

    private void setUpAttributes() {
        glVertexAttribPointer(ShaderProgram.ATTRIBUTE_POSITION, 3, GL_FLOAT, false, 20, 0);
        glEnableVertexAttribArray(ShaderProgram.ATTRIBUTE_POSITION);
        glVertexAttribPointer(ShaderProgram.ATTRIBUTE_TEXCOORD, 2, GL_FLOAT, false, 20, 12);
        glEnableVertexAttribArray(ShaderProgram.ATTRIBUTE_TEXCOORD);
    }

    private void clearBlack() {
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        surface.swapBuffers();
    }

    private void writePng(Path path) {
        final int w = width;
        final int h = height;
        final ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        glReadBuffer(GL_BACK);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        final BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int i = (x + y * w) * 4;
                final int rgb = ((pixels.get(i) & 0xFF) << 16) | ((pixels.get(i + 1) & 0xFF) << 8) | (pixels.get(i + 2) & 0xFF);
                // glReadPixels は下の行から
                image.setRGB(x, h - 1 - y, rgb);
            }
        }
        try {
            final Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            ImageIO.write(image, "png", path.toFile());
            Log.info("Wrote panorama window screenshot to {}", path.toAbsolutePath());
        } catch (IOException e) {
            Log.error("Couldn't write panorama window screenshot", e);
        }
    }

    private void makeCurrent() {
        surface.makeCurrent();
        GL.setCapabilities(capabilities);
    }

    private void restore(GLCapabilities caps) {
        surface.restoreSaved();
        GL.setCapabilities(caps);
    }

    private static GLCapabilities currentCapabilities() {
        try {
            return GL.getCapabilities();
        } catch (IllegalStateException e) {
            // Minecraft が OpenGL を使っていない (Vulkan など)
            return null;
        }
    }
}
