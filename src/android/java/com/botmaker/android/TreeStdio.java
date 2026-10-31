package com.botmaker.android;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * Runs on the device beside openatx's {@code u2.jar}, started by {@code app_process} with both jars on its
 * {@code CLASSPATH}: it builds u2's own tree reader and answers on stdin and stdout, so BotMaker reads the tree
 * over the adb stream that started it and <b>no port is opened</b>. u2's own server listens on every interface
 * with no password, and anything that reaches it can tap, type and read the screen.
 *
 * <p>Protocol: after start, one line {@code BOTMAKER-TREE 1}. Then for each line read, one frame: a four-byte
 * big-endian length and that many bytes of gzipped UTF-8 hierarchy XML; length 0 when the read failed. When
 * stdin ends (BotMaker closed the stream, or its connection dropped), the process ends: nothing is left running.
 *
 * <p>Built by {@code src/android/build.sh} into {@code tree-stdio.jar}; u2's classes are reached by reflection,
 * so it compiles with no Android or u2 jar. Java 8 source, for {@code d8}.
 */
public final class TreeStdio {

    static final String READY = "BOTMAKER-TREE 1";

    private TreeStdio() {
    }

    public static void main(String[] args) throws Exception {
        // The protocol owns fd 1; u2 logs with System.out, which would corrupt it.
        DataOutputStream out = new DataOutputStream(new FileOutputStream(FileDescriptor.out));
        PrintStream discard = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
            }
        });
        System.setOut(discard);
        System.setErr(discard);

        // What u2's Main.runServer does before it builds the service.
        Class.forName("com.genymobile.scrcpy.Workarounds")
                .getMethod("apply", boolean.class, boolean.class).invoke(null, true, true);
        Object wrapper = Class.forName("uiautomator.InstrumentShellWrapper").getMethod("getInstance").invoke(null);
        wrapper.getClass().getMethod("getContext").invoke(wrapper);
        Object service = Class.forName("com.wetest.uia2.stub.AutomatorServiceImpl").getConstructor().newInstance();
        Method dump = service.getClass().getMethod("dumpWindowHierarchy", boolean.class, int.class);

        out.write((READY + "\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
        while (in.readLine() != null) {
            byte[] frame;
            try {
                Object xml = dump.invoke(service, false, 50);
                frame = xml == null ? new byte[0] : gzip(((String) xml).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                frame = new byte[0];
            }
            out.writeInt(frame.length);
            out.write(frame);
            out.flush();
        }
        System.exit(0);
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream(bytes.length / 8 + 64);
        GZIPOutputStream out = new GZIPOutputStream(zipped);
        out.write(bytes);
        out.close();
        return zipped.toByteArray();
    }
}
