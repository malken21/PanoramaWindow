package marumasa.panorama_window.core.spout;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * LWJGL 3.3 以降 (Minecraft 1.19 以降) の libffi で呼ぶ。LWJGL 3.3 の API でコンパイルするため、
 * 別のソースセット (ffi33) にある。{@link NativeCalls#get()} がリフレクションで作る。
 */
final class LibFfiNative extends NativeCalls {

    private final Map<String, SharedLibrary> libraries = new HashMap<String, SharedLibrary>();
    // 引数の数ごとの呼び出しの形 (すべてポインタの幅)
    private final Map<Integer, FFICIF> cifs = new HashMap<Integer, FFICIF>();

    @Override
    synchronized long function(String library, String name) {
        SharedLibrary handle = libraries.get(library);
        if (handle == null) {
            handle = Library.loadNative("panorama_window", library);
            libraries.put(library, handle);
        }
        return handle.getFunctionAddress(name);
    }

    @Override
    synchronized long call(long function, long... args) {
        FFICIF cif = cifs.get(args.length);
        if (cif == null) {
            cif = FFICIF.calloc();
            final PointerBuffer types = MemoryUtil.memAllocPointer(Math.max(1, args.length));
            for (int i = 0; i < args.length; i++) {
                types.put(i, LibFFI.ffi_type_pointer.address());
            }
            types.limit(args.length);
            final int status = LibFFI.ffi_prep_cif(cif, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_pointer, types);
            if (status != LibFFI.FFI_OK) throw new IllegalStateException("ffi_prep_cif failed: " + status);
            // types は cif が指したままにするので解放しない
            cifs.put(args.length, cif);
        }
        final ByteBuffer values = MemoryUtil.memAlloc(Math.max(8, args.length * 8));
        final PointerBuffer pointers = MemoryUtil.memAllocPointer(Math.max(1, args.length));
        final ByteBuffer result = MemoryUtil.memAlloc(8);
        try {
            for (int i = 0; i < args.length; i++) {
                values.putLong(i * 8, args[i]);
                pointers.put(i, MemoryUtil.memAddress(values) + i * 8L);
            }
            pointers.limit(args.length);
            LibFFI.ffi_call(cif, function, result, pointers);
            return result.getLong(0);
        } finally {
            MemoryUtil.memFree(result);
            MemoryUtil.memFree(pointers);
            MemoryUtil.memFree(values);
        }
    }
}
