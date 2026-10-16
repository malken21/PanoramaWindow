package marumasa.panorama_window.adapter;

import marumasa.panorama_window.core.Log;
import marumasa.panorama_window.core.Panorama;

/**
 * Mixin から呼ばれる入口。どのバージョンでも共通。
 */
public final class Hooks {

    private static Panorama panorama;
    private static boolean broken;

    private Hooks() {
    }

    static void init() {
        try {
            panorama = new Panorama(new GameImpl());
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            Log.error("PanoramaWindow failed to start", e);
        }
    }

    /** クライアントの tick の終わり */
    public static void onTick() {
        if (panorama == null || broken) return;
        try {
            Settings.tick(net.minecraft.client.Minecraft.getInstance());
            panorama.onTick();
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /** 1 フレームの終わり */
    public static void onFrame() {
        if (panorama == null || broken) return;
        try {
            panorama.onFrame();
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /** ゲームの終了 */
    public static void onStop() {
        if (panorama == null || broken) return;
        try {
            panorama.onStop();
        } catch (RuntimeException | LinkageError e) {
            Log.error("PanoramaWindow failed while stopping", e);
        }
    }

    /** handle がパノラマウィンドウか (26.3 以降、SDL のイベントを振り分けるのに使う) */
    public static boolean isPanoramaWindow(long handle) {
        return handle != 0 && panorama != null && panorama.isPanoramaWindow(handle);
    }

    // ゲームを巻き込まないよう、以後は何もしない
    private static void fail(Throwable e) {
        broken = true;
        Log.error("PanoramaWindow stopped after an error", e);
    }
}
