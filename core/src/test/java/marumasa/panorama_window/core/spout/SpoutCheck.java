package marumasa.panorama_window.core.spout;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Spout2 の送信を、Minecraft を起動せずに確かめる (./gradlew :core:spoutCheck)。
 *
 * <p>隠したウィンドウの OpenGL で、上の 1/4 が緑・下の左半分が赤・下の右半分が青のテクスチャを作って送り、
 * 受け取る側の手順 (名前の並び → 送信者の情報 → 共有テクスチャを Direct3D 11 で開いて読む) で、
 * 同じ絵が正しい向きで届くかを調べる。
 */
public final class SpoutCheck {

    private static final String NAME = "PanoramaWindowCheck";
    private static final int WIDTH = 512;
    private static final int HEIGHT = 256;

    // ID3D11Device / ID3D11DeviceContext の仮想関数表の位置
    private static final int DEVICE_CREATE_TEXTURE_2D = 5;
    private static final int DEVICE_OPEN_SHARED_RESOURCE = 28;
    private static final int CONTEXT_MAP = 14;
    private static final int CONTEXT_UNMAP = 15;
    private static final int CONTEXT_COPY_RESOURCE = 47;

    public static void main(String[] args) {
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        final long window = glfwCreateWindow(64, 64, "spout check", 0, 0);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        // OpenGL のエラーを数える (Minecraft も同じようにデバッグ出力をログに出す)
        final int[] glErrors = new int[1];
        org.lwjgl.opengl.KHRDebug.glDebugMessageCallback((source, type, id, severity, length, message, user) -> {
            if (type == org.lwjgl.opengl.KHRDebug.GL_DEBUG_TYPE_ERROR) {
                glErrors[0]++;
                System.out.println("GL ERROR: " + org.lwjgl.opengl.GLDebugMessageCallback.getMessage(length, message));
            }
        }, 0);
        glEnable(org.lwjgl.opengl.KHRDebug.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        System.out.println("FFI: " + NativeCalls.get().getClass().getSimpleName());
        System.out.println("available: " + SpoutSender.available());
        System.out.println(String.format("GL LUID: 0x%016X", SpoutSender.openGlLuid()));
        System.out.println("GL: " + glGetString(GL_VENDOR) + " / " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));

        // OpenGL のテクスチャは下の行から並ぶ
        final ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                final boolean top = y >= HEIGHT * 3 / 4;
                final int r = top ? 0 : x < WIDTH / 2 ? 255 : 0;
                final int g = top ? 255 : 0;
                final int b = top ? 0 : x < WIDTH / 2 ? 0 : 255;
                pixels.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
            }
        }
        pixels.flip();
        final int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);

        final SpoutSender sender = new SpoutSender(NAME);
        boolean ok = true;
        try {
            for (int i = 0; i < 3; i++) ok &= sender.send(texture, WIDTH, HEIGHT);
            glFinish();
            System.out.println("sent as \"" + sender.name() + "\": " + ok);
            ok &= receive(sender.name());
        } finally {
            sender.close();
        }
        // 閉じたら名前の並びから消えていること
        final Win32 win32 = new Win32();
        final Win32.SharedMemory names = win32.new SharedMemory("SpoutSenderNames", win32.maxSenders() * 256);
        final long buffer = names.lock();
        final boolean removed = !readName(buffer, 0, win32.maxSenders()).contains(NAME);
        names.unlock();
        names.close();
        System.out.println("removed from the list after close: " + removed);
        ok &= removed;

        System.out.println("GL errors: " + glErrors[0]);
        ok &= glErrors[0] == 0;
        glfwDestroyWindow(window);
        glfwTerminate();
        System.out.println(ok ? "SPOUT CHECK OK" : "SPOUT CHECK FAILED");
        System.exit(ok ? 0 : 1);
    }

    private static boolean receive(String name) {
        final Win32 win32 = new Win32();
        final Win32.SharedMemory names = win32.new SharedMemory("SpoutSenderNames", win32.maxSenders() * 256);
        long buffer = names.lock();
        final String list = readName(buffer, 0, win32.maxSenders());
        names.unlock();
        names.close();
        System.out.println("sender names: " + list);
        if (!list.contains(name)) return false;

        final Win32.SharedMemory info = win32.new SharedMemory(name, 280);
        buffer = info.lock();
        final long handle = MemoryUtil.memGetInt(buffer) & 0xFFFFFFFFL;
        final int width = MemoryUtil.memGetInt(buffer + 4);
        final int height = MemoryUtil.memGetInt(buffer + 8);
        final int format = MemoryUtil.memGetInt(buffer + 12);
        info.unlock();
        info.close();
        System.out.println(String.format("info: handle=0x%X size=%dx%d format=%d", handle, width, height, format));
        if (handle == 0 || width != WIDTH || height != HEIGHT || format != D3D11.DXGI_FORMAT_B8G8R8A8_UNORM) return false;

        // 受け取る側と同じく、共有テクスチャを開いて、CPU で読める写しを作る
        final D3D11 d3d = D3D11.forOpenGl(glGetString(GL_RENDERER), SpoutSender.openGlLuid(), new D3D11.DeviceCheck() {
            public boolean accept(long device) {
                return true;
            }
        });
        final NativeCalls calls = NativeCalls.get();
        final ByteBuffer out = MemoryUtil.memCalloc(8);
        final ByteBuffer iid = MemoryUtil.memAlloc(16);
        // IID_ID3D11Texture2D {6f15aaf2-d208-4e89-9ab4-489535d34f9c}
        iid.put(new byte[]{(byte) 0xf2, (byte) 0xaa, 0x15, 0x6f, 0x08, (byte) 0xd2, (byte) 0x89, 0x4e,
                (byte) 0x9a, (byte) 0xb4, 0x48, (byte) 0x95, 0x35, (byte) 0xd3, 0x4f, (byte) 0x9c}).flip();
        int result = (int) calls.com(d3d.device, DEVICE_OPEN_SHARED_RESOURCE, handle, MemoryUtil.memAddress(iid), MemoryUtil.memAddress(out));
        if (result < 0) {
            System.out.println(String.format("OpenSharedResource failed: 0x%08X", result));
            return false;
        }
        final long shared = out.getLong(0);

        final ByteBuffer desc = MemoryUtil.memCalloc(44);
        desc.putInt(0, width).putInt(4, height).putInt(8, 1).putInt(12, 1).putInt(16, format)
                .putInt(20, 1).putInt(28, 3 /* STAGING */).putInt(36, 0x20000 /* CPU_ACCESS_READ */);
        out.putLong(0, 0);
        result = (int) calls.com(d3d.device, DEVICE_CREATE_TEXTURE_2D, MemoryUtil.memAddress(desc), 0, MemoryUtil.memAddress(out));
        if (result < 0) {
            System.out.println(String.format("CreateTexture2D (staging) failed: 0x%08X", result));
            return false;
        }
        final long staging = out.getLong(0);
        final long context = contextOf(d3d);
        calls.com(context, CONTEXT_COPY_RESOURCE, staging, shared);
        final ByteBuffer mapped = MemoryUtil.memCalloc(16);
        result = (int) calls.com(context, CONTEXT_MAP, staging, 0, 1 /* READ */, 0, MemoryUtil.memAddress(mapped));
        if (result < 0) {
            System.out.println(String.format("Map failed: 0x%08X", result));
            return false;
        }
        final long data = mapped.getLong(0);
        final int pitch = mapped.getInt(8);
        // 上の行 (Direct3D では先頭) は緑、下の左は赤、下の右は青のはず (BGRA)
        final int topLeft = MemoryUtil.memGetInt(data + 4L * 10);
        final int bottomLeft = MemoryUtil.memGetInt(data + (long) pitch * (height - 10) + 4L * 10);
        final int bottomRight = MemoryUtil.memGetInt(data + (long) pitch * (height - 10) + 4L * (width - 10));
        calls.com(context, CONTEXT_UNMAP, staging, 0);
        System.out.println(String.format("pixels (BGRA as int): top=0x%08X bottomLeft=0x%08X bottomRight=0x%08X",
                topLeft, bottomLeft, bottomRight));
        d3d.release(staging);
        d3d.release(shared);
        d3d.close();
        // 0xAARRGGBB の並び (メモリ上は B, G, R, A)
        return (topLeft & 0xFFFFFF) == 0x00FF00 && (bottomLeft & 0xFFFFFF) == 0xFF0000 && (bottomRight & 0xFFFFFF) == 0x0000FF;
    }

    private static long contextOf(D3D11 d3d) {
        try {
            final java.lang.reflect.Field field = D3D11.class.getDeclaredField("context");
            field.setAccessible(true);
            return field.getLong(d3d);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readName(long buffer, int from, int max) {
        final StringBuilder all = new StringBuilder();
        for (int i = from; i < max; i++) {
            final long address = buffer + i * 256L;
            if (MemoryUtil.memGetByte(address) == 0) break;
            final StringBuilder one = new StringBuilder();
            for (int j = 0; j < 255 && MemoryUtil.memGetByte(address + j) != 0; j++) one.append((char) MemoryUtil.memGetByte(address + j));
            if (all.length() > 0) all.append(", ");
            all.append(one);
        }
        return all.toString();
    }
}
