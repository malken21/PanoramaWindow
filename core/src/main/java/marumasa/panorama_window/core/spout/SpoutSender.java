package marumasa.panorama_window.core.spout;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.WGLNVDXInterop;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Spout2 の送信者。OpenGL のテクスチャを、Spout に対応したアプリ (OBS、Resolume、TouchDesigner など) に GPU のまま渡す。
 *
 * <p>Spout2 SDK (BSD-2-Clause, https://github.com/leadedge/Spout2) と同じ取り決めを Java で行う。
 * <ul>
 *   <li>Direct3D 11 で、他のプロセスと共有できるテクスチャ (B8G8R8A8) を作る</li>
 *   <li>WGL_NV_DX_interop で、そのテクスチャを OpenGL のテクスチャとして使えるようにし、送る絵を描き写す</li>
 *   <li>共有メモリ "SpoutSenderNames" (256 バイトの名前の並び) に名前を足し、"名前" という共有メモリに
 *       テクスチャの共有ハンドル・大きさ・形式を書く (SharedTextureInfo、280 バイト)</li>
 *   <li>写している間はミューテックス "名前_SpoutAccessMutex" を取り、写し終えたらセマフォ "名前_Count_Semaphore"
 *       を 1 つ進める (受け取る側が新しいフレームかどうかを見分ける)</li>
 * </ul>
 * どのメソッドも、送るテクスチャを持つ OpenGL のコンテキストを current にして呼ぶ。
 */
public final class SpoutSender {

    private static final int MAX_NAME = 256;
    private static final int SHARED_TEXTURE_INFO_SIZE = 280;
    private static final int WGL_ACCESS_WRITE_DISCARD_NV = 0x0002;
    private static final int GL_DEVICE_LUID_EXT = 0x9599;

    private final Win32 win32 = new Win32();
    private final D3D11 d3d;
    private long interopDevice;
    private final int maxSenders;
    private final Win32.SharedMemory senderNames;

    private final String requestedName;
    private String name;
    private Win32.SharedMemory info;
    private long accessMutex;
    private long frameSemaphore;

    private D3D11.SharedTexture texture;
    private int glTexture;
    private long interopObject;
    private int width;
    private int height;
    private int readFramebuffer;
    private int drawFramebuffer;
    // (MemoryUtil.memFree の引数の型が LWJGL 3.3 で変わったので、GC に任せる作り方にする)
    private final PointerBuffer lockObjects = BufferUtils.createPointerBuffer(1);

    /** Spout2 を使えるか (Windows で、OpenGL と Direct3D の相互運用に対応した GPU か)。今の OpenGL のコンテキストで調べる */
    public static boolean available() {
        final String os = System.getProperty("os.name", "");
        if (!os.toLowerCase(java.util.Locale.ROOT).contains("win")) return false;
        try {
            return GL.getCapabilitiesWGL().WGL_NV_DX_interop2;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 送信者を作る。同じ名前の送信者がもういれば、名前の後ろに _1, _2 ... を付ける */
    public SpoutSender(String requestedName) {
        this.requestedName = requestedName;
        // OpenGL と相互運用を開ける GPU の Direct3D 11 デバイスを選ぶ
        d3d = D3D11.forOpenGl(glGetString(GL_RENDERER), openGlLuid(), new D3D11.DeviceCheck() {
            public boolean accept(long device) {
                interopDevice = WGLNVDXInterop.wglDXOpenDeviceNV(device);
                return interopDevice != 0;
            }
        });
        // 名前の並びの長さ。Spout の設定 (レジストリ) で変えられ、既定は 64
        maxSenders = win32.maxSenders();
        senderNames = win32.new SharedMemory("SpoutSenderNames", maxSenders * MAX_NAME);
        name = register(requestedName);
        info = win32.new SharedMemory(name, SHARED_TEXTURE_INFO_SIZE);
        accessMutex = win32.createMutex(name + "_SpoutAccessMutex");
        frameSemaphore = win32.createSemaphore(name + "_Count_Semaphore", 1, Integer.MAX_VALUE);
    }

    // 今の OpenGL のコンテキストが動いている GPU の LUID。分からなければ 0
    static long openGlLuid() {
        try {
            final GLCapabilities caps = GL.getCapabilities();
            if (!(caps.GL_EXT_memory_object_win32 || caps.GL_EXT_semaphore_win32)) return 0;
            final ByteBuffer luid = BufferUtils.createByteBuffer(8).order(ByteOrder.LITTLE_ENDIAN);
            EXTMemoryObject.glGetUnsignedBytevEXT(GL_DEVICE_LUID_EXT, luid);
            return luid.getLong(0);
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    /** その名前の送信者が Spout の名前の並びにあるか (動作確認用) */
    public static boolean isRegistered(String sender) {
        final Win32 win32 = new Win32();
        final int max = win32.maxSenders();
        final Win32.SharedMemory names = win32.new SharedMemory("SpoutSenderNames", max * MAX_NAME);
        try {
            final long buffer = names.lock();
            if (buffer == 0) return false;
            try {
                for (int i = 0; i < max; i++) {
                    final String entry = readString(buffer + (long) i * MAX_NAME);
                    if (entry.isEmpty()) return false;
                    if (entry.equals(sender)) return true;
                }
                return false;
            } finally {
                names.unlock();
            }
        } finally {
            names.close();
        }
    }

    /** 作るときに頼んだ名前 */
    public String requestedName() {
        return requestedName;
    }

    /** 実際に使っている名前 (同じ名前があったときは後ろに番号が付く) */
    public String name() {
        return name;
    }

    /**
     * 今の OpenGL のコンテキストのテクスチャ (GL_TEXTURE_2D) を送る。上下は Spout の向き (上の行が先) に直す。
     */
    public boolean send(int sourceTexture, int sourceWidth, int sourceHeight) {
        if (sourceWidth != width || sourceHeight != height || texture == null) {
            resize(sourceWidth, sourceHeight);
        }
        if (!win32.waitFor(accessMutex, 67)) return false;
        lockObjects.put(0, interopObject);
        final PointerBuffer objects = lockObjects;
        try {
            if (!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, objects)) return false;
            try {
                final int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
                final int previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
                if (readFramebuffer == 0) readFramebuffer = glGenFramebuffers();
                if (drawFramebuffer == 0) drawFramebuffer = glGenFramebuffers();
                glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
                glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sourceTexture, 0);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, glTexture, 0);
                // OpenGL は下の行から、Direct3D は上の行から並ぶので、上下を反転して写す
                glBlitFramebuffer(0, 0, width, height, 0, height, width, 0, GL_COLOR_BUFFER_BIT, GL_NEAREST);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
            } finally {
                WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, objects);
            }
        } finally {
            win32.releaseMutex(accessMutex);
        }
        // 新しいフレームの合図: 1 減らして 2 増やす (Spout の SetNewFrame と同じ)
        if (win32.waitFor(frameSemaphore, 0)) win32.releaseSemaphore(frameSemaphore, 2);
        return true;
    }

    // 大きさが変わったら、共有テクスチャを作り直して、送信者の情報を書き直す
    private void resize(int newWidth, int newHeight) {
        releaseTexture();
        width = newWidth;
        height = newHeight;
        texture = d3d.createSharedTexture(width, height);
        glTexture = glGenTextures();
        // 名前を取っただけではテクスチャの実体がないので、一度結び付けてから相互運用に登録する
        final int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, glTexture);
        glBindTexture(GL_TEXTURE_2D, previous);
        interopObject = WGLNVDXInterop.wglDXRegisterObjectNV(interopDevice, texture.texture, glTexture,
                GL_TEXTURE_2D, WGL_ACCESS_WRITE_DISCARD_NV);
        if (interopObject == 0) {
            releaseTexture();
            throw new IllegalStateException("wglDXRegisterObjectNV failed");
        }
        writeInfo();
    }

    private void writeInfo() {
        final long buffer = info.lock();
        if (buffer == 0) return;
        try {
            MemoryUtil.memSet(buffer, 0, SHARED_TEXTURE_INFO_SIZE);
            MemoryUtil.memPutInt(buffer, (int) texture.shareHandle);  // shareHandle (32 ビットに収まる)
            MemoryUtil.memPutInt(buffer + 4, width);
            MemoryUtil.memPutInt(buffer + 8, height);
            MemoryUtil.memPutInt(buffer + 12, D3D11.DXGI_FORMAT_B8G8R8A8_UNORM);
            MemoryUtil.memPutInt(buffer + 16, 0);                     // usage
            final byte[] path = win32.modulePath();                   // description: 送っているプログラムの場所
            for (int i = 0; i < Math.min(255, path.length); i++) {
                MemoryUtil.memPutByte(buffer + 20 + i, path[i]);
            }
            MemoryUtil.memPutInt(buffer + 276, 0);                    // partnerId
        } finally {
            info.unlock();
        }
    }

    // ---------- 名前の並び ----------

    private String register(String requested) {
        final long buffer = senderNames.lock();
        if (buffer == 0) throw new IllegalStateException("Couldn't lock SpoutSenderNames");
        try {
            final TreeSet<String> names = readNames(buffer);
            String unique = requested;
            for (int i = 1; names.contains(unique); i++) {
                unique = requested + "_" + i;
            }
            if (names.size() < maxSenders) {
                names.add(unique);
                writeNames(buffer, names);
                setActive(unique);
            }
            return unique;
        } finally {
            senderNames.unlock();
        }
    }

    private void unregister() {
        final long buffer = senderNames.lock();
        if (buffer == 0) return;
        try {
            final TreeSet<String> names = readNames(buffer);
            if (!names.remove(name)) return;
            writeNames(buffer, names);
            // 自分が「選ばれている送信者」だったら、残っているものの先頭に替える
            if (!names.isEmpty()) {
                final Win32.SharedMemory active = win32.new SharedMemory("ActiveSenderName", MAX_NAME);
                try {
                    final long activeBuffer = active.lock();
                    if (activeBuffer != 0) {
                        final String current = readString(activeBuffer);
                        active.unlock();
                        if (current.equals(name) || names.size() == 1) setActive(names.first());
                    }
                } finally {
                    active.close();
                }
            }
        } finally {
            senderNames.unlock();
        }
    }

    private void setActive(String sender) {
        final Win32.SharedMemory active = win32.new SharedMemory("ActiveSenderName", MAX_NAME);
        try {
            final long buffer = active.lock();
            if (buffer == 0) return;
            try {
                writeString(buffer, sender);
            } finally {
                active.unlock();
            }
        } finally {
            active.close();
        }
    }

    private TreeSet<String> readNames(long buffer) {
        // Spout は std::set (名前の順) で持ち、空の名前で終わる
        final TreeSet<String> names = new TreeSet<String>();
        for (int i = 0; i < maxSenders; i++) {
            final String entry = readString(buffer + (long) i * MAX_NAME);
            if (entry.isEmpty()) break;
            names.add(entry);
        }
        return names;
    }

    private void writeNames(long buffer, TreeSet<String> names) {
        int i = 0;
        for (String entry : names) {
            if (i >= maxSenders) break;
            writeString(buffer + (long) i * MAX_NAME, entry);
            i++;
        }
        if (i < maxSenders) MemoryUtil.memPutByte(buffer + (long) i * MAX_NAME, (byte) 0);
    }

    private static String readString(long address) {
        int length = 0;
        while (length < MAX_NAME - 1 && MemoryUtil.memGetByte(address + length) != 0) length++;
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = MemoryUtil.memGetByte(address + i);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(long address, String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        final int length = Math.min(MAX_NAME - 1, bytes.length);
        for (int i = 0; i < length; i++) MemoryUtil.memPutByte(address + i, bytes[i]);
        MemoryUtil.memPutByte(address + length, (byte) 0);
    }

    // ---------- 後片付け ----------

    private void releaseTexture() {
        if (interopObject != 0) {
            WGLNVDXInterop.wglDXUnregisterObjectNV(interopDevice, interopObject);
            interopObject = 0;
        }
        if (glTexture != 0) {
            glDeleteTextures(glTexture);
            glTexture = 0;
        }
        if (texture != null) {
            d3d.release(texture.texture);
            texture = null;
        }
    }

    /** 送信をやめる。受け取る側の一覧からも消える */
    public void close() {
        try {
            unregister();
        } finally {
            releaseTexture();
            if (readFramebuffer != 0) glDeleteFramebuffers(readFramebuffer);
            if (drawFramebuffer != 0) glDeleteFramebuffers(drawFramebuffer);
            readFramebuffer = drawFramebuffer = 0;
            info.close();
            senderNames.close();
            win32.closeHandle(accessMutex);
            win32.closeHandle(frameSemaphore);
            WGLNVDXInterop.wglDXCloseDeviceNV(interopDevice);
            d3d.close();
        }
    }
}
