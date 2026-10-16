package marumasa.panorama_window.core.spout;

/**
 * Windows の API (kernel32 / d3d11 と COM のメソッド) を Java から呼ぶ仕組み。自前の DLL は使わず、Minecraft に同梱の
 * LWJGL が持つ FFI を使う。LWJGL 3.2 (Minecraft 1.14 - 1.18) は dyncall、3.3 以降 (1.19 以降) は libffi。
 *
 * <p>Windows x64 の呼び出し規約では、整数もポインタも 64 ビットの枠で渡され、戻り値も 64 ビットのレジスタで返る。
 * そのため、すべての引数をポインタの幅で渡し、戻り値を 64 ビットで受け取る 1 種類の呼び出しだけで足りる
 * (32 ビットの値を返す関数は、下位 32 ビットだけを使う)。
 */
abstract class NativeCalls {

    private static NativeCalls instance;

    /** 関数のアドレス。見つからなければ 0 */
    abstract long function(String library, String name);

    /** 関数を呼ぶ。引数はすべてポインタの幅 */
    abstract long call(long function, long... args);

    static synchronized NativeCalls get() {
        if (instance != null) return instance;
        if (hasClass("org.lwjgl.system.dyncall.DynCall")) {
            instance = new DynCallNative();
        } else {
            try {
                instance = (NativeCalls) Class.forName("marumasa.panorama_window.core.spout.LibFfiNative")
                        .getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("No FFI is available in this LWJGL", e);
            }
        }
        return instance;
    }

    /** COM のメソッドを呼ぶ。index は仮想関数表の位置 (IUnknown の QueryInterface が 0) */
    long com(long object, int index, long... args) {
        final long table = org.lwjgl.system.MemoryUtil.memGetAddress(object);
        final long method = org.lwjgl.system.MemoryUtil.memGetAddress(table + (long) index * 8L);
        final long[] full = new long[args.length + 1];
        full[0] = object;
        System.arraycopy(args, 0, full, 1, args.length);
        return call(method, full);
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, NativeCalls.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
