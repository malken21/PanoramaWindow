// tools/gen_ui_adapters.py が MarumaSign の実装から作ったファイル。直すときはスクリプトを直して作り直す
package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 設定画面に使う、バージョンによって書き方が違う部分。
 */
final class McUi {

    private McUi() {
    }

    static Component text(String text) {
        return Component.literal(text);
    }

    static Window window(Minecraft mc) {
        return mc.getWindow();
    }

    static Screen screen(Minecraft mc) {
        return mc.gui.screen();
    }

    static void setScreen(Minecraft mc, Screen screen) {
        mc.gui.setScreen(screen);
    }

    static KeyMapping newKey(String name, int keyCode) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, KeyMapping.Category.MISC);
    }
}
