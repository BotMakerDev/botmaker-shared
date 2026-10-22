package com.botmaker.shared.capture.linux;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.List;

/**
 * JNA bindings for the MIT-SHM extension (libXext) and the System V shared memory calls it rests on (libc), so a frame
 * is read into memory the X server writes straight into, not sent over the socket (2026-10-22, OBS's X11 route).
 *
 * <p>Loaded lazily and defensively via {@link #instance()}: {@code null} when libXext is missing, and the caller reads
 * frames the old way.
 */
public interface XShm extends Library {

    /** True when the server offers MIT-SHM on this connection; false over the network, where no memory is shared. */
    boolean XShmQueryExtension(Pointer display);

    /**
     * An image of {@code width × height} in {@code visual}'s layout whose pixels live in {@code shminfo}'s segment;
     * its {@code data} is set by the caller to the segment's address. Freed with {@link X11#XFree}, which leaves the
     * segment alone.
     */
    X11.XImage XShmCreateImage(Pointer display, Pointer visual, int depth, int format, Pointer data,
                               SegmentInfo shminfo, int width, int height);

    boolean XShmAttach(Pointer display, SegmentInfo shminfo);

    boolean XShmDetach(Pointer display, SegmentInfo shminfo);

    /** {@code drawable}'s pixels from {@code (x, y)} read into {@code image}'s segment; false on any error. */
    boolean XShmGetImage(Pointer display, Pointer drawable, X11.XImage image, int x, int y, NativeLong planeMask);

    /**
     * {@code XShmSegmentInfo} (XShm.h). The image keeps a pointer to it, so it must outlive the image: one per grab,
     * held as long as the segment is attached.
     */
    class SegmentInfo extends Structure {
        public NativeLong shmseg;
        public int shmid;
        public Pointer shmaddr;
        public boolean readOnly;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("shmseg", "shmid", "shmaddr", "readOnly");
        }
    }

    /** libc's System V shared memory (sys/shm.h), LP64. */
    interface Ipc extends Library {
        int IPC_PRIVATE = 0;
        int IPC_CREAT = 0x200;
        int IPC_RMID = 0;
        /** Read and write by this user only. */
        int MODE_OWNER = 0x180;

        int shmget(int key, long size, int flags);

        /** The segment mapped here, or {@code (void *) -1} when it could not be. */
        Pointer shmat(int shmid, Pointer address, int flags);

        int shmdt(Pointer address);

        int shmctl(int shmid, int command, Pointer buffer);
    }

    /** Lazily-loaded singleton; {@code null} when libXext could not be loaded. */
    static XShm instance() {
        return Holder.INSTANCE;
    }

    /** libc's shared memory calls; {@code null} when libc could not be loaded, as {@link #instance()}. */
    static Ipc ipc() {
        return Holder.IPC;
    }

    /** Deferred, exception-safe load so a missing libXext doesn't break window capture. */
    final class Holder {
        static final XShm INSTANCE = load("Xext", XShm.class);
        static final Ipc IPC = load("c", Ipc.class);

        private static <T extends Library> T load(String library, Class<T> type) {
            try {
                return Native.load(library, type);
            } catch (Throwable t) {
                return null;
            }
        }

        private Holder() {}
    }
}
