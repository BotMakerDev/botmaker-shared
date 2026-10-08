package com.botmaker.shared.vm;

import com.botmaker.shared.vnc.GuestWindow;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guest's window list: the loop's script, its lines read back, and the cached read. */
class GuestWindowsTest {

    @Test
    void aListedLineIsAWindowAndABrokenOneIsSkipped() {
        String text = "131234\t4410\tnotepad\t-8\t12\t800\t600\t0\tUntitled - Notepad\n"
                + "not a window\n"
                + "70000\t880\tFirestone\t0\t0\t1280\t720\t1\tFirestone\twith a tab\n";
        assertEquals(List.of(
                new GuestWindow(131234, "Untitled - Notepad", "notepad", new Rectangle(-8, 12, 800, 600), false),
                new GuestWindow(70000, "Firestone\twith a tab", "Firestone", new Rectangle(0, 0, 1280, 720), true)),
                GuestWindows.parse(text));
        assertEquals(List.of(), GuestWindows.parse(null));
    }

    @Test
    void aReadIsKeptForASecondAndAFailedOneKeepsTheLastListForAWhile() throws Exception {
        String one = "1\t2\tp\t0\t0\t5\t5\t1\tOne\n";
        Deque<String> reads = new LinkedList<>(Arrays.asList(one, null, "", one, null, null));
        int[] asked = {0};
        List<Runnable> later = new ArrayList<>();
        // Refreshes run when the test says, as the background thread would run them.
        GuestWindows.Cached cached = new GuestWindows.Cached(() -> {
            asked[0]++;
            return reads.poll();
        }, later::add, Duration.ofMillis(1_500));
        assertEquals(1, cached.get().size(), "the first call reads");
        assertEquals(1, cached.get().size());
        assertEquals(1, asked[0], "read once within a second");
        assertEquals(List.of(), later);

        Thread.sleep(GuestWindows.FRESH.toMillis() + 50);
        assertEquals(1, cached.get().size(), "a stale list is answered at once");
        assertEquals(1, cached.get().size());
        assertEquals(1, later.size(), "one refresh at a time");
        later.removeFirst().run();
        assertEquals(1, cached.get().size(), "a failed read keeps what was listed");

        Thread.sleep(GuestWindows.FRESH.toMillis() + 50);
        cached.get();
        later.removeFirst().run();
        assertEquals(List.of(), cached.get(), "an empty list is a list");

        Thread.sleep(GuestWindows.FRESH.toMillis() + 50);
        cached.get();
        later.removeFirst().run();
        assertEquals(1, cached.get().size());
        for (int i = 0; i < 2; i++) {
            Thread.sleep(GuestWindows.FRESH.toMillis() + 50);
            cached.get();
            later.removeFirst().run();
        }
        assertEquals(List.of(), cached.get(), "reads failing for longer than that: a guest that lists nothing");
        assertEquals(6, asked[0]);
    }

    @Test
    void theLoopWritesTheListAsideAndRunsOnce() {
        String script = GuestWindows.script();
        assertTrue(script.contains("'Global\\BotMakerWindows'"), "one loop however often it is started");
        assertTrue(script.contains("$aside = 'C:\\BotMaker\\windows.tsv.new'"), script);
        // $null would reach .NET as "", which Replace refuses as a path (live): the list never changed again.
        assertTrue(script.contains("[System.IO.File]::Replace($aside, 'C:\\BotMaker\\windows.tsv', [NullString]::Value)"),
                script);
        assertTrue(script.contains("Replace('\\t', ' ')"), "a tab in a title can't split its line");
        assertTrue(script.contains("SetProcessDPIAware"));
        assertFalse(script.contains("%1$s"));
        assertEquals("start \"\" /min C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe -NoProfile"
                + " -WindowStyle Hidden -ExecutionPolicy Bypass -File C:\\BotMaker\\windows.ps1", GuestWindows.startCommand());
    }
}
