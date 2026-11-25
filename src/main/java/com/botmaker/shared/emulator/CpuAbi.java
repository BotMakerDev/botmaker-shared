// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator;

import java.util.Arrays;
import java.util.Locale;

/**
 * The kind of processor an Android app's native code is built for, as Android names it ({@code ro.product.cpu.abilist}):
 * what the {@link ApkLibrary} files a download under, since one game is a different file for an {@code x86_64}
 * Waydroid and an {@code arm64-v8a} phone (spec {@code 2026-11-25-game-window-design.md}).
 */
public enum CpuAbi {
    /** A file with no native code, or with code for several kinds, or one kept before the library named kinds. */
    ANY("", "any CPU"),
    X86_64("x86_64", "x86 64-bit"),
    X86("x86", "x86 32-bit"),
    ARM64_V8A("arm64-v8a", "ARM 64-bit"),
    ARMEABI_V7A("armeabi-v7a", "ARM 32-bit"),
    /** A kind this build doesn't know ({@code riscv64}, {@code mips}); never written in a file name. */
    UNKNOWN("unknown", "Unknown CPU");

    private final String id;
    private final String displayName;

    CpuAbi(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** Android's own name, the one a library file name ends with; blank for {@link #ANY}. */
    public String id() {
        return id;
    }

    /** The name the Game window shows. */
    public String displayName() {
        return displayName;
    }

    /** Whether a file of this kind runs on a device of the kind {@code device}. */
    public boolean serves(CpuAbi device) {
        return this == ANY || this == device;
    }

    /** The kind Android names {@code id}; blank is {@link #ANY}, any other unknown name {@link #UNKNOWN}, never a throw. */
    public static CpuAbi fromId(String id) {
        if (id == null || id.isBlank()) return ANY;
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(abi -> abi != ANY && abi != UNKNOWN && abi.id.equals(wanted))
                .findFirst().orElse(UNKNOWN);
    }
}
