package com.botmaker.shared.capture.windows;

import com.botmaker.shared.Diag;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * One window's pixels through <b>Windows.Graphics.Capture</b> (Windows 10 1903+), the API OBS and the Snipping
 * Tool use: it reads the window's own surface from the compositor, so a DirectX game comes back when
 * {@code PrintWindow} gives black, and a covered window comes back as itself rather than as whatever covers it.
 *
 * <p><b>Opt-in</b> ({@code -Dbotmaker.windows.capture=wgc}) until it has run on a real Windows machine: it is
 * written against the interfaces' vtable layouts through {@link Com}, and a wrong slot takes the JVM down rather
 * than throwing. Every slot is named at its constant. With the property unset this class is never loaded.
 *
 * <p>A capture session stays open on the last window asked for, holding one frame buffer; asking for another
 * window, or the window changing size, opens a new one. Each frame is copied out through a CPU-readable staging
 * texture and cropped to the client area, the same area {@code PrintWindow} and the window's rect describe.
 */
final class WgcCapture {

    /** The run property that turns this path on. */
    static final String PROPERTY = "botmaker.windows.capture";

    /** Whether the run asked for this path. */
    static boolean requested() {
        return "wgc".equalsIgnoreCase(System.getProperty(PROPERTY, "").trim());
    }

    // --- interface ids ---
    private static final GUID IID_IDXGIDEVICE = GUID.fromString("{54EC77FA-1377-44E6-8C32-88FD5F44C84C}");
    private static final GUID IID_IDIRECT3DDEVICE = GUID.fromString("{A37624AB-8D5F-4650-9D3E-9EAE3D9BC670}");
    private static final GUID IID_ITEM_INTEROP = GUID.fromString("{3628E81B-3CAC-4C60-B7F4-23CE0E0C3356}");
    private static final GUID IID_ITEM = GUID.fromString("{79C3F95B-31F7-4EC2-A464-632EF5D30760}");
    private static final GUID IID_POOL_STATICS2 = GUID.fromString("{589B103F-6BBC-5DF5-A991-02E28B3B66D5}");
    private static final GUID IID_SESSION2 = GUID.fromString("{2C39AE40-7D2E-5044-804E-8B6799D4CF9E}");
    private static final GUID IID_SESSION3 = GUID.fromString("{F2CDD966-22AE-5EA1-9596-3A289344C3BE}");
    private static final GUID IID_DXGI_ACCESS = GUID.fromString("{A9B3D012-3DF2-4EE3-B8D1-8695F457D3C1}");
    private static final GUID IID_TEXTURE2D = GUID.fromString("{6F15AAF2-D208-4E89-9AB4-489535D34F9C}");

