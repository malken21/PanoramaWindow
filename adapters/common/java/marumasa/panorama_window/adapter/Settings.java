package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.platform.InputConstants;
import marumasa.panorama_window.core.Log;
import marumasa.panorama_window.core.Panorama;
import marumasa.panorama_window.core.ui.Page;
import marumasa.panorama_window.core.ui.SettingsPage;
import marumasa.panorama_window.core.ui.UiRoot;
import marumasa.panorama_window.core.util.OptionsFile;
import marumasa.panorama_window.core.util.Reflect;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Minecraft の画面に出す設定画面と、それを開くキー (既定は O)。キーは「操作設定」の「その他」で変えられる。
 * 画面の中身は core の SettingsPage が描き、バージョンごとの UiScreen はそれを貼って入力を渡すだけ
 * (MarumaSign の画面と同じ作り)。
 */
final class Settings {

    private static final String KEY_NAME = "PanoramaWindow";
    // GLFW / SDL の O キー
    private static final int KEY_O = 79;

    private static UiRoot ui;
    private static KeyMapping openKey;
    private static boolean registered;

    private Settings() {
    }

    static UiRoot ui() {
        if (ui == null) {
            ui = new UiRoot(new Host(), new UiRoot.PageFactory() {
                public Page create(UiRoot root) {
                    return new SettingsPage(root, Panorama.get());
                }
            });
        }
        return ui;
    }

    /** クライアントの tick ごとに呼ぶ */
    static void tick(Minecraft mc) {
        if (!registered) {
            // 設定の読み込みが終わるまで待つ
            if (mc.options == null) return;
            registered = true;
            try {
                registerKey(mc);
            } catch (RuntimeException | LinkageError e) {
                Log.error("Couldn't register the settings key", e);
            }
        }
        if (openKey != null) {
            while (openKey.consumeClick()) {
                if (Panorama.get() != null && McUi.screen(mc) == null) McUi.setScreen(mc, new UiScreen());
            }
        }
        if (ui != null && ui.isOpen()) ui.tick();
    }

    // 操作設定の一覧にキーを足す。ローダーの API を使わず、Options の配列を差し替える (MarumaSign と同じ方法)
    private static void registerKey(Minecraft mc) {
        openKey = McUi.newKey(KEY_NAME, KEY_O);
        final KeyMapping[] current = mc.options.keyMappings;
        final KeyMapping[] extended = new KeyMapping[current.length + 1];
        System.arraycopy(current, 0, extended, 0, current.length);
        extended[current.length] = openKey;
        Reflect.set(Reflect.fieldHolding(mc.options, current), mc.options, extended);
        // 保存されているキーの割り当てを反映する
        final String saved = OptionsFile.read(mc.gameDirectory, "key_" + KEY_NAME);
        if (saved != null && !saved.isEmpty()) {
            try {
                openKey.setKey(InputConstants.getKey(saved));
            } catch (RuntimeException e) {
                // 読めない値なら既定のキーのままにする
            }
        }
        KeyMapping.resetMapping();
    }

    /** UiRoot が使う、テクスチャと画面の操作 */
    private static final class Host implements UiRoot.Host {

        private Minecraft mc() {
            return Minecraft.getInstance();
        }

        public Object createTexture(int width, int height, int[] argb) {
            return Tex.create(mc(), width, height, argb);
        }

        public void updateTexture(Object texture, int width, int height, int[] argb) {
            ((Tex) texture).update(width, height, argb);
        }

        public void releaseTexture(Object texture) {
            ((Tex) texture).release(mc());
        }

        public void close() {
            if (McUi.screen(mc()) instanceof UiScreen) McUi.setScreen(mc(), null);
        }

        public String languageCode() {
            return mc().options.languageCode;
        }

        public String getClipboard() {
            return mc().keyboardHandler.getClipboard();
        }

        public void setClipboard(String text) {
            mc().keyboardHandler.setClipboard(text);
        }
    }
}
