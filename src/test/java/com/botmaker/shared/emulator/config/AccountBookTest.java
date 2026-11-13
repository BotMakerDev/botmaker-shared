package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.PlatformId;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AccountBookTest {

    private static final AccountRecord FARM = new AccountRecord(AccountRecord.Kind.GOOGLE, "farm@example.com", "farm");
    private static final AccountRecord ALT = new AccountRecord(AccountRecord.Kind.GOOGLE, "alt@example.com");
    private static final AccountRecord FB = new AccountRecord(AccountRecord.Kind.FACEBOOK, "a player");
    private static final InstanceKey LD0 = new InstanceKey(PlatformId.LDPLAYER, "LDPlayer");
    private static final InstanceKey LD1 = new InstanceKey(PlatformId.LDPLAYER, "LDPlayer-1");
    private static final InstanceKey MUMU = new InstanceKey(PlatformId.MUMU, "MuMu-0");

    /** LDPlayer's layer wants farm on every LDPlayer instance; MuMu-0 wants alt and the Facebook record. */
    private static EmulatorConfig config() {
        return EmulatorConfig.EMPTY
                .withEmulator(PlatformId.LDPLAYER, Layer.EMPTY.withAccount(FARM))
                .withInstance(MUMU, Layer.EMPTY.withAccount(ALT).withAccount(FB));
    }

    @Test
    void theFirstReadIsSeededFromTheLayersAndThePcsAccountAndLaterReadsAddWhatAConfigurationLists(@TempDir Path dir)
            throws IOException {
        AccountBook book = new AccountBook(dir);

        List<AccountRecord> first = book.known(config(), Optional.of("main@example.com"));
        assertEquals(List.of("main@example.com", "farm@example.com", "alt@example.com", "a player"),
                first.stream().map(AccountRecord::name).toList());
        assertEquals(AccountBook.PC_ACCOUNT_NOTE, first.getFirst().note());

        book.remove(first.getFirst().key());
        AccountRecord added = new AccountRecord(AccountRecord.Kind.MAIL, "mail@example.com");
        EmulatorConfig more = config().withInstance(LD1, Layer.EMPTY.withAccount(added));
        List<AccountRecord> later = book.known(more, Optional.of("main@example.com"));
        assertEquals(List.of("farm@example.com", "alt@example.com", "a player", "mail@example.com"),
                later.stream().map(AccountRecord::name).toList(), "the PC's account is seeded on the first read only");
    }

    @Test
    void addKeepsANoteUnlessGivenOneAndOneAccountPerKey(@TempDir Path dir) throws IOException {
        AccountBook book = new AccountBook(dir);
        book.add(FARM);
        book.add(new AccountRecord(AccountRecord.Kind.GOOGLE, "FARM@example.com"));
        assertEquals(List.of(FARM), book.known(EmulatorConfig.EMPTY, Optional.empty()));

        List<AccountRecord> renoted = book.add(new AccountRecord(AccountRecord.Kind.GOOGLE, "farm@example.com", "main"));
        assertEquals("main", renoted.getFirst().note());
        List<AccountRecord> cleared = book.put(new AccountRecord(AccountRecord.Kind.GOOGLE, "farm@example.com"));
        assertEquals("", cleared.getFirst().note(), "the user's own edit clears a note");
    }

    @Test
    void thePcsAccountIsSeededEvenWhenAnAddWroteTheBookFirstAndAReadThatAddsNothingWritesNothing(@TempDir Path dir)
            throws IOException {
        AccountBook book = new AccountBook(dir);
        book.add(FB);
        assertEquals(List.of("a player", "main@example.com"),
                book.known(EmulatorConfig.EMPTY, Optional.of("main@example.com")).stream().map(AccountRecord::name)
                        .toList());

        String written = Files.readString(book.file(), StandardCharsets.UTF_8);
        Files.writeString(book.file(), written + "\n", StandardCharsets.UTF_8);
        book.known(EmulatorConfig.EMPTY.withInstance(MUMU, Layer.EMPTY.withAccount(FB)), Optional.of("main@example.com"));
        assertEquals(written + "\n", Files.readString(book.file(), StandardCharsets.UTF_8), "nothing to add, no write");
    }

    @Test
    void onInstancesFollowsTheLayersAndTheSortPutsTheFreeAccountsFirst(@TempDir Path dir) throws IOException {
        EmulatorConfig config = config().withInstance(LD1, Layer.EMPTY.removingAccount(FARM.key()));
        Map<AccountRecord.Key, List<InstanceKey>> on = AccountBook.onInstances(config, List.of(LD0, LD1, MUMU));
        assertEquals(List.of(LD0), on.get(FARM.key()), "LD-1 removed the emulator's account");
        assertEquals(List.of(MUMU), on.get(ALT.key()));

        AccountRecord free = new AccountRecord(AccountRecord.Kind.GOOGLE, "zed@example.com");
        List<AccountRecord> sorted = AccountBook.sorted(List.of(FARM, ALT, free), on);
        assertEquals(List.of(free, ALT, FARM), sorted);
    }

    @Test
    void heldIsWhatAnyLayerListsAnEmulatorsOrAnInstancesAndNotARemoval() {
        EmulatorConfig config = config().withInstance(LD1, Layer.EMPTY.removingAccount(FARM.key()));
        assertEquals(Set.of(FARM.key(), ALT.key(), FB.key()), AccountBook.held(config));
        assertEquals(Set.of(), AccountBook.held(EmulatorConfig.EMPTY.withInstance(LD1,
                Layer.EMPTY.removingAccount(FARM.key()))));
    }

    @Test
    void anImportAddsWhatIsMissingAndKeepsANoteTheBookHas(@TempDir Path dir) throws IOException {
        AccountBook other = new AccountBook(dir.resolve("other"));
        other.add(new AccountRecord(AccountRecord.Kind.GOOGLE, "farm@example.com", "from the other PC"));
        other.add(new AccountRecord(AccountRecord.Kind.GOOGLE, "alt@example.com", "alt there"));
        other.add(FB);
        Path exported = dir.resolve("accounts-export.json");
        AccountBook.export(other.known(EmulatorConfig.EMPTY, Optional.empty()), exported);

        AccountBook book = new AccountBook(dir.resolve("mine"));
        book.add(FARM);
        book.add(ALT);
        List<AccountRecord> after = book.importFrom(exported);
        assertEquals(List.of(FARM, new AccountRecord(AccountRecord.Kind.GOOGLE, "alt@example.com", "alt there"), FB),
                after);
    }

    @Test
    void theExportHoldsKindNameAndNoteOnly(@TempDir Path dir) throws IOException {
        Path exported = dir.resolve("export.json");
        AccountBook.export(List.of(FARM, FB), exported);
        JsonNode root = JsonFiles.JSON.readTree(Files.readString(exported, StandardCharsets.UTF_8));
        Set<String> top = new HashSet<>();
        root.fieldNames().forEachRemaining(top::add);
        assertEquals(Set.of("v", "accounts"), top);
        for (JsonNode entry : root.path("accounts")) {
            Set<String> keys = new HashSet<>();
            entry.fieldNames().forEachRemaining(keys::add);
            assertEquals(Set.of("kind", "name", "note"), keys);
        }
        assertFalse(Files.readString(exported).toLowerCase().contains("password"));
    }

    @Test
    void anEntryOfAKindThisBuildDoesNotKnowIsKept(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("accounts.json"),
                "{\"v\":1,\"accounts\":[{\"kind\":\"steam\",\"name\":\"player\",\"note\":\"\"}]}");
        AccountBook book = new AccountBook(dir);
        assertEquals(List.of(FARM), book.add(FARM));
        assertEquals(2, JsonFiles.read(book.file()).path("accounts").size());
    }
}
