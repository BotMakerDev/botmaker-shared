package com.botmaker.shared.capture.linux.input;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * Minimal libc + Linux uinput bindings used by {@link UinputBackend} to create a virtual input device via
 * {@code /dev/uinput}. Pure JNA — no {@code ydotool}/{@code dotool} binary. Everything here is Linux/uinput
 * specific and 64-bit (LP64) sized.
 */
public interface CLib extends Library {
    CLib INSTANCE = Native.load("c", CLib.class);

    // open() flags
    int O_WRONLY = 1;
    int O_NONBLOCK = 0x800;

    int open(String pathname, int flags);
    int close(int fd);
    long write(int fd, Pointer buf, long count);

    // ioctl request is `unsigned long` (8 bytes on LP64). The int-arg form covers UI_DEV_CREATE/DESTROY
    // (arg ignored) and the UI_SET_*BIT calls; the Pointer form covers UI_DEV_SETUP / UI_ABS_SETUP.
    int ioctl(int fd, NativeLong request, int arg);
    int ioctl(int fd, NativeLong request, Pointer arg);

    // --- uinput / evdev constants (linux/input-event-codes.h, linux/uinput.h) ---
    int EV_SYN = 0x00;
    int EV_KEY = 0x01;
    int EV_REL = 0x02;
    int EV_ABS = 0x03;

    int SYN_REPORT = 0;
    int REL_WHEEL = 0x08;
    int ABS_X = 0x00;
    int ABS_Y = 0x01;

    int BTN_LEFT = 0x110;
    int BTN_RIGHT = 0x111;
    int BTN_MIDDLE = 0x112;

    int BUS_USB = 0x03;

    // Device property telling libinput to treat an absolute device as a cursor-moving pointer (like a
    // touchpad) rather than a direct touchscreen/joystick — required for KWin/GNOME to honor ABS_X/ABS_Y.
    int INPUT_PROP_POINTER = 0x00;

    // _IOC direction bits
    int IOC_NONE = 0;
    int IOC_WRITE = 1;

    int UINPUT_IOCTL_BASE = 'U';

    /** Linux {@code _IOC(dir, type, nr, size)}: nr[0..7], type[8..15], size[16..29], dir[30..31]. */
    static NativeLong ioc(int dir, int type, int nr, int size) {
        long v = ((long) dir << 30) | ((long) size << 16) | ((long) type << 8) | (nr & 0xFF);
        return new NativeLong(v & 0xFFFFFFFFL);
    }

    static NativeLong UI_SET_EVBIT() { return ioc(IOC_WRITE, UINPUT_IOCTL_BASE, 100, 4); }
    static NativeLong UI_SET_KEYBIT() { return ioc(IOC_WRITE, UINPUT_IOCTL_BASE, 101, 4); }
    static NativeLong UI_SET_RELBIT() { return ioc(IOC_WRITE, UINPUT_IOCTL_BASE, 102, 4); }
    static NativeLong UI_SET_ABSBIT() { return ioc(IOC_WRITE, UINPUT_IOCTL_BASE, 103, 4); }
    static NativeLong UI_SET_PROPBIT() { return ioc(IOC_WRITE, UINPUT_IOCTL_BASE, 110, 4); }
    static NativeLong UI_DEV_CREATE() { return ioc(IOC_NONE, UINPUT_IOCTL_BASE, 1, 0); }
    static NativeLong UI_DEV_DESTROY() { return ioc(IOC_NONE, UINPUT_IOCTL_BASE, 2, 0); }
    static NativeLong UI_DEV_SETUP(int structSize) { return ioc(IOC_WRITE, UINPUT_IOCTL_BASE, 3, structSize); }
    static NativeLong UI_ABS_SETUP(int structSize) { return ioc(IOC_WRITE, UINPUT_IOCTL_BASE, 4, structSize); }

    /** {@code struct input_event} (24 bytes on 64-bit: 16B timeval + type/code/value). */
    class InputEvent extends Structure {
        public NativeLong tv_sec;
        public NativeLong tv_usec;
        public short type;
        public short code;
        public int value;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("tv_sec", "tv_usec", "type", "code", "value");
        }
    }

    /** {@code struct uinput_setup}: input_id (4×u16) + name[80] + ff_effects_max (92 bytes). */
    class UinputSetup extends Structure {
        public short bustype;
        public short vendor;
        public short product;
        public short version;
        public byte[] name = new byte[80];
        public int ff_effects_max;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("bustype", "vendor", "product", "version", "name", "ff_effects_max");
        }
    }

    /** {@code struct uinput_abs_setup}: u16 code + struct input_absinfo (28 bytes, code padded to 4). */
    class UinputAbsSetup extends Structure {
        public short code;
        public int value;
        public int minimum;
        public int maximum;
        public int fuzz;
        public int flat;
        public int resolution;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("code", "value", "minimum", "maximum", "fuzz", "flat", "resolution");
        }
    }
}