    // --- vtable slots (IUnknown 0–2, IInspectable 3–5) ---
    /** {@code IGraphicsCaptureItemInterop::CreateForWindow} — an IUnknown interface, so its first slot is 3. */
    private static final int INTEROP_CREATE_FOR_WINDOW = 3;
    /** {@code IGraphicsCaptureItem}: 6 get_DisplayName, 7 get_Size. */
    private static final int ITEM_GET_SIZE = 7;
    /** {@code IDirect3D11CaptureFramePoolStatics2::CreateFreeThreaded}. */
    private static final int STATICS2_CREATE_FREE_THREADED = 6;
    /** {@code IDirect3D11CaptureFramePool}: 6 Recreate, 7 TryGetNextFrame, 8/9 FrameArrived, 10 CreateCaptureSession. */
    private static final int POOL_TRY_GET_NEXT_FRAME = 7;
    private static final int POOL_CREATE_CAPTURE_SESSION = 10;
    /** {@code IGraphicsCaptureSession::StartCapture}. */
    private static final int SESSION_START_CAPTURE = 6;
    /** {@code IGraphicsCaptureSession2}: 6 get, 7 put_IsCursorCaptureEnabled. */
    private static final int SESSION2_PUT_CURSOR = 7;
    /** {@code IGraphicsCaptureSession3}: 6 get, 7 put_IsBorderRequired. */
    private static final int SESSION3_PUT_BORDER = 7;
    /** {@code IDirect3D11CaptureFrame}: 6 get_Surface, 7 get_SystemRelativeTime, 8 get_ContentSize. */
    private static final int FRAME_GET_SURFACE = 6;
    private static final int FRAME_GET_CONTENT_SIZE = 8;
    /** {@code IDirect3DDxgiInterfaceAccess::GetInterface} — IUnknown-based, slot 3. */
    private static final int DXGI_ACCESS_GET_INTERFACE = 3;
    /** {@code ID3D11Device::CreateTexture2D}: 3 CreateBuffer, 4 CreateTexture1D, 5 CreateTexture2D. */
    private static final int DEVICE_CREATE_TEXTURE2D = 5;
    /** {@code ID3D11Texture2D::GetDesc}: DeviceChild 3–6, Resource 7–9, then 10. */
    private static final int TEXTURE_GET_DESC = 10;
    /** {@code ID3D11DeviceContext}: DeviceChild 3–6, then 7 VSSetConstantBuffers … 14 Map, 15 Unmap, 47 CopyResource. */
    private static final int CONTEXT_MAP = 14;
    private static final int CONTEXT_UNMAP = 15;
    private static final int CONTEXT_COPY_RESOURCE = 47;

    private static final int D3D_DRIVER_TYPE_HARDWARE = 1;
    private static final int D3D11_CREATE_DEVICE_BGRA_SUPPORT = 0x20;
    private static final int D3D11_SDK_VERSION = 7;
    private static final int PIXEL_FORMAT_B8G8R8A8_UNORM = 87;
    private static final int D3D11_USAGE_STAGING = 3;
    private static final int D3D11_CPU_ACCESS_READ = 0x20000;
    private static final int D3D11_MAP_READ = 1;
    private static final int RPC_E_CHANGED_MODE = 0x80010106;
    private static final long FIRST_FRAME_WAIT_MS = 500;

    interface D3D11 extends StdCallLibrary {
        D3D11 INSTANCE = Native.load("d3d11", D3D11.class);

        int D3D11CreateDevice(Pointer adapter, int driverType, Pointer software, int flags, Pointer featureLevels,
                              int featureLevelCount, int sdkVersion, PointerByReference device,
                              IntByReference featureLevel, PointerByReference context);

        int CreateDirect3D11DeviceFromDXGIDevice(Pointer dxgiDevice, PointerByReference graphicsDevice);
    }

    interface Combase extends StdCallLibrary {
        Combase INSTANCE = Native.load("combase", Combase.class, W32APIOptions.DEFAULT_OPTIONS);

        int RoInitialize(int initType);

        int RoGetActivationFactory(Pointer activatableClassId, GUID iid, PointerByReference factory);

        int WindowsCreateString(WString source, int length, PointerByReference string);

        int WindowsDeleteString(Pointer string);
    }

    private WgcCapture() {}

    // --- state, guarded by the class lock ---
    private static boolean broken;
    private static Pointer device;        // ID3D11Device
    private static Pointer context;       // ID3D11DeviceContext
    private static Pointer winrtDevice;   // IDirect3DDevice
    private static Open open;
    private static final ThreadLocal<Boolean> APARTMENT = ThreadLocal.withInitial(() -> false);

    /** The open session: its window, its size, and the last frame it gave. */
    private record Open(HWND hwnd, int width, int height, Pointer item, Pointer pool, Pointer session,
                        BufferedImage[] last) {}

