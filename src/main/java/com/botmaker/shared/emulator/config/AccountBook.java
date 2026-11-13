package com.botmaker.shared.emulator.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every account this PC knows, for every emulator and every bot: {@code accounts.json} in the folder its owner gives
 * (spec {@code 2026-11-13-google-accounts-design.md}). The Accounts window lists it, and an instance's configuration
 * picks from it. An entry is an {@link AccountRecord}: kind, name and note, never a password, which only the OS
 * credential store holds.
 *
 * <p>The first {@link #known} read seeds it with the PC's own Google account, whatever wrote the book before; each read
 * adds what a configuration lists that the book lacks, so an account set in a configuration is always in the book, and
 * writes only when it added something.
 */
public final class AccountBook {

    /** The note the PC's own Google account is seeded with. */
    public static final String PC_ACCOUNT_NOTE = "This PC's Google account";

    /** Set once the PC's own account was offered to the book, whatever wrote the book first. */
    private static final String SEEDED = "pcSeeded";

    private final Path dir;

    public AccountBook(Path dir) {
        this.dir = dir;
    }

    public Path file() {
        return dir.resolve("accounts.json");
    }

    /**
     * The book, in the order accounts were added, with what {@code config} lists that it lacks added to it; on the first
     * read also {@code pcGoogle}, the PC's own Google account.
     */
    public List<AccountRecord> known(EmulatorConfig config, Optional<String> pcGoogle) throws IOException {
        List<AccountRecord> layered = layered(config);
        ObjectNode now = JsonFiles.read(file());
        Map<AccountRecord.Key, AccountRecord> held = read(now);
        if (seeded(now) && layered.stream().allMatch(account -> held.containsKey(account.key()))) {
            return List.copyOf(held.values());
        }
        return JsonFiles.change(file(), root -> {
            Map<AccountRecord.Key, AccountRecord> book = read(root);
            if (!seeded(root)) {
                pcGoogle.filter(email -> !email.isBlank())
                        .map(email -> new AccountRecord(AccountRecord.Kind.GOOGLE, email, PC_ACCOUNT_NOTE))
                        .ifPresent(pc -> book.putIfAbsent(pc.key(), pc));
                root.put(SEEDED, true);
            }
            for (AccountRecord account : layered) book.putIfAbsent(account.key(), account);
            write(root, book.values());
            return List.copyOf(book.values());
        });
    }

    /** Adds the account, or sets the note of the one of its key when {@code account}'s is not empty. */
    public List<AccountRecord> add(AccountRecord account) throws IOException {
        return JsonFiles.change(file(), root -> {
            Map<AccountRecord.Key, AccountRecord> book = read(root);
            AccountRecord was = book.get(account.key());
            book.put(account.key(), was == null || !account.note().isEmpty() ? account : was);
            write(root, book.values());
            return List.copyOf(book.values());
        });
    }

    /** Sets the account as given, its note too, empty or not: the user's own edit of it. */
    public List<AccountRecord> put(AccountRecord account) throws IOException {
        return JsonFiles.change(file(), root -> {
            Map<AccountRecord.Key, AccountRecord> book = read(root);
            book.put(account.key(), account);
            write(root, book.values());
            return List.copyOf(book.values());
        });
    }

    /** Takes the account out of the book; the caller offers it only for one no configuration lists. */
    public List<AccountRecord> remove(AccountRecord.Key key) throws IOException {
        return JsonFiles.change(file(), root -> {
            Map<AccountRecord.Key, AccountRecord> book = read(root);
            book.remove(key);
            write(root, book.values());
            return List.copyOf(book.values());
        });
    }

    /**
     * Writes {@code accounts} to {@code target} for another PC: {@code kind}, {@code name} and {@code note} per account,
     * and nothing else, so no password or instance ever leaves this PC this way.
     */
    public static void export(List<AccountRecord> accounts, Path target) throws IOException {
        ObjectNode root = JsonFiles.JSON.createObjectNode();
        write(root, accounts);
        Files.writeString(target, JsonFiles.JSON.writeValueAsString(root), StandardCharsets.UTF_8);
    }

    /**
     * Adds the accounts of a file {@link #export} wrote that the book lacks; an account it has keeps its note, unless
     * that note is empty. Answers the book after.
     */
    public List<AccountRecord> importFrom(Path source) throws IOException {
        Map<AccountRecord.Key, AccountRecord> given = read(JsonFiles.read(source));
        return JsonFiles.change(file(), root -> {
            Map<AccountRecord.Key, AccountRecord> book = read(root);
            given.forEach((key, account) -> {
                AccountRecord was = book.get(key);
                if (was == null || was.note().isEmpty() && !account.note().isEmpty()) book.put(key, account);
            });
            write(root, book.values());
            return List.copyOf(book.values());
        });
    }

    /** Which of {@code instances} each account is wanted on, by its configuration (the emulator's layer, then its own). */
    public static Map<AccountRecord.Key, List<InstanceKey>> onInstances(EmulatorConfig config,
                                                                       Collection<InstanceKey> instances) {
        Map<AccountRecord.Key, List<InstanceKey>> on = new LinkedHashMap<>();
        for (InstanceKey instance : instances) {
            for (Sourced<AccountRecord> account : config.wanted(instance).accounts()) {
                on.computeIfAbsent(account.value().key(), k -> new ArrayList<>()).add(instance);
            }
        }
        return on;
    }

    /** The accounts on no instance first, then by name, case aside: the free ones are the likely pick. */
    public static List<AccountRecord> sorted(List<AccountRecord> accounts,
                                             Map<AccountRecord.Key, List<InstanceKey>> onInstances) {
        return accounts.stream()
                .sorted(Comparator.<AccountRecord, Boolean>comparing(a -> onInstances.containsKey(a.key()))
                        .thenComparing(AccountRecord::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** The accounts some configuration lists, an emulator's or an instance's: the ones the book may not drop. */
    public static Set<AccountRecord.Key> held(EmulatorConfig config) {
        Set<AccountRecord.Key> held = new HashSet<>();
        for (AccountRecord account : layered(config)) held.add(account.key());
        return held;
    }

    /** Every account a layer lists, emulators' first, in their order. */
    private static List<AccountRecord> layered(EmulatorConfig config) {
        List<AccountRecord> out = new ArrayList<>();
        config.emulators().values().forEach(layer -> out.addAll(layer.accounts()));
        config.instances().values().forEach(layer -> out.addAll(layer.accounts()));
        return out;
    }

    private static boolean seeded(ObjectNode root) {
        return root.path(SEEDED).asBoolean(false);
    }

    /** The file's accounts by key; an entry of a kind this build does not know, or with no name, is skipped. */
    private static Map<AccountRecord.Key, AccountRecord> read(ObjectNode root) {
        Map<AccountRecord.Key, AccountRecord> out = new LinkedHashMap<>();
        for (JsonNode entry : root.path("accounts")) {
            AccountRecord.Kind kind = AccountRecord.Kind.fromId(entry.path("kind").asText());
            String name = entry.path("name").asText("");
            if (kind == AccountRecord.Kind.UNKNOWN || name.isBlank()) continue;
            AccountRecord account = new AccountRecord(kind, name, entry.path("note").asText(""));
            out.putIfAbsent(account.key(), account);
        }
        return out;
    }

    /** Writes the accounts over the file's list, keeping an entry of a kind this build does not know as it was. */
    private static void write(ObjectNode root, Collection<AccountRecord> accounts) {
        List<JsonNode> others = new ArrayList<>();
        for (JsonNode entry : root.path("accounts")) {
            if (AccountRecord.Kind.fromId(entry.path("kind").asText()) == AccountRecord.Kind.UNKNOWN) others.add(entry);
        }
        root.put("v", Math.max(1, root.path("v").asInt(1)));
        ArrayNode list = root.putArray("accounts");
        for (AccountRecord account : accounts) {
            ObjectNode entry = list.addObject();
            entry.put("kind", account.kind().id());
            entry.put("name", account.name());
            entry.put("note", account.note());
        }
        list.addAll(others);
    }
}
