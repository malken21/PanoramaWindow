package marumasa.panorama_window.core.spout;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Spout で共有するテクスチャを作るための Direct3D 11。デバイスと、他のプロセスと共有できるテクスチャだけを扱う。
 * COM のメソッドは仮想関数表の位置で呼ぶ (d3d11.h / dxgi.h の宣言順)。
 */
final class D3D11 {

    private static final int D3D_DRIVER_TYPE_UNKNOWN = 0;
    private static final int D3D_DRIVER_TYPE_HARDWARE = 1;
    private static final int D3D11_CREATE_DEVICE_BGRA_SUPPORT = 0x20;
    private static final int D3D11_SDK_VERSION = 7;
    /** Spout の既定の形式 (DXGI_FORMAT_B8G8R8A8_UNORM) */
    static final int DXGI_FORMAT_B8G8R8A8_UNORM = 87;
    private static final int D3D11_BIND_SHADER_RESOURCE = 0x8;
    private static final int D3D11_BIND_RENDER_TARGET = 0x20;
    private static final int D3D11_RESOURCE_MISC_SHARED = 0x2;

    // 仮想関数表の位置
    private static final int IUNKNOWN_QUERY_INTERFACE = 0;
    private static final int IUNKNOWN_RELEASE = 2;
    private static final int DEVICE_CREATE_TEXTURE_2D = 5;
    private static final int DXGI_RESOURCE_GET_SHARED_HANDLE = 8;
    private static final int FACTORY_ENUM_ADAPTERS = 7;
    private static final int ADAPTER_GET_DESC = 8;

    // IID_IDXGIFactory1 {770aae78-f26f-4dba-a829-253c83d1b387}
    private static final byte[] IID_IDXGI_FACTORY1 = guid(0x770aae78, (short) 0xf26f, (short) 0x4dba,
            new byte[]{(byte) 0xa8, 0x29, 0x25, 0x3c, (byte) 0x83, (byte) 0xd1, (byte) 0xb3, (byte) 0x87});

    // IID_IDXGIResource {035f3ab4-482e-4e50-b41f-8a7f8bd8960b}
    private static final byte[] IID_IDXGI_RESOURCE = guid(0x035f3ab4, (short) 0x482e, (short) 0x4e50,
            new byte[]{(byte) 0xb4, 0x1f, (byte) 0x8a, 0x7f, (byte) 0x8b, (byte) 0xd8, (byte) 0x96, 0x0b});

    private final NativeCalls calls = NativeCalls.get();
    final long device;
    private final long context;

    /** 共有テクスチャ (ID3D11Texture2D) と、他のプロセスが開くためのハンドル */
    static final class SharedTexture {
        final long texture;
        final long shareHandle;

        SharedTexture(long texture, long shareHandle) {
            this.texture = texture;
            this.shareHandle = shareHandle;
        }
    }

    private D3D11(long device, long context) {
        this.device = device;
        this.context = context;
    }

    /** デバイスが使えるかを調べるもの (OpenGL との相互運用を開けるか) */
    interface DeviceCheck {
        boolean accept(long device);
    }

