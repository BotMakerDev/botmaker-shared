package com.botmaker.shared.capture.windows;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.ptr.PointerByReference;

/**
 * Calls into COM and WinRT interfaces by vtable slot, for the few that jna-platform has no binding for (the
 * Direct3D 11 device and the Windows.Graphics.Capture types {@link WgcCapture} uses).
 *
 * <p>A slot is the method's position in the interface <em>including</em> every base interface: {@code IUnknown}
 * takes 0–2 ({@code QueryInterface}, {@code AddRef}, {@code Release}) and {@code IInspectable} 3–5, so a WinRT
 * interface's own first method is slot 6. <b>A wrong slot calls a different method with the wrong arguments and
 * takes the JVM down</b>; each slot used is named at its constant, with the interface it belongs to.
 */
final class Com {

    private Com() {}

    /** A failed {@code HRESULT}, with what was being done. */
    static final class Failure extends Exception {
        final int hresult;

        Failure(String what, int hresult) {
            super(what + " failed: HRESULT 0x" + Integer.toHexString(hresult));
            this.hresult = hresult;
        }
    }

    /** Call slot {@code slot} of {@code self}, which returns an {@code HRESULT}. */
    static int call(Pointer self, int slot, Object... args) {
        return function(self, slot).invokeInt(withSelf(self, args));
    }

    /** Call slot {@code slot} of {@code self}, which returns nothing. */
    static void callVoid(Pointer self, int slot, Object... args) {
        function(self, slot).invokeVoid(withSelf(self, args));
    }

    /** {@link #call}, throwing on a failed {@code HRESULT}. */
    static void check(String what, Pointer self, int slot, Object... args) throws Failure {
        check(what, call(self, slot, args));
    }

    static void check(String what, int hresult) throws Failure {
        if (hresult < 0) {
            throw new Failure(what, hresult);
        }
    }

    /** {@code self} as interface {@code iid} ({@code IUnknown::QueryInterface}, slot 0); release it after. */
    static Pointer query(String what, Pointer self, GUID iid) throws Failure {
        PointerByReference out = new PointerByReference();
        check(what, self, 0, iid, out);
        return out.getValue();
    }

    /** {@code IUnknown::Release}, slot 2. Null-safe. */
    static void release(Pointer self) {
        if (self != null) {
            call(self, 2);
        }
    }

    /** {@code IClosable::Close} (slot 6) then release; for WinRT objects holding a resource. Never throws. */
    static void close(Pointer self) {
        if (self == null) {
            return;
        }
        try {
            Pointer closable = query("IClosable", self, IID_ICLOSABLE);
            call(closable, 6);
            release(closable);
        } catch (Failure ignored) {
            // Not closable: releasing is all there is.
        }
        release(self);
    }

    static final GUID IID_ICLOSABLE = GUID.fromString("{30D5A829-7FA4-4026-83BB-D75BAE4EA99E}");

    private static Function function(Pointer self, int slot) {
        Pointer vtable = self.getPointer(0);
        return Function.getFunction(vtable.getPointer((long) slot * Native.POINTER_SIZE), Function.ALT_CONVENTION);
    }

    private static Object[] withSelf(Pointer self, Object[] args) {
        Object[] all = new Object[args.length + 1];
        all[0] = self;
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }
}
