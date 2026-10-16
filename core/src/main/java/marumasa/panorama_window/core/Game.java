package marumasa.panorama_window.core;

import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Minecraft の、バージョンによって違う部分。adapters 側でバージョンごとに実装する。
 * どのメソッドもメインスレッド (描画スレッド) から呼ぶ。
 */
public interface Game {

    /** ゲームのフォルダ (.minecraft など) */
    Path gameDirectory();

    /** Minecraft のバージョン (ログ用) */
    String version();

    /** Minecraft のウィンドウ (GLFW のハンドル)。まだ無ければ 0 */
    long windowHandle();

    /** Minecraft がメインのウィンドウに OpenGL を使っているか (26.2 以降は Vulkan のこともある) */
    boolean usesOpenGl();

    // ---------- プレイヤーとワールド ----------

    /** ワールドに入っていて、プレイヤーがいるか */
    boolean inWorld();

    /** 今のワールド (ディメンションの切り替えを見分けるのに使う) */
    Object level();

    boolean playerAlive();

    boolean paused();

    double playerX();

    double playerY();

    double playerZ();

    /** プレイヤーの体の向き (度)。頭の向きではない */
    float yaw();

    float pitch();

    /** 1 tick 前の向き (補間用) */
    float yawO();

    float pitchO();

    /** プレイヤーの向きを今と 1 tick 前の両方とも書き換える */
    void setRotation(float yaw, float pitch, float yawO, float pitchO);

    /** フレームの tick 内での位置 (0..1) */
    float partialTick();

    // ---------- 取り込み ----------

    /** Minecraft のメインの描画先の幅 (ピクセル) */
    int framebufferWidth();

    int framebufferHeight();

    /** 影 Mod のシェーダーパックが有効か */
    boolean shaderPackActive();

    /** 描画先の作り直しに弱い描画方式 (Fabulous! など) を使っているか */
    boolean fragileTargets();

    /**
     * 取り込みを始める。手を描かない・ブロックの枠を描かない・視点の揺れなし、にする。
     * FOV は {@link Panorama#captureFov()} を Mixin で返す。
     *
     * @param size 0 ならメインの描画先の大きさを変えない (中央を切り出す)。正なら描画先をこの大きさの正方形にする
     */
    void beginCapture(int size);

    /** 今のプレイヤーの向きで、ワールドを 1 回メインの描画先に描く */
    void renderLevel();

    /** {@link #beginCapture} で変えたものを元に戻す */
    void endCapture();

    /**
     * メインの描画先の色のテクスチャ (OpenGL の名前)。{@link #usesOpenGl()} が false なら -1。
     * 描画先を作り直すと変わるので、描くたびに取り直す。
     */
    int mainColorTexture();

    /**
     * OpenGL 以外のときに、メインの描画先の一部を CPU 側に読み出す。読み出しが終わると、メインスレッドで
     * {@code sink} が呼ばれる (RGBA、下の行から)。対応していなければ false。
     */
    boolean readMainColor(int x, int y, int size, PixelSink sink);

    interface PixelSink {
        void accept(ByteBuffer rgba);
    }

    // ---------- 設定画面 ----------

    /** 設定画面を開く */
    void openSettings();

    /** 設定画面が開いていれば閉じる */
    void closeSettings();

    // ---------- 動作確認用 ----------

    /** ゲームを終了する */
    void quit();
}