    /**
     * OpenGL と同じ GPU の Direct3D 11 デバイスを作る。GPU が複数あると、既定の GPU が OpenGL のものと違い、
     * 相互運用を開けない (開けても GPU どうしのコピーになって遅い) ことがある。そこで DXGI で GPU を並べ、
     * <ol>
     *   <li>LUID (Windows が GPU ごとに付ける ID) が OpenGL のもの (GL_EXT_memory_object_win32 で分かる) と同じもの</li>
     *   <li>名前が GL_RENDERER の先頭と同じもの (LUID が分からないとき。同じ型番の GPU が 2 枚あると決めきれない)</li>
     *   <li>残りすべて</li>
     * </ol>
     * の順に試し、相互運用を開けた (check が true を返した) 最初のものを使う。
     *
     * @param glLuid OpenGL の GPU の LUID。分からなければ 0
     */
    static D3D11 forOpenGl(String glRenderer, long glLuid, DeviceCheck check) {
        final NativeCalls calls = NativeCalls.get();
        final java.util.List<long[]> adapters = new java.util.ArrayList<long[]>();
        final java.util.List<String> names = new java.util.ArrayList<String>();
        final java.util.List<Long> luids = new java.util.ArrayList<Long>();
        final long factory = createFactory(calls);
        try {
            if (factory != 0) {
                final ByteBuffer out = MemoryUtil.memCalloc(8);
                final ByteBuffer desc = MemoryUtil.memCalloc(512);
                try {
                    for (int i = 0; i < 16; i++) {
                        out.putLong(0, 0);
                        if ((int) calls.com(factory, FACTORY_ENUM_ADAPTERS, i, MemoryUtil.memAddress(out)) < 0) break;
                        final long adapter = out.getLong(0);
                        calls.com(adapter, ADAPTER_GET_DESC, MemoryUtil.memAddress(desc));
                        final String name = MemoryUtil.memUTF16(desc, 128).trim();
                        final int end = name.indexOf('\0');
                        names.add(end >= 0 ? name.substring(0, end) : name);
                        // DXGI_ADAPTER_DESC の AdapterLuid (Description[128] と ID 4 つと SIZE_T 3 つの後)
                        luids.add(desc.getLong(296));
                        adapters.add(new long[]{adapter});
                    }
                } finally {
                    MemoryUtil.memFree(desc);
                    MemoryUtil.memFree(out);
                }
            }
            final java.util.List<Integer> order = new java.util.ArrayList<Integer>();
            for (int i = 0; i < luids.size(); i++) {
                if (glLuid != 0 && luids.get(i) == glLuid) order.add(i);
            }
            // 名前が GL_RENDERER と合うもの (例: "NVIDIA GeForce RTX 5050" と "NVIDIA GeForce RTX 5050/PCIe/SSE2")
            for (int i = 0; i < names.size(); i++) {
                if (order.contains(i)) continue;
                if (glRenderer != null && !names.get(i).isEmpty() && glRenderer.startsWith(names.get(i))) order.add(i);
            }
            for (int i = 0; i < names.size(); i++) {
                if (!order.contains(i)) order.add(i);
            }
            for (int index : order) {
                final D3D11 d3d = create(calls, adapters.get(index)[0]);
                if (d3d == null) continue;
                if (check.accept(d3d.device)) {
                    marumasa.panorama_window.core.Log.info("Spout2 uses the GPU \"{}\" ({})", names.get(index),
                            glLuid != 0 && luids.get(index) == glLuid ? "same LUID as OpenGL" : "LUID unknown or different");
                    return d3d;
                }
                d3d.close();
            }
            // DXGI で並べられなかったときは、既定の GPU で試す
            final D3D11 d3d = create(calls, 0);
            if (d3d != null && check.accept(d3d.device)) return d3d;
            if (d3d != null) d3d.close();
            throw new IllegalStateException("No Direct3D 11 adapter can share textures with OpenGL (" + glRenderer + ", " + names + ")");
        } finally {
            for (long[] adapter : adapters) calls.com(adapter[0], IUNKNOWN_RELEASE);
            if (factory != 0) calls.com(factory, IUNKNOWN_RELEASE);
        }
    }

    private static long createFactory(NativeCalls calls) {
        final long create = calls.function("dxgi", "CreateDXGIFactory1");
        if (create == 0) return 0;
        final ByteBuffer iid = MemoryUtil.memAlloc(16);
        final ByteBuffer out = MemoryUtil.memCalloc(8);
        try {
            iid.put(IID_IDXGI_FACTORY1).flip();
            return (int) calls.call(create, MemoryUtil.memAddress(iid), MemoryUtil.memAddress(out)) < 0 ? 0 : out.getLong(0);
        } finally {
            MemoryUtil.memFree(out);
            MemoryUtil.memFree(iid);
        }
    }

