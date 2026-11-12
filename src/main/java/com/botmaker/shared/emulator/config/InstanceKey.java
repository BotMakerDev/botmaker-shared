package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;

/**
 * Which instance a configuration layer belongs to: its product and the name a bot names it by
 * ({@link EmulatorInstance#reference()}). Not {@link EmulatorInstance#identity()}, which keys on the ADB port: BlueStacks'
 * instances share 5555, and a port moves with the product's numbering.
 */
public record InstanceKey(PlatformId platform, String reference) {

    public InstanceKey {
        if (platform == null || platform == PlatformId.UNKNOWN) {
            throw new IllegalArgumentException("an instance key has a known product");
        }
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("an instance key has a name");
        }
    }

    public static InstanceKey of(EmulatorInstance instance) {
        return new InstanceKey(instance.platformId(), instance.reference());
    }

    /** The key as the store writes it: {@code ldplayer/LDPlayer-1}. */
    public String text() {
        return platform.id() + "/" + reference;
    }

    /**
     * The key {@link #text()} wrote, or null for text that is not one, or names a product this build does not know or
     * spells it otherwise ({@code LDPlayer/…}): such a key is another writer's, kept as it is.
     */
    public static InstanceKey parse(String text) {
        int slash = text == null ? -1 : text.indexOf('/');
        if (slash <= 0 || slash == text.length() - 1 || text.substring(slash + 1).isBlank()) {
            return null;
        }
        String id = text.substring(0, slash);
        PlatformId platform = PlatformId.fromId(id);
        return platform == PlatformId.UNKNOWN || !platform.id().equals(id)
                ? null : new InstanceKey(platform, text.substring(slash + 1));
    }
}