    /**
     * {@code hWnd}'s client area through the compositor, or {@code null} when this path can't give it (an older
     * Windows, a window that closed, no frame within {@value #FIRST_FRAME_WAIT_MS} ms). Never throws.
     */
    static synchronized BufferedImage capture(HWND hWnd) {
        if (broken) {
            return null;
        }
        try {
            enterApartment();
            ensureDevice();
            // The session is the top-level window's: WGC captures top-levels only, and a bot that captures a
            // child while the click watch captures its root must not reopen the session on every call.
            HWND root = User32.INSTANCE.GetAncestor(hWnd, User32.GA_ROOT);
            Open session = sessionFor(root == null ? hWnd : root);
            BufferedImage frame = latestFrame(session);
            if (frame == null) {
                return null;
            }
            Rectangle client = clientInFrame(session.hwnd(), hWnd, frame.getWidth(), frame.getHeight());
            return client == null ? null : frame.getSubimage(client.x, client.y, client.width, client.height);
        } catch (Com.Failure | RuntimeException | LinkageError e) {
            closeSession();
            if (device == null) {
                broken = true;
                Diag.error("Windows", "Windows.Graphics.Capture is unavailable (" + e.getMessage()
                        + "); capturing with PrintWindow instead");
            } else {
                Diag.log("[Windows] Windows.Graphics.Capture could not read this window: " + e.getMessage());
            }
            return null;
        }
    }

    private static void enterApartment() throws Com.Failure {
        if (APARTMENT.get()) {
            return;
        }
        int hr = Combase.INSTANCE.RoInitialize(1); // RO_INIT_MULTITHREADED
        if (hr != RPC_E_CHANGED_MODE) {
            Com.check("RoInitialize", hr);
        }
        APARTMENT.set(true);
    }

    private static void ensureDevice() throws Com.Failure {
        if (winrtDevice != null) {
            return;
        }
        PointerByReference d = new PointerByReference();
        PointerByReference c = new PointerByReference();
        Com.check("D3D11CreateDevice", D3D11.INSTANCE.D3D11CreateDevice(null, D3D_DRIVER_TYPE_HARDWARE, null,
                D3D11_CREATE_DEVICE_BGRA_SUPPORT, null, 0, D3D11_SDK_VERSION, d, null, c));
        Pointer dxgi = Com.query("IDXGIDevice", d.getValue(), IID_IDXGIDEVICE);
        PointerByReference inspectable = new PointerByReference();
        try {
            Com.check("CreateDirect3D11DeviceFromDXGIDevice",
                    D3D11.INSTANCE.CreateDirect3D11DeviceFromDXGIDevice(dxgi, inspectable));
        } finally {
            Com.release(dxgi);
        }
        Pointer direct3d = Com.query("IDirect3DDevice", inspectable.getValue(), IID_IDIRECT3DDEVICE);
        Com.release(inspectable.getValue());
        device = d.getValue();
        context = c.getValue();
        winrtDevice = direct3d;
    }

    private static Open sessionFor(HWND hWnd) throws Com.Failure {
        if (open != null && open.hwnd().equals(hWnd)) {
            return open;
        }
        closeSession();
        Pointer interop = factory("Windows.Graphics.Capture.GraphicsCaptureItem", IID_ITEM_INTEROP);
        PointerByReference item = new PointerByReference();
        try {
            Com.check("CreateForWindow", interop, INTEROP_CREATE_FOR_WINDOW, hWnd, IID_ITEM, item);
        } finally {
            Com.release(interop);
        }
        Memory size = new Memory(8);
        Com.check("GraphicsCaptureItem.Size", item.getValue(), ITEM_GET_SIZE, size);
        int width = size.getInt(0);
        int height = size.getInt(4);
        Pointer statics = factory("Windows.Graphics.Capture.Direct3D11CaptureFramePool", IID_POOL_STATICS2);
        PointerByReference pool = new PointerByReference();
        PointerByReference session = new PointerByReference();
        try {
            // SizeInt32 is passed by value: two int32s, which the x64 and ARM64 ABIs pass as one 64-bit integer.
            Com.check("CreateFreeThreaded", statics, STATICS2_CREATE_FREE_THREADED, winrtDevice,
                    PIXEL_FORMAT_B8G8R8A8_UNORM, 1, packSize(width, height), pool);
        } finally {
            Com.release(statics);
        }
        Com.check("CreateCaptureSession", pool.getValue(), POOL_CREATE_CAPTURE_SESSION, item.getValue(), session);
        quietly(session.getValue(), IID_SESSION2, SESSION2_PUT_CURSOR);
        quietly(session.getValue(), IID_SESSION3, SESSION3_PUT_BORDER);
        Com.check("StartCapture", session.getValue(), SESSION_START_CAPTURE);
        open = new Open(hWnd, width, height, item.getValue(), pool.getValue(), session.getValue(),
                new BufferedImage[1]);
        return open;
    }