    // adapter が 0 なら既定の GPU
    private static D3D11 create(NativeCalls calls, long adapter) {
        final long create = calls.function("d3d11", "D3D11CreateDevice");
        if (create == 0) throw new IllegalStateException("d3d11.dll is not available");
        final ByteBuffer levels = MemoryUtil.memAlloc(12);
        final ByteBuffer out = MemoryUtil.memCalloc(24);
        try {
            // D3D_FEATURE_LEVEL_11_0, 10_1, 10_0
            levels.putInt(0, 0xb000).putInt(4, 0xa100).putInt(8, 0xa000);
            final long base = MemoryUtil.memAddress(out);
            // GPU を指定するときは、ドライバーの種類を UNKNOWN にする決まり
            final int result = (int) calls.call(create, adapter, adapter == 0 ? D3D_DRIVER_TYPE_HARDWARE : D3D_DRIVER_TYPE_UNKNOWN, 0,
                    D3D11_CREATE_DEVICE_BGRA_SUPPORT, MemoryUtil.memAddress(levels), 3, D3D11_SDK_VERSION, base, base + 8, base + 16);
            if (result < 0) return null;
            return new D3D11(out.getLong(0), out.getLong(16));
        } finally {
            MemoryUtil.memFree(out);
            MemoryUtil.memFree(levels);
        }
    }
    SharedTexture createSharedTexture(int width, int height) {
        final ByteBuffer desc = MemoryUtil.memCalloc(44);
        final ByteBuffer out = MemoryUtil.memCalloc(8);
        final ByteBuffer iid = MemoryUtil.memAlloc(16);
        try {
            desc.putInt(0, width).putInt(4, height)
                    .putInt(8, 1)                 // MipLevels
                    .putInt(12, 1)                // ArraySize
                    .putInt(16, DXGI_FORMAT_B8G8R8A8_UNORM)
                    .putInt(20, 1).putInt(24, 0)  // SampleDesc
                    .putInt(28, 0)                // D3D11_USAGE_DEFAULT
                    .putInt(32, D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET)
                    .putInt(36, 0)                // CPUAccessFlags
                    .putInt(40, D3D11_RESOURCE_MISC_SHARED);
            int result = (int) calls.com(device, DEVICE_CREATE_TEXTURE_2D, MemoryUtil.memAddress(desc), 0, MemoryUtil.memAddress(out));
            if (result < 0) throw new IllegalStateException(String.format("CreateTexture2D failed: 0x%08X", result));
            final long texture = out.getLong(0);

            iid.put(IID_IDXGI_RESOURCE).flip();
            out.putLong(0, 0);
            result = (int) calls.com(texture, IUNKNOWN_QUERY_INTERFACE, MemoryUtil.memAddress(iid), MemoryUtil.memAddress(out));
            if (result < 0) {
                release(texture);
                throw new IllegalStateException(String.format("QueryInterface(IDXGIResource) failed: 0x%08X", result));
            }
            final long resource = out.getLong(0);
            out.putLong(0, 0);
            result = (int) calls.com(resource, DXGI_RESOURCE_GET_SHARED_HANDLE, MemoryUtil.memAddress(out));
            release(resource);
            if (result < 0) {
                release(texture);
                throw new IllegalStateException(String.format("GetSharedHandle failed: 0x%08X", result));
            }
            return new SharedTexture(texture, out.getLong(0));
        } finally {
            MemoryUtil.memFree(iid);
            MemoryUtil.memFree(out);
            MemoryUtil.memFree(desc);
        }
    }

    void release(long object) {
        if (object != 0) calls.com(object, IUNKNOWN_RELEASE);
    }

    void close() {
        release(context);
        release(device);
    }

    private static byte[] guid(int data1, short data2, short data3, byte[] data4) {
        final byte[] bytes = new byte[16];
        bytes[0] = (byte) data1;
        bytes[1] = (byte) (data1 >> 8);
        bytes[2] = (byte) (data1 >> 16);
        bytes[3] = (byte) (data1 >> 24);
        bytes[4] = (byte) data2;
        bytes[5] = (byte) (data2 >> 8);
        bytes[6] = (byte) data3;
        bytes[7] = (byte) (data3 >> 8);
        System.arraycopy(data4, 0, bytes, 8, 8);
        return bytes;
    }
}
