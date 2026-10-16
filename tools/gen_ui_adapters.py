"""設定画面のバージョンごとの部分 (adapters/ui_*) を、MarumaSign の実装から作る。

MarumaSign (https://github.com/TORO-Server/MarumaSign) の画面は、core が描いた 1 枚の画像をテクスチャにして
Minecraft の画面に貼るだけなので、そのテクスチャと画面のクラスをそのまま使える。
パッケージ名などを置き換えて adapters/ui_*/java に書き出す (書き出したファイルもリポジトリに入れる)。

    python tools/gen_ui_adapters.py [MarumaSign のリポジトリ]
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
MARUMASIGN = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO.parent / "MarumaSign"
SRC = "main/java/marumasa/marumasa_sign/adapter"
OUT = "java/marumasa/panorama_window/adapter"

# 世代: (画面の土台, 画面の描画, テクスチャ, McUi の書き方)
#   McUi の書き方: text (TextComponent か Component.literal), window (mc.window か mc.getWindow()),
#   screen (mc.screen か mc.gui.screen()), category (キーの分類), keyType (キーの種類)
LAYERS = {
    "ui_1_14": ("classic", "v1_14", "tex_0", dict(text="new TextComponent(text)", window="mc.window")),
    "ui_1_15": ("classic", "v1_15", "tex_1r", dict(text="new TextComponent(text)")),
    "ui_1_16": ("classic", "v1_16", "tex_1", dict(text="new TextComponent(text)")),
    "ui_1_17": ("classic", "v1_17", "tex_1", dict(text="new TextComponent(text)")),
    "ui_1_19": ("classic", "v1_19_3", "tex_1", dict()),
    "ui_1_20": ("classic", "v1_20", "tex_1", dict()),
    "ui_1_21": ("classic", "v1_21", "tex_2", dict()),
    "ui_1_21_2": ("classic", "v1_21_2", "tex_3", dict()),
    "ui_1_21_5": ("classic", "v1_21_5", "tex_4", dict()),
    "ui_1_21_6": ("classic", "v1_21_6", "tex_4", dict()),
    "ui_1_21_9": ("renderstate", "v1_21_9", "tex_4", dict(category="KeyMapping.Category.MISC")),
    "ui_1_21_11": ("renderstate", "v1_21_9", "tex_5", dict(category="KeyMapping.Category.MISC")),
    "ui_26_1": ("renderstate", "v26", "tex_5", dict(category="KeyMapping.Category.MISC")),
    "ui_26_2": ("renderstate", "v26", "tex_5", dict(category="KeyMapping.Category.MISC", screen="gui")),
    "ui_26_3": ("renderstate", "v26", "tex_5", dict(category="KeyMapping.Category.MISC", screen="gui",
                                                     keyType="KEYBOARD")),
}

HEADER = "// tools/gen_ui_adapters.py が MarumaSign の実装から作ったファイル。直すときはスクリプトを直して作り直す\n"


def convert(text, is_tex=False):
    text = text.replace("package marumasa.marumasa_sign.adapter;", "package marumasa.panorama_window.adapter;")
    text = text.replace("import marumasa.marumasa_sign.core.MarumaSign;\n", "")
    text = text.replace("marumasa.marumasa_sign.core.", "marumasa.panorama_window.core.")
    text = text.replace("MarumaSign.ui()", "Settings.ui()")
    text = text.replace("MarumaSign.MOD_ID", '"panorama_window"')
    text = text.replace('"marumasa_sign"', '"panorama_window"')
    text = text.replace('Mc.text("MarumaSign")', 'McUi.text("PanoramaWindow")')
    text = text.replace("Mc.window(", "McUi.window(")
    text = text.replace(" * MarumaSign の画面", " * PanoramaWindow の設定画面")
    # 看板を描くための描画設定は使わない (テクスチャのクラスだけ)
    if is_tex:
        text = strip_render_type(text)
    return finish(text)


def strip_render_type(text):
    text = re.sub(r"\n\s*// ワールドに描くときの描画設定。[^\n]*\n\s*// [^\n]*\n\s*final RenderType renderType;\n", "\n", text)
    text = re.sub(r"\n\s*this\.renderType = [^\n]*\n", "\n", text)
    text = re.sub(r"import net\.minecraft\.client\.renderer\.(rendertype\.)?RenderTypes?;\n", "", text)
    return text


def finish(text):
    # ファイルのドロップは使わない
    text = re.sub(r"\n    // ファイルのドロップは[^\n]*\n    public void onFilesDrop\(List<Path> paths\) \{.*?\n    \}\n", "\n", text, flags=re.S)
    text = re.sub(r"\n    @Override\n    public void onFilesDrop\(List<Path> paths\) \{.*?\n    \}\n", "\n", text, flags=re.S)
    for unused in ("java.io.File", "java.nio.file.Path", "java.util.ArrayList", "java.util.List"):
        if unused.rsplit(".", 1)[1] + "<" not in text.split("import " + unused + ";")[-1] \
                and re.search(r"\b" + unused.rsplit(".", 1)[1] + r"\b", text.split("import " + unused + ";")[-1]) is None:
            text = text.replace("import " + unused + ";\n", "")
    return HEADER + text


def mc_ui(options):
    text_expr = options.get("text", "Component.literal(text)")
    window_expr = options.get("window", "mc.getWindow()")
    gui = options.get("screen") == "gui"
    category = options.get("category", '"key.categories.misc"')
    key_type = options.get("keyType", "KEYSYM")
    component_import = ("import net.minecraft.network.chat.TextComponent;\n" if "TextComponent" in text_expr else "")
    return HEADER + f"""package marumasa.panorama_window.adapter;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
{component_import}
/**
 * 設定画面に使う、バージョンによって書き方が違う部分。
 */
final class McUi {{

    private McUi() {{
    }}

    static Component text(String text) {{
        return {text_expr};
    }}

    static Window window(Minecraft mc) {{
        return {window_expr};
    }}

    static Screen screen(Minecraft mc) {{
        return {"mc.gui.screen()" if gui else "mc.screen"};
    }}

    static void setScreen(Minecraft mc, Screen screen) {{
        {"mc.gui.setScreen(screen)" if gui else "mc.setScreen(screen)"};
    }}

    static KeyMapping newKey(String name, int keyCode) {{
        return new KeyMapping(name, InputConstants.Type.{key_type}, keyCode, {category});
    }}
}}
"""


def main():
    for layer, (base, screen, tex, options) in LAYERS.items():
        out = REPO / "adapters" / layer / OUT
        out.mkdir(parents=True, exist_ok=True)
        sources = {
            "UiScreenBase.java": MARUMASIGN / "adapters" / base / SRC / "UiScreenBase.java",
            "UiScreen.java": MARUMASIGN / "adapters" / screen / SRC / "UiScreen.java",
            "Tex.java": MARUMASIGN / "adapters" / tex / SRC / "Tex.java",
        }
        for name, source in sources.items():
            (out / name).write_text(convert(source.read_text(encoding="utf-8"), name == "Tex.java"),
                                    encoding="utf-8", newline="\n")
        (out / "McUi.java").write_text(mc_ui(options), encoding="utf-8", newline="\n")
        print(f"{layer}: {base} + {screen} + {tex}")


if __name__ == "__main__":
    main()
