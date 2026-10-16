package marumasa.panorama_window.core.spout;

import org.lwjgl.system.dyncall.DynCall;
import org.lwjgl.system.dyncall.DynLoad;

import java.util.HashMap;
import java.util.Map;

/**
 * LWJGL 3.2 (Minecraft 1.14 - 1.18) の dyncall で呼ぶ。LWJGL 3.3 にはこのクラスが使うものがないので、
 * そのときは読み込まれない ({@link NativeCalls#get()} が選ぶ)。
 */
final class DynCallNative extends NativeCalls {

    private final long vm = DynCall.dcNewCallVM(256);
    private final Map<String, Long> libraries = new HashMap<String, Long>();

    DynCallNative() {
        DynCall.dcMode(vm, DynCall.DC_CALL_C_DEFAULT);
    }

    @Override
    synchronized long function(String library, String name) {
        Long handle = libraries.get(library);
        if (handle == null) {
            handle = DynLoad.dlLoadLibrary(library);
            libraries.put(library, handle);
        }
        return handle == 0 ? 0 : DynLoad.dlFindSymbol(handle, name);
    }

    @Override
    synchronized long call(long function, long... args) {
        DynCall.dcReset(vm);
        for (long arg : args) {
            DynCall.dcArgLongLong(vm, arg);
        }
        return DynCall.dcCallLongLong(vm, function);
    }
}
