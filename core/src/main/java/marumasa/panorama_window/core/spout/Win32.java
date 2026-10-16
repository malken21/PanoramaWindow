package marumasa.panorama_window.core.spout;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Spout の取り決めに使う kernel32 の関数 (名前付きの共有メモリ・ミューテックス・セマフォ)。
 */
final class Win32 {

    private static final long INVALID_HANDLE_VALUE = -1L;
    private static final int PAGE_READWRITE = 0x04;
    private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
    static final int WAIT_OBJECT_0 = 0;

    private final NativeCalls calls = NativeCalls.get();
    private final long createFileMapping = kernel32("CreateFileMappingA");
    private final long mapViewOfFile = kernel32("MapViewOfFile");
    private final long unmapViewOfFile = kernel32("UnmapViewOfFile");
    private final long closeHandle = kernel32("CloseHandle");
    private final long createMutex = kernel32("CreateMutexA");
    private final long releaseMutex = kernel32("ReleaseMutex");
    private final long createSemaphore = kernel32("CreateSemaphoreA");
    private final long releaseSemaphore = kernel32("ReleaseSemaphore");
    private final long waitForSingleObject = kernel32("WaitForSingleObject");
    private final long getModuleFileName = kernel32("GetModuleFileNameA");

    private long kernel32(String name) {
        final long function = calls.function("kernel32", name);
        if (function == 0) throw new IllegalStateException("kernel32!" + name + " was not found");
        return function;
    }

    /** 名前付きの共有メモリ (と、それを守るミューテックス "名前_mutex")。Spout の SpoutSharedMemory と同じもの */
    final class SharedMemory {
        final String name;
        final int size;
        private long mapping;
        private long view;
        private long mutex;

        SharedMemory(String name, int size) {
            this.name = name;
            this.size = size;
            final ByteBuffer cName = ascii(name);
            try {
                mapping = calls.call(createFileMapping, INVALID_HANDLE_VALUE, 0, PAGE_READWRITE, 0, size, MemoryUtil.memAddress(cName));
            } finally {
                MemoryUtil.memFree(cName);
            }
            if (mapping == 0) throw new IllegalStateException("CreateFileMapping failed for " + name);
            view = calls.call(mapViewOfFile, mapping, FILE_MAP_ALL_ACCESS, 0, 0, 0);
            if (view == 0) {
                close();
                throw new IllegalStateException("MapViewOfFile failed for " + name);
            }
            mutex = createMutex(name + "_mutex");
        }

        /** ミューテックスを取る (取れなければ 0)。Spout と同じく 67 ミリ秒まで待つ */
        long lock() {
            return waitFor(mutex, 67) ? view : 0;
        }

        void unlock() {
            calls.call(releaseMutex, mutex);
        }

        void close() {
            if (view != 0) calls.call(unmapViewOfFile, view);
            if (mapping != 0) calls.call(closeHandle, mapping);
            if (mutex != 0) calls.call(closeHandle, mutex);
            view = mapping = mutex = 0;
        }
    }

    long createMutex(String name) {
        final ByteBuffer cName = ascii(name);
        try {
            return calls.call(createMutex, 0, 0, MemoryUtil.memAddress(cName));
        } finally {
            MemoryUtil.memFree(cName);
        }
    }

    long createSemaphore(String name, int initial, int maximum) {
        final ByteBuffer cName = ascii(name);
        try {
            return calls.call(createSemaphore, 0, initial, maximum, MemoryUtil.memAddress(cName));
        } finally {
            MemoryUtil.memFree(cName);
        }
    }

    boolean waitFor(long handle, int milliseconds) {
        return handle != 0 && (int) calls.call(waitForSingleObject, handle, milliseconds) == WAIT_OBJECT_0;
    }

    void releaseMutex(long handle) {
        if (handle != 0) calls.call(releaseMutex, handle);
    }

    void releaseSemaphore(long handle, int count) {
        if (handle != 0) calls.call(releaseSemaphore, handle, count, 0);
    }

    void closeHandle(long handle) {
        if (handle != 0) calls.call(closeHandle, handle);
    }

    /**
     * Spout の送信者の名前を並べる数。Spout の設定 (HKEY_CURRENT_USER\Software\Leading Edge\Spout の MaxSenders)
     * で変えられ、すべてのアプリが同じ値を使わないと、名前の並びの共有メモリの大きさが食い違う。
     */
    int maxSenders() {
        final long regGetValue = calls.function("advapi32", "RegGetValueA");
        if (regGetValue == 0) return 64;
        final ByteBuffer key = ascii("Software\\Leading Edge\\Spout");
        final ByteBuffer value = ascii("MaxSenders");
        final ByteBuffer data = MemoryUtil.memCalloc(8);
        try {
            data.putInt(4, 4);
            // HKEY_CURRENT_USER は (HKEY)(LONG)0x80000001 なので、64 ビットでは符号が広がった値になる
            final int result = (int) calls.call(regGetValue, 0xFFFFFFFF80000001L, MemoryUtil.memAddress(key),
                    MemoryUtil.memAddress(value), 0x10 /* RRF_RT_REG_DWORD */, 0, MemoryUtil.memAddress(data),
                    MemoryUtil.memAddress(data) + 4);
            final int count = data.getInt(0);
            return result == 0 && count > 0 && count <= 10000 ? count : 64;
        } finally {
            MemoryUtil.memFree(data);
            MemoryUtil.memFree(value);
            MemoryUtil.memFree(key);
        }
    }

    /** この実行ファイル (java.exe など) の場所。Spout は送信者の説明にこれを入れる */
    byte[] modulePath() {
        final ByteBuffer buffer = MemoryUtil.memCalloc(260);
        try {
            final int length = (int) calls.call(getModuleFileName, 0, MemoryUtil.memAddress(buffer), 260);
            final byte[] bytes = new byte[Math.max(0, Math.min(259, length))];
            buffer.get(bytes);
            return bytes;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    // 終端の 0 付きの ASCII (Spout の名前は 8 ビットの文字列)
    static ByteBuffer ascii(String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = MemoryUtil.memCalloc(bytes.length + 1);
        buffer.put(bytes).flip();
        buffer.limit(bytes.length + 1);
        return buffer;
    }
}
