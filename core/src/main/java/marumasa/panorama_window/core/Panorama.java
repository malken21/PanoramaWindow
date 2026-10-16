package marumasa.panorama_window.core;

import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * プレイヤーの周りを、ワールドの軸に揃えた立方体の 6 面として描き、別ウィンドウに 360 度のパノラマとして映す。
 *
 * <p>面は東西南北と上下に固定してあり、プレイヤーの向きには付いてこない。そのため向きを変えるだけなら描き直しは要らず、
 * ウィンドウのシェーダーが見る方向を回すだけで済む。ワールドを描き直すのは、中身が変わりうるとき (動いた、時間が経った)
 * だけで、そのときも 6 面を何 tick かに分けて ({@code FacesPerCapture} 面ずつ) 描くので、6 回分の描画が 1 フレームに
 * 集中しない。
 *
 * <p>面を描く方法は 2 つある ({@link Config.Mode})。
 * <ul>
 *   <li>切り出し: メインの描画先の大きさを変えずに FOV 90 度で描き、中央の正方形を使う。描画先を作り直さないので、
 *       影 Mod (Iris / OptiFine など) や Fabulous! の描画先がそのまま使える。</li>
 *   <li>作り直し: 描画先を面の大きさの正方形に作り直して描く。ゲーム画面より細かく取り込める。</li>
 * </ul>
 */
public final class Panorama {

    private static volatile boolean taking;
    /** 取り込みの最中のビルボード (名札など) の向き (度)。6 面すべてで同じにする */
    private static volatile float billboardYaw = 180.0F;

    private static Panorama instance;

    /** 面を描いている最中か。ビルボードの Mixin はこの間だけ働く */
    public static boolean isTaking() {
        return taking;
    }

    /**
     * 取り込みの最中に、ビルボードを向ける方向 (Minecraft の yaw、度)。一巡の始まりのプレイヤーの向きで、
     * 6 面すべてで同じ。面ごとの向きに合わせると、面の境目にかかったビルボードが裂けて見える。
     */
    public static float billboardYaw() {
        return billboardYaw;
    }

    /**
     * 取り込みの最中の縦の FOV (度)。取り込み中でなければ 0。FOV の Mixin がこれを返す。
     * 面より広く描くとき (オーバースキャン) は 90 度より大きい。
     */
    public static float captureFov() {
        return taking ? captureFov : 0.0F;
    }

    private static volatile float captureFov = 90.0F;

    public static Panorama get() {
        return instance;
    }

    /** 影 Mod を使っているときの、面と面の間の最小の間隔 (tick) と、ワールドに入ってから待つ時間 (tick) */
    private static final int SHADER_MIN_INTERVAL = 6;
    private static final int SHADER_SETTLE_TICKS = 100;

    /** 動いていない間も、時間や Mob の変化を拾うため、この間隔 (tick) で描き直す */
    private static final int IDLE_RESWEEP_TICKS = 40;

    private final Game game;
    private final Config config;
    private final SelfTest selfTest;

    private WindowManager window;
    private boolean failed;
    private GlFaces glFaces;

    // 面の大きさと、メインの描画先のどこを切り出すか
    private int faceSize;
    private int resizeTo;
    private int cropX;
    private int cropY;
    // ウィンドウにまだ映していない新しい面があるか
    private boolean newPixels;
    // 次に描く面 (順番に回す)
    private int nextFace;
    private int ticksSinceCapture;
    private int capturedFaces;
    private long completedSweeps;

    private double lastX = Double.NaN;
    private double lastY;
    private double lastZ;
    private float lastYaw;
    private int idleTicks;

    // ディメンションを移ると、ワールドの描画の準備がやり直しになるので、少し待ってから描く
    private Object lastLevel;
    private int settleTicks;

    public Panorama(Game game) {
        this.game = game;
        this.config = new Config(game.gameDirectory().resolve("config").resolve("panorama_window.json"));
        this.selfTest = SelfTest.fromSystemProperty(game);
        instance = this;
        Log.info("PanoramaWindow for Minecraft {}", game.version());
    }

    /** 全体の一巡を何回終えたか (動作確認用) */
    public long completedSweeps() {
        return completedSweeps;
    }

    /** 今の取り込みの状態 (設定画面と動作確認用) */
    public String describe() {
        return "faceSize=" + faceSize + "\nmode=" + (resizeTo > 0 ? "resize " + resizeTo : "crop")
                + "\nfov=" + captureFov + "\ncrop=" + cropX + "," + cropY;
    }

