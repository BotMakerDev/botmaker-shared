package com.botmaker.shared.vm;

import com.botmaker.shared.platform.Os;
import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * A game VM's secrets: the guest account's password and the VNC password, kept in the VM's folder encrypted
 * with Windows' DPAPI, so only this Windows user on this computer can read them back. Nothing here logs or
 * prints one.
 *
 * @param guest the guest account's password
 * @param vnc   the password VNC clients give; VNC uses 8 characters
 */
public record VmCredentials(String guest, String vnc) {

    static final String FILE = "credentials.dpapi";
    /** Letters and digits only, so a password needs no escaping in XML, a {@code .vmx} or a command line. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public VmCredentials {
        if (guest.isEmpty() || guest.contains("\n") || vnc.isEmpty() || vnc.length() > 8 || vnc.contains("\n")) {
            throw new IllegalArgumentException("A guest password and a VNC password of up to 8 characters.");
        }
    }

    @Override
    public String toString() {
        return "VmCredentials[hidden]";
    }

    /** New random passwords: 20 characters for the guest, 8 for VNC. */
    public static VmCredentials random() {
        return new VmCredentials(randomText(20), randomText(8));
    }

    static String randomText(int length) {
        StringBuilder s = new StringBuilder(length);
        for (int i = 0; i < length; i++) s.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return s.toString();
    }

    /** Encrypts both into {@code vmFolder}. Windows only. */
    public void save(Path vmFolder) throws IOException {
        requireWindows();
        byte[] plain = (guest + "\n" + vnc).getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(vmFolder.resolve(FILE), Crypt32Util.cryptProtectData(plain));
        } catch (RuntimeException e) {
            throw new IOException("Windows could not encrypt the VM's passwords: " + e.getMessage(), e);
        }
    }

    /** What {@link #save} kept in {@code vmFolder}, or empty when nothing was. Windows only. */
    public static Optional<VmCredentials> load(Path vmFolder) throws IOException {
        requireWindows();
        Path file = vmFolder.resolve(FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        String plain;
        try {
            plain = new String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(file)), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IOException("The VM's passwords were saved by another Windows user or computer.", e);
        }
        int newline = plain.indexOf('\n');
        if (newline < 0) throw new IOException("The VM's password file is damaged.");
        return Optional.of(new VmCredentials(plain.substring(0, newline), plain.substring(newline + 1)));
    }

    private static void requireWindows() throws IOException {
        if (!Os.current().isWindows()) throw new IOException("A game VM's passwords are kept with Windows' DPAPI.");
    }
}
