package com.botmaker.shared.emulator.tree;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.LoopbackForward;

import java.io.File;
import java.io.IOException;

/** The part of an {@link AdbDevice} a {@link TreeReader} uses; a seam so the readers are tested with no device. */
interface TreeDevice {

    /** {@link AdbDevice#shell}: stdout, throwing when the device does not answer. */
    String shell(String command);

    /** {@link AdbDevice#push}: whether the file landed. */
    boolean push(File file, String remotePath);

    /** {@link AdbDevice#forward}: a loopback port reaching {@code devicePort}. */
    Forward forward(int devicePort) throws IOException;

    /** A forward's loopback port; closing it removes the forward. */
    interface Forward extends AutoCloseable {
        int port();

        @Override
        void close();
    }

    static TreeDevice of(AdbDevice device) {
        return new TreeDevice() {
            @Override
            public String shell(String command) {
                return device.shell(command);
            }

            @Override
            public boolean push(File file, String remotePath) {
                return device.push(file, remotePath);
            }

            @Override
            public Forward forward(int devicePort) throws IOException {
                LoopbackForward forward = device.forward(devicePort);
                return new Forward() {
                    @Override
                    public int port() {
                        return forward.port();
                    }

                    @Override
                    public void close() {
                        forward.close();
                    }
                };
            }
        };
    }
}