    /** 面のテクスチャを書き出す (動作確認用) */
    public void dumpFaces(Path dir) {
        if (glFaces != null) glFaces.dump(dir);
    }

    public void requestScreenshot(Path path) {
        if (window != null) window.requestCapture(path);
    }

    /** クライアントの tick の終わりに呼ぶ。面を取り込む */
    public void onTick() {
        if (!ensureWindow()) return;
        if (selfTest != null) selfTest.tick(this);
        if (!game.inWorld()) {
            window.renderBlack();
            return;
        }
        if (!hasViewer()) return;
        try {
            capture();
        } catch (RuntimeException e) {
            Log.error("Couldn't take panorama images", e);
        }
    }

    /** フレームを描き終えたときに呼ぶ。ウィンドウを描き直す (向きを変えたときに滑らかに追うため、毎フレーム) */
    public void onFrame() {
        if (!ensureWindow()) return;
        if (taking) return;
        if (!game.inWorld()) {
            window.renderBlack();
            return;
        }
        if (!hasViewer()) return;
        // 6 面がそろうまでは、隙間が見えないよう黒いままにする
        if (capturedFaces < 6) {
            window.renderBlack();
            return;
        }
        present();
    }

    /** ゲームの終了時に呼ぶ。次も同じ大きさで開くよう、ウィンドウの大きさを覚えておく */
    public void onStop() {
        if (window == null) return;
        window.stopSpout();
        if (!config.isShowWindow()) return;
        final int[] size = window.windowSize();
        if (size[0] > 0 && size[1] > 0) config.setWindowSize(size[0], size[1]);
    }

    // パノラマを見る (受け取る) ものがいるか。いなければ取り込みを止める
    private boolean hasViewer() {
        if (config.isSpoutEnabled()) return true;
        if (!config.isShowWindow()) return false;
        return !(config.isPauseWhenHidden() && window.isMinimized());
    }

    /** handle がパノラマウィンドウか */
    public boolean isPanoramaWindow(long handle) {
        return window != null && window.handle() == handle;
    }

    /** 設定 (設定画面から変える) */
    public Config config() {
        return config;
    }

    /** ウィンドウの表示・枠の設定を反映する */
    public void applyWindowSettings() {
        if (window == null) return;
        window.setVisible(config.isShowWindow());
        window.setDecorated(!config.isBorderless());
    }

    /** Spout2 の設定を反映する */
    public void applySpoutSettings() {
        if (window != null) window.configureSpout(config.isSpoutEnabled(), config.getSpoutName(), config.getSpoutWidth());
    }

    /** 設定画面に出す、今の取り込みの状態 */
    public String statusText() {
        if (window == null) return Lang.get("ui.status.no_window");
        if (faceSize == 0) return Lang.get("ui.status.waiting");
        final String mode = resizeTo > 0 ? Lang.get("ui.mode.resize") : Lang.get("ui.mode.crop");
        final String shader = game.shaderPackActive() ? Lang.get("ui.status.shader_on") : Lang.get("ui.status.shader_off");
        return Lang.get("ui.status", faceSize, mode, Math.round(captureFov), shader);
    }

    /** 設定画面に出す、Spout2 の状態。先頭が "!" ならうまく行っていない */
    public String spoutStatusText() {
        if (!config.isSpoutEnabled()) return "";
        if (window == null) return "";
        final String status = window.spoutStatus();
        return status.isEmpty() ? Lang.get("ui.spout.starting") : status;
    }

    private boolean ensureWindow() {
        if (window != null) return true;
        if (failed) return false;
        final long handle = game.windowHandle();
        if (handle == 0) return false;
        try {
            window = new WindowManager(config, handle, game.usesOpenGl());
            if (game.usesOpenGl()) glFaces = new GlFaces();
            window.setDecorated(!config.isBorderless());
            applySpoutSettings();
            Log.info("Opened the panorama window ({})", game.usesOpenGl() ? "shared OpenGL context" : "CPU transfer");
            return true;
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.error("Couldn't open the panorama window", e);
            return false;
        }
    }