    /**
     * Turn a session option off (the cursor drawn into frames, the yellow capture border): both are newer than
     * the API itself, so an older Windows refusing one is not a failure.
     */
    private static void quietly(Pointer session, GUID iid, int putSlot) {
        try {
            Pointer extra = Com.query("session option", session, iid);
            Com.call(extra, putSlot, 0);
            Com.release(extra);
        } catch (Com.Failure ignored) {
            // That Windows has no such option.
        }
    }

    /**
     * The newest frame, or the last one when nothing changed since — the pool only hands out a frame when the
     * window repainted. The first frame of a session is waited for.
     */
    private static BufferedImage latestFrame(Open session) throws Com.Failure {
        long deadline = System.currentTimeMillis() + FIRST_FRAME_WAIT_MS;
        while (true) {
            // One frame per call, read once: a game rendering faster than a read takes always has another frame
            // waiting, and reading until none is left never ended. With one buffer the pool holds at most one.
            Pointer frame = nextFrame(session);
            if (frame != null) {
                try {
                    Memory size = new Memory(8);
                    Com.check("ContentSize", frame, FRAME_GET_CONTENT_SIZE, size);
                    if (size.getInt(0) != session.width() || size.getInt(4) != session.height()) {
                        // Resized: this session's buffer is the old size. The next call opens a new one.
                        closeSession();
                        return null;
                    }
                    session.last()[0] = read(frame);
                } finally {
                    Com.close(frame);
                }
                return session.last()[0];
            }
            if (session.last()[0] != null || System.currentTimeMillis() >= deadline) {
                return session.last()[0];
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static Pointer nextFrame(Open session) throws Com.Failure {
        PointerByReference frame = new PointerByReference();
        Com.check("TryGetNextFrame", session.pool(), POOL_TRY_GET_NEXT_FRAME, frame);
        return frame.getValue();
    }

    /** Copy {@code frame}'s texture out through a staging texture the CPU can map. */
    private static BufferedImage read(Pointer frame) throws Com.Failure {
        PointerByReference surface = new PointerByReference();
        Com.check("Frame.Surface", frame, FRAME_GET_SURFACE, surface);
        Pointer access = null;
        Pointer texture = null;
        Pointer staging = null;
        try {
            access = Com.query("IDirect3DDxgiInterfaceAccess", surface.getValue(), IID_DXGI_ACCESS);
            PointerByReference tex = new PointerByReference();
            Com.check("GetInterface", access, DXGI_ACCESS_GET_INTERFACE, IID_TEXTURE2D, tex);
            texture = tex.getValue();
            Memory desc = new Memory(TextureDesc.SIZE);
            Com.callVoid(texture, TEXTURE_GET_DESC, desc);
            int width = desc.getInt(TextureDesc.WIDTH);
            int height = desc.getInt(TextureDesc.HEIGHT);
            TextureDesc.makeStaging(desc);
            PointerByReference st = new PointerByReference();
            Com.check("CreateTexture2D", device, DEVICE_CREATE_TEXTURE2D, desc, null, st);
            staging = st.getValue();
            Com.callVoid(context, CONTEXT_COPY_RESOURCE, staging, texture);
            Memory mapped = new Memory(Native.POINTER_SIZE + 8L);
            Com.check("Map", context, CONTEXT_MAP, staging, 0, D3D11_MAP_READ, 0, mapped);
            try {
                Pointer data = mapped.getPointer(0);
                int rowPitch = mapped.getInt(Native.POINTER_SIZE);
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
                for (int y = 0; y < height; y++) {
                    // B, G, R, A bytes read as a little-endian int are 0xAARRGGBB — the image's own layout.
                    data.read((long) y * rowPitch, pixels, y * width, width);
                }
                return image;
            } finally {
                Com.callVoid(context, CONTEXT_UNMAP, staging, 0);
            }
        } finally {
            Com.release(staging);
            Com.release(texture);
            Com.release(access);
            Com.release(surface.getValue());
        }
    }

    /**
     * {@code hWnd}'s client area within a frame of top-level {@code root}'s visible bounds; {@code null} when it
     * has none.
     */
    private static Rectangle clientInFrame(HWND root, HWND hWnd, int frameWidth, int frameHeight) {
        RECT bounds = new RECT();
        if (Dwmapi.INSTANCE.DwmGetWindowAttribute(root.getPointer(), Dwmapi.DWMWA_EXTENDED_FRAME_BOUNDS, bounds,
                bounds.size()) != 0) {
            User32.INSTANCE.GetWindowRect(root.getPointer(), bounds);
        }
        Rectangle client = WindowsController.clientRect(hWnd);
        return client == null ? null : WindowFrames.clientWithin(
                new Rectangle(bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top),
                client, frameWidth, frameHeight);
    }

    private static Pointer factory(String className, GUID iid) throws Com.Failure {
        PointerByReference name = new PointerByReference();
        Com.check("WindowsCreateString", Combase.INSTANCE.WindowsCreateString(new WString(className),
                className.length(), name));
        try {
            PointerByReference factory = new PointerByReference();
            Com.check("RoGetActivationFactory " + className,
                    Combase.INSTANCE.RoGetActivationFactory(name.getValue(), iid, factory));
            return factory.getValue();
        } finally {
            Combase.INSTANCE.WindowsDeleteString(name.getValue());
        }
    }

    private static void closeSession() {
        Open was = open;
        open = null;
        if (was != null) {
            Com.close(was.session());
            Com.close(was.pool());
            Com.release(was.item());
        }
    }

    /** {@code SizeInt32 { Width; Height }} as the one 64-bit register it travels in. */
    static long packSize(int width, int height) {
        return (width & 0xFFFFFFFFL) | ((long) height << 32);
    }

    /** {@code D3D11_TEXTURE2D_DESC}: eleven 32-bit fields. */
    static final class TextureDesc {
        static final int SIZE = 44;
        static final int WIDTH = 0;
        static final int HEIGHT = 4;
        static final int MIP_LEVELS = 8;
        static final int ARRAY_SIZE = 12;
        static final int SAMPLE_COUNT = 20;
        static final int SAMPLE_QUALITY = 24;
        static final int USAGE = 28;
        static final int BIND_FLAGS = 32;
        static final int CPU_ACCESS = 36;
        static final int MISC_FLAGS = 40;

        private TextureDesc() {}

        /** The same size and format, as one plain texture the CPU can read. */
        static void makeStaging(Memory desc) {
            desc.setInt(MIP_LEVELS, 1);
            desc.setInt(ARRAY_SIZE, 1);
            desc.setInt(SAMPLE_COUNT, 1);
            desc.setInt(SAMPLE_QUALITY, 0);
            desc.setInt(USAGE, D3D11_USAGE_STAGING);
            desc.setInt(BIND_FLAGS, 0);
            desc.setInt(CPU_ACCESS, D3D11_CPU_ACCESS_READ);
            desc.setInt(MISC_FLAGS, 0);
        }
    }
}
