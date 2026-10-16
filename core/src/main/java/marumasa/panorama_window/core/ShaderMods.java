package marumasa.panorama_window.core;

import java.lang.reflect.Method;

/**
 * 影 Mod (シェーダーを使えるようにする Mod) のシェーダーパックが有効かどうかを調べる。
 * どの影 Mod にも直接は依存せず、クラスがあるときだけリフレクションで聞く。
 *
 * <ul>
 *   <li>Iris (Oculus など、Iris の API を持つものも): {@code IrisApi.getInstance().isShaderPackInUse()}</li>
 *   <li>OptiFine (Fabric では OptiFabric 経由): {@code net.optifine.Config.isShaders()}</li>
 *   <li>Canvas: 常に独自の描画パイプラインを使う</li>
 * </ul>
 */
public final class ShaderMods {

    private static boolean initialized;
    private static Object irisApi;
    private static Method irisInUse;
    private static Method optifineShaders;
    private static boolean canvas;

    private ShaderMods() {
    }

    /** シェーダーパックが有効か。毎 tick 呼んでも軽い */
    public static boolean active() {
        init();
        try {
            if (irisInUse != null && (Boolean) irisInUse.invoke(irisApi)) return true;
            if (optifineShaders != null && (Boolean) optifineShaders.invoke(null)) return true;
        } catch (Exception | LinkageError e) {
            // 影 Mod の版が違って呼べなくなったら、以後は聞かない
            irisInUse = null;
            optifineShaders = null;
        }
        return canvas;
    }

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            final Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApi = api.getMethod("getInstance").invoke(null);
            irisInUse = api.getMethod("isShaderPackInUse");
            Log.info("Detected Iris shader API");
        } catch (Exception | LinkageError ignored) {
            // Iris は入っていない
        }
        try {
            optifineShaders = Class.forName("net.optifine.Config").getMethod("isShaders");
            Log.info("Detected OptiFine");
        } catch (Exception | LinkageError ignored) {
            // OptiFine は入っていない
        }
        try {
            Class.forName("grondag.canvas.CanvasMod");
            canvas = true;
            Log.info("Detected Canvas renderer");
        } catch (Exception | LinkageError ignored) {
            // Canvas は入っていない
        }
    }
}