    private void capture() {
        if (game.level() != lastLevel) {
            lastLevel = game.level();
            // 影 Mod は、ワールドに入った直後しばらくは準備中で、そのとき描いた面は霧で白くなることがある
            settleTicks = game.shaderPackActive() ? SHADER_SETTLE_TICKS : 30;
            nextFace = 0;
        }
        if (settleTicks > 0) {
            settleTicks--;
            return;
        }
        if (!game.playerAlive()) return;
        // ポーズ中はワールドが止まっているので、描き直しても同じものになる
        if (game.paused()) return;
        // 影 Mod は、面と面の間に本来のフレームを何枚か描かないと、前の描画の状態を引きずって絵が崩れることがある
        final int interval = game.shaderPackActive()
                ? Math.max(config.getCaptureInterval(), SHADER_MIN_INTERVAL) : config.getCaptureInterval();
        if (++ticksSinceCapture < interval) return;
        ticksSinceCapture = 0;

        if (nextFace == 0 && !startSweep()) return;

        // 影 Mod は、前に描いたときの状態 (視点や画面の履歴) を次の描画に持ち越す。面を続けて描くと、2 面目からは
        // 別の向きの面の状態を引きずって絵が崩れる (地面が抜けるなど) ので、1 面ごとに本来の向きで描き直す
        final boolean shaders = game.shaderPackActive();
        final int count = shaders ? 1 : config.getFacesPerCapture();
        final float yaw = game.yaw();
        final float pitch = game.pitch();
        final float yawO = game.yawO();
        final float pitchO = game.pitchO();

        taking = true;
        try {
            game.beginCapture(resizeTo);
            if (glFaces != null) glFaces.ensure(faceSize);
            for (int i = 0; i < count; i++) {
                final int face = (nextFace + i) % 6;
                final float[] rotation = FACE_ROTATIONS[face];
                final float faceYaw = rotation[0] + AXIS_OFFSET;
                game.setRotation(faceYaw, rotation[1], faceYaw, rotation[1]);
                game.renderLevel();
                copyFace(face);
            }
        } finally {
            game.setRotation(yaw, pitch, yawO, pitchO);
            game.endCapture();
            taking = false;
        }
        // 影 Mod の多くは、前のフレームの画面と視点を使って画面を滑らかにする (TAA など)。面を描いたままだと
        // 次のフレームで「前の視点」が面の向きになり、画面がぶれるので、本来の向きでもう 1 回描いておく
        if (shaders) {
            restoreView();
        }
        nextFace = (nextFace + count) % 6;
    }

    // 本来の向き・本来の FOV で、もう 1 回描く (出来上がった絵は次のフレームで上書きされる)
    // (taking が false なので、FOV やビルボードの Mixin も働かない)
    private void restoreView() {
        try {
            game.renderLevel();
        } catch (RuntimeException e) {
            Log.error("Couldn't re-render the main view after a capture", e);
        }
    }

    // 一巡の始まり。描き直す必要が無ければ false
    private boolean startSweep() {
        final double x = game.playerX();
        final double y = game.playerY();
        final double z = game.playerZ();
        final float yaw = game.yaw();
        final boolean moved = Double.isNaN(lastX)
                || Math.abs(x - lastX) > 1.0e-3
                || Math.abs(y - lastY) > 1.0e-3
                || Math.abs(z - lastZ) > 1.0e-3
                || Math.abs(wrapDegrees(yaw - lastYaw)) > 0.05F;
        if (!moved && idleTicks < IDLE_RESWEEP_TICKS) {
            idleTicks += config.getCaptureInterval();
            return false;
        }
        idleTicks = 0;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
        billboardYaw = yaw;
        chooseFaceSize();
        return true;
    }

