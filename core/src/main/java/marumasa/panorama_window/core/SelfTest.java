package marumasa.panorama_window.core;

import marumasa.panorama_window.core.spout.SpoutSender;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 実機での動作確認 (tools/verify) のための自動操作。JVM の引数
 * {@code -Dpanorama_window.selftest=<出力先>} を付けたときだけ働く。
 *
 * <p>ワールドに入ったら、何度か取り込みが終わるのを待ってパノラマウィンドウの中身を PNG に書き出し、
 * 向きを変えて (横に 90 度、上を見上げる) もう一度書き出す。続けて、設定画面を開いて (確認ツールが画面を撮る)
 * 閉じ、Spout2 で送って名前が登録されるかを調べてから、結果を result.txt に書いてゲームを終了する。
 */
final class SelfTest {

    private static final int TIMEOUT_TICKS = 20 * 240;

    private final Game game;
    private final Path out;
    private int step;
    private int ticks;
    private int waitTicks;
    private long sweepsAtStep;
    private float baseYaw;
    private String settings = "not tested";
    private String spout = "not tested";

    private SelfTest(Game game, Path out) {
        this.game = game;
        this.out = out;
    }

    static SelfTest fromSystemProperty(Game game) {
        final String dir = System.getProperty("panorama_window.selftest");
        if (dir == null || dir.isEmpty()) return null;
        Log.info("Self test enabled; writing results to {}", dir);
        return new SelfTest(game, Paths.get(dir));
    }

    void tick(Panorama panorama) {
        if (step < 0) return;
        if (!game.inWorld()) return;
        if (++ticks > TIMEOUT_TICKS) {
            finish(panorama, "TIMEOUT at step " + step);
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        final long sweeps = panorama.completedSweeps();
        switch (step) {
            case 0:
                // ワールドの読み込みが落ち着いて、何周か取り込みが終わるまで待つ
                if (sweeps < 4) return;
                baseYaw = game.yaw();
                panorama.dumpFaces(out);
                shot(panorama, 0, sweeps);
                break;
            case 1:
                game.setRotation(baseYaw + 90.0F, 0.0F, baseYaw + 90.0F, 0.0F);
                next(sweeps, 10);
                break;
            case 2:
                if (sweeps < sweepsAtStep + 2) return;
                shot(panorama, 1, sweeps);
                break;
            case 3:
                // 見上げるだけなら描き直しは要らない (ウィンドウのシェーダーが回すだけ)
                game.setRotation(baseYaw + 90.0F, -60.0F, baseYaw + 90.0F, -60.0F);
                next(sweeps, 10);
                break;
            case 4:
                shot(panorama, 2, sweeps);
                break;
            case 5:
                game.setRotation(baseYaw, 0.0F, baseYaw, 0.0F);
                game.openSettings();
                next(sweeps, 20);
                break;
            case 6:
                // 確認ツールが Minecraft の画面を撮るまで待つ
                marker("settings-open.txt");
                settings = "opened";
                next(sweeps, 80);
                break;
            case 7:
                game.closeSettings();
                Log.info("Self test: closed the settings screen");
                next(sweeps, 40);
                break;
            case 8:
                Log.info("Self test: enabling Spout2");
                panorama.config().setSpoutEnabled(true);
                panorama.applySpoutSettings();
                next(sweeps, 60);
                break;
            case 9:
                spout = spoutResult(panorama);
                panorama.config().setSpoutEnabled(false);
                panorama.applySpoutSettings();
                next(sweeps, 20);
                break;
            default:
                finish(panorama, "OK");
                break;
        }
    }

    private String spoutResult(Panorama panorama) {
        final String status = panorama.spoutStatusText();
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return "skipped (not Windows)";
        try {
            final boolean registered = SpoutSender.isRegistered(panorama.config().getSpoutName());
            return (registered ? "registered" : "NOT registered") + " (" + status + ")";
        } catch (RuntimeException | LinkageError e) {
            return "error " + e + " (" + status + ")";
        }
    }

    private void shot(Panorama panorama, int index, long sweeps) {
        panorama.requestScreenshot(out.resolve("panorama-" + index + ".png"));
        next(sweeps, 10);
    }

    private void next(long sweeps, int wait) {
        step++;
        sweepsAtStep = sweeps;
        waitTicks = wait;
    }

    private void marker(String name) {
        try {
            Files.createDirectories(out);
            Files.write(out.resolve(name), "1\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.error("Couldn't write " + name, e);
        }
    }

    private void finish(Panorama panorama, String status) {
        step = -1;
        final String text = "status=" + status + "\n"
                + "minecraft=" + game.version() + "\n"
                + "sweeps=" + panorama.completedSweeps() + "\n"
                + "shaderPack=" + game.shaderPackActive() + "\n"
                + "opengl=" + game.usesOpenGl() + "\n"
                + "framebuffer=" + game.framebufferWidth() + "x" + game.framebufferHeight() + "\n"
                + "settings=" + settings + "\n"
                + "spout=" + spout + "\n"
                + panorama.describe() + "\n";
        try {
            Files.createDirectories(out);
            Files.write(out.resolve("result.txt"), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.error("Couldn't write the self test result", e);
        }
        Log.info("Self test finished: {}", status);
        game.quit();
    }
}
