package marumasa.panorama_window.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Mod の設定。{@code config/panorama_window.json} に保存する。ゲーム内の設定画面から変えるとすぐ保存される。
 *
 * <p>読み込みは寛容にしてある: 知らないキーは無視し、無いキーは既定値のまま (古い版が書いたファイルも読める)、
 * 壊れたファイルは既定値に戻して書き直す。
 *
 * <p>面の解像度は設定にしていない。パノラマウィンドウの大きさ (と取り込み方式) から決める。
 */
public final class Config {

    /** 面の取り込み方式 */
    public enum Mode {
        /** 影 Mod や Fabulous! のときは切り出し、それ以外は必要な解像度に合わせて選ぶ */
        AUTO,
        /** 描画先の大きさを変えずに FOV 90 度で描き、中央の正方形を切り出す。影 Mod と相性がよい */
        CROP,
        /** 描画先を面の大きさに作り直して描く。ゲーム画面より大きな解像度で取り込める */
        RESIZE
    }

    public static final String DEFAULT_SPOUT_NAME = "PanoramaWindow";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;

    /** パノラマウィンドウの大きさ (ピクセル)。終了時の大きさを書き戻すので、次も同じ大きさで開く */
    private int Width = 1280;
    private int Height = 720;

    /** パノラマウィンドウを表示する (Spout2 にだけ送りたいときは消せる) */
    private boolean ShowWindow = true;

    /** ウィンドウの枠とタイトルバーを隠す */
    private boolean Borderless = false;

    /** 面を取り込む間隔 (クライアント tick)。ウィンドウは毎フレーム描き直すので、向きを変えるだけなら関係ない */
    private int CaptureInterval = 2;

    /** 1 回に取り込む面の数 (1..6)。少ないほど負荷が分散し、多いほど全体が早く新しくなる */
    private int FacesPerCapture = 2;

    /** ウィンドウを最小化している間は取り込みを止める (Spout2 に送っている間は止めない) */
    private boolean PauseWhenHidden = true;

    /** 取り込み方式 (auto / crop / resize) */
    private String CaptureMode = "auto";

    /**
     * 面より広い範囲を描いて、中央の 90 度だけを使う倍率 (1.0 = 広げない、2.0 = FOV 約 127 度)。
     * 影 Mod のゴッドレイなど、画面の中だけで計算する効果が面の境目で途切れにくくなる (そのぶん面は粗くなる)。
     * 0 以下なら自動 (シェーダーパックが有効なときだけ 1.5)
     */
    private double Overscan = 0.0;

    /** パノラマ (正距円筒図法) を Spout2 で送る (Windows のみ) */
    private boolean SpoutEnabled = false;

    /** Spout2 の送信者の名前 */
    private String SpoutName = DEFAULT_SPOUT_NAME;

    /** Spout2 で送る画像の幅 (高さは幅の半分) */
    private int SpoutWidth = 3840;

    public Config(Path path) {
        this.path = path;
        if (Files.exists(path)) {
            load();
        }
        save();
    }

    // ---------- 読み出し ----------

    public int getWidth() {
        return Width;
    }

    public int getHeight() {
        return Height;
    }

    public boolean isShowWindow() {
        return ShowWindow;
    }

    public boolean isBorderless() {
        return Borderless;
    }

    public int getCaptureInterval() {
        return Math.max(1, CaptureInterval);
    }

    public int getFacesPerCapture() {
        return Math.min(6, Math.max(1, FacesPerCapture));
    }

    public boolean isPauseWhenHidden() {
        return PauseWhenHidden;
    }

