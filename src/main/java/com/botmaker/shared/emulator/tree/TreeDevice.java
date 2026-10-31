package com.botmaker.shared.emulator.tree;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.DeviceStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The part of an {@link AdbDevice} a {@link TreeReader} uses; a seam so the readers are tested with no device. */
interface TreeDevice {

    /** {@link AdbDevice#shell}: stdout, throwing when the device does not answer. */
    String shell(String command);

    /** {@link AdbDevice#push}: whether the file landed. */
    boolean push(File file, String remotePath);

    /** A process started on the device, its stdin and stdout as the stream; closing it ends the process's input. */
    Channel exec(String command) throws IOException;

    /** A running process's stdout ({@link #in}) and stdin ({@link #out}). */
    interface Channel extends AutoCloseable {
        InputStream in();

        OutputStream out();

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
            public Channel exec(String command) throws IOException {
                // exec: carries bytes as they are, where shell: may add a terminal's line endings.
                DeviceStream stream = device.openService("exec:" + command);
                return new Channel() {
                    @Override
                    public InputStream in() {
                        return stream.in();
                    }

                    @Override
                    public OutputStream out() {
                        return stream.out();
                    }

                    @Override
                    public void close() {
                        stream.close();
                    }
                };
            }
        };
    }
}
