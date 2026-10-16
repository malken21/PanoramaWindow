package marumasa.panorama_window.adapter;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric から起動されたときの入口。実際の処理は Mixin (MinecraftMixin) から呼ばれる。
 */
public final class FabricEntry implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Hooks.init();
    }
}