    public Mode getCaptureMode() {
        try {
            return Mode.valueOf(CaptureMode.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return Mode.AUTO;
        }
    }

    /** 設定に書いてある倍率 (0 = 自動) */
    public double getOverscanSetting() {
        return Overscan > 0.0 ? Overscan : 0.0;
    }

    /** 面より広く描く倍率。shaderPack はシェーダーパックが有効か */
    public double getOverscan(boolean shaderPack) {
        if (!(Overscan > 0.0)) return shaderPack ? 1.5 : 1.0;
        return Math.min(3.0, Math.max(1.0, Overscan));
    }

    public boolean isSpoutEnabled() {
        return SpoutEnabled;
    }

    public String getSpoutName() {
        final String name = SpoutName == null ? "" : SpoutName.trim();
        return name.isEmpty() ? DEFAULT_SPOUT_NAME : name;
    }

    public int getSpoutWidth() {
        // 4 の倍数にそろえる (高さは幅の半分)
        return Math.max(256, Math.min(8192, SpoutWidth)) / 4 * 4;
    }

    // ---------- 書き換え (すぐ保存する) ----------

    public void setShowWindow(boolean value) {
        ShowWindow = value;
        save();
    }

    public void setBorderless(boolean value) {
        Borderless = value;
        save();
    }

    public void setCaptureInterval(int value) {
        CaptureInterval = Math.max(1, Math.min(40, value));
        save();
    }

    public void setFacesPerCapture(int value) {
        FacesPerCapture = Math.min(6, Math.max(1, value));
        save();
    }

    public void setPauseWhenHidden(boolean value) {
        PauseWhenHidden = value;
        save();
    }

    public void setCaptureMode(Mode mode) {
        CaptureMode = mode.name().toLowerCase(Locale.ROOT);
        save();
    }

    public void setOverscan(double value) {
        Overscan = value > 0.0 ? Math.min(3.0, Math.max(1.0, value)) : 0.0;
        save();
    }

    public void setSpoutEnabled(boolean value) {
        SpoutEnabled = value;
        save();
    }

    public void setSpoutName(String value) {
        SpoutName = value == null ? DEFAULT_SPOUT_NAME : value.trim();
        save();
    }

    public void setSpoutWidth(int value) {
        SpoutWidth = Math.max(256, Math.min(8192, value));
        save();
    }

    /** 終了時のウィンドウの大きさを覚えておく */
    public void setWindowSize(int width, int height) {
        int w = clampDimension(width, 1280);
        int h = clampDimension(height, 720);
        if (w == Width && h == Height) return;
        Width = w;
        Height = h;
        save();
    }

    /** ウィンドウの大きさ以外を、既定の値に戻す */
    public void resetToDefaults() {
        final Config defaults = new Config();
        ShowWindow = defaults.ShowWindow;
        Borderless = defaults.Borderless;
        CaptureInterval = defaults.CaptureInterval;
        FacesPerCapture = defaults.FacesPerCapture;
        PauseWhenHidden = defaults.PauseWhenHidden;
        CaptureMode = defaults.CaptureMode;
        Overscan = defaults.Overscan;
        SpoutEnabled = defaults.SpoutEnabled;
        SpoutName = defaults.SpoutName;
        SpoutWidth = defaults.SpoutWidth;
        save();
    }

    // 既定値だけを持つもの (resetToDefaults 用)
    private Config() {
        this.path = null;
    }

    private static int clampDimension(int value, int fallback) {
        if (value <= 0) return fallback;
        return Math.min(15360, Math.max(64, value));
    }

    // ---------- ファイル ----------

    private void load() {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            // Gson 2.8.0 (Minecraft 1.14 に同梱) には JsonParser.parseReader が無い
            @SuppressWarnings("deprecation")
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            Width = clampDimension(getInt(json, "Width", Width), 1280);
            Height = clampDimension(getInt(json, "Height", Height), 720);
            ShowWindow = getBool(json, "ShowWindow", ShowWindow);
            Borderless = getBool(json, "Borderless", Borderless);
            CaptureInterval = Math.max(1, getInt(json, "CaptureInterval", CaptureInterval));
            FacesPerCapture = Math.min(6, Math.max(1, getInt(json, "FacesPerCapture", FacesPerCapture)));
            PauseWhenHidden = getBool(json, "PauseWhenHidden", PauseWhenHidden);
            CaptureMode = getString(json, "CaptureMode", CaptureMode);
            Overscan = getDouble(json, "Overscan", Overscan);
            SpoutEnabled = getBool(json, "SpoutEnabled", SpoutEnabled);
            SpoutName = getString(json, "SpoutName", SpoutName);
            SpoutWidth = getInt(json, "SpoutWidth", SpoutWidth);
        } catch (Exception e) {
            Log.warn("Couldn't read {}; using defaults and rewriting it ({})", path, e.toString());
        }
    }

    private void save() {
        if (path == null) return;
        JsonObject json = new JsonObject();
        json.addProperty("Width", Width);
        json.addProperty("Height", Height);
        json.addProperty("ShowWindow", ShowWindow);
        json.addProperty("Borderless", Borderless);
        json.addProperty("CaptureInterval", CaptureInterval);
        json.addProperty("FacesPerCapture", FacesPerCapture);
        json.addProperty("PauseWhenHidden", PauseWhenHidden);
        json.addProperty("CaptureMode", CaptureMode);
        json.addProperty("Overscan", Overscan);
        json.addProperty("SpoutEnabled", SpoutEnabled);
        json.addProperty("SpoutName", SpoutName);
        json.addProperty("SpoutWidth", SpoutWidth);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.error("Couldn't write " + path, e);
        }
    }

    private static int getInt(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) && json.getAsJsonPrimitive(key).isNumber()
                    ? json.getAsJsonPrimitive(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double getDouble(JsonObject json, String key, double fallback) {
        try {
            return json.has(key) && json.getAsJsonPrimitive(key).isNumber()
                    ? json.getAsJsonPrimitive(key).getAsDouble() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean getBool(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) && json.getAsJsonPrimitive(key).isBoolean()
                    ? json.getAsJsonPrimitive(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String getString(JsonObject json, String key, String fallback) {
        try {
            return json.has(key) && json.getAsJsonPrimitive(key).isString()
                    ? json.getAsJsonPrimitive(key).getAsString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
