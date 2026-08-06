package com.botmaker.shared.capture;

import com.botmaker.shared.capture.linux.LinuxController;
import com.botmaker.shared.capture.windows.WindowsController;
import com.botmaker.shared.platform.Os;

public class NativeControllerFactory {

    private static NativeController instance;

    /**
     * Override the controller — used by tests to inject a fake so input/window operations can be
     * asserted without a live X11/Windows session. Pass {@code null} to reset to auto-detection.
     */
    public static void setForTesting(NativeController controller) {
        instance = controller;
    }

    public static NativeController get() {
        if (instance == null) {
            Os os = Os.current();
            instance = switch (os) {
                case WINDOWS -> new WindowsController();
                case LINUX -> new LinuxController();
                case MAC, UNKNOWN ->
                        throw new UnsupportedOperationException(os.displayName() + " is not yet supported.");
            };
        }
        return instance;
    }
}