    // 面の大きさと取り込み方式を決める
    private void chooseFaceSize() {
        final int width = game.framebufferWidth();
        final int height = game.framebufferHeight();
        final int wanted = window.faceSize();
        final int available = Math.min(width, height);
        // 描画先を作り直すと、影 Mod や Fabulous! の描画先まで作り直しになる (重いうえ、正しく描けないこともある)
        final boolean resizeAllowed = !game.shaderPackActive() && !game.fragileTargets();
        // 面より広く描くときは、描いた絵の中央の 1/overscan が 90 度ぶんになる
        final double overscan = config.getOverscan(game.shaderPackActive());
        captureFov = (float) Math.toDegrees(2.0 * Math.atan(overscan));
        boolean resize;
        switch (config.getCaptureMode()) {
            case CROP:
                resize = false;
                break;
            case RESIZE:
                resize = resizeAllowed;
                break;
            default:
                resize = resizeAllowed && wanted > available / overscan;
                break;
        }
        // FOV 90 度は縦の角度なので、縦長の画面からは正方形を切り出せない
        if (width < height) resize = true;

        final int size;
        if (resize) {
            final int target = Math.min(window.maxFaceSize(), (int) Math.ceil(wanted * overscan));
            size = Math.max(1, (int) (target / overscan));
            resizeTo = target;
            cropX = (target - size) / 2;
            cropY = (target - size) / 2;
        } else {
            size = Math.max(1, Math.min(window.maxFaceSize(), (int) (available / overscan)));
            resizeTo = 0;
            cropX = (width - size) / 2;
            cropY = (height - size) / 2;
        }
        if (size != faceSize) {
            faceSize = size;
            // 大きさが変わったら、一巡し終えるまで古い面を見せない
            capturedFaces = 0;
        }
    }

    private void copyFace(final int face) {
        if (glFaces != null) {
            final int source = game.mainColorTexture();
            if (source <= 0) return;
            glFaces.copy(face, source, cropX, cropY);
            faceCaptured();
        } else {
            final int size = faceSize;
            game.readMainColor(cropX, cropY, size, new Game.PixelSink() {
                @Override
                public void accept(ByteBuffer rgba) {
                    if (window != null && size == faceSize) {
                        window.uploadFace(face, rgba, size);
                        faceCaptured();
                    }
                }
            });
        }
    }

    private void faceCaptured() {
        newPixels = true;
        if (capturedFaces < 6) capturedFaces++;
        if (++totalFaces % 6 == 0) completedSweeps++;
    }

    private long totalFaces;

    private void present() {
        // 体の向き (頭の向きではない) を使う。取り込みも体の向きで回している。
        // tick の間を補間するが、向きを直接書き換えた直後は 1 tick 前の値が当てにならないので、大きく飛んだら補間しない
        final float partial = game.partialTick();
        final float yaw = game.yaw();
        final float turn = wrapDegrees(yaw - game.yawO());
        final float yawDegrees = Math.abs(turn) < 30.0F ? yaw - turn * (1.0F - partial) : yaw;
        final float pitch = game.pitch();
        final float tilt = pitch - game.pitchO();
        final float pitchDegrees = Math.abs(tilt) < 30.0F ? pitch - tilt * (1.0F - partial) : pitch;
        // 前の面 (0) は北向き (yaw 180) で描いてあるので、北を向いたときに中央に来るようずらす
        // 面は AXIS_OFFSET だけ回して描いてあるので、その分も戻す
        final float yawRadians = (float) Math.toRadians(yawDegrees - 180.0F - AXIS_OFFSET);
        final float pitchRadians = (float) Math.toRadians(pitchDegrees);
        window.render(glFaces != null ? glFaces.textures() : null, faceSize, yawRadians, pitchRadians, newPixels);
        newPixels = false;
    }

    /**
     * 面を描く向きを、軸からこれだけ (度) ずらす。カメラの向きが軸にぴったり沿うと、視錐台で隠れる判定の計算が
     * 丸め誤差で崩れ、地面などが描かれないことがある (影 Mod 使用時の東西の面、Forge の看板など)。
     * 6 面すべてを同じだけ横に回すので、ウィンドウのシェーダーで向きを戻せば見た目のずれは残らない。
     * これより小さいと、向きを行列にする途中で丸められて消えることがある (MarumaSign の知見)
     */
    static final float AXIS_OFFSET = 0.05F;

    // 面ごとのプレイヤーの向き (yaw, pitch)。並びはウィンドウのシェーダーと同じ。
    // yaw 180 = -Z (北)、270 = +X (東)、0 = +Z (南)、90 = -X (西)
    private static final float[][] FACE_ROTATIONS = {
            {180.0F, 0.0F},   // 前 (シェーダーの +Z)
            {270.0F, 0.0F},   // 右 (+X)
            {0.0F, 0.0F},     // 後 (-Z)
            {90.0F, 0.0F},    // 左 (-X)
            {180.0F, -90.0F}, // 上 (+Y)
            {180.0F, 90.0F},  // 下 (-Y)
    };

    static float wrapDegrees(float degrees) {
        float d = degrees % 360.0F;
        if (d >= 180.0F) d -= 360.0F;
        if (d < -180.0F) d += 360.0F;
        return d;
    }
}
