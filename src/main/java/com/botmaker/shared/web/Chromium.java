// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.ServiceWorkerPolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>One running Chromium</b>, started for BotMaker alone through Playwright: its own build ({@link ChromiumInstall}),
 * a fresh profile Playwright deletes on {@link #close()}. Headless by default; {@code show} opens a window. No private
 * display session (spec {@code 2026-11-18-web-discovery-design.md} §1): input goes into the renderer, never the
 * user's cursor or focus. Every Playwright call is made on one {@link BrowserThread}.
 */
public final class Chromium implements AutoCloseable {

    /** The viewport every frame and recording shares. */
    public static final int WIDTH = 1440;
    public static final int HEIGHT = 900;

    private final BrowserThread thread;
    private final Playwright playwright;
    private final Browser browser;

    private Chromium(BrowserThread thread, Playwright playwright, Browser browser) {
        this.thread = thread;
        this.playwright = playwright;
        this.browser = browser;
    }

    /**
     * Starts Playwright's Chromium, which {@link ChromiumInstall#install} put in place.
     *
     * @param show     a window instead of headless
     * @param switches more switches, such as {@code --host-resolver-rules} in tests
     */
    public static Chromium start(boolean show, List<String> switches) throws IOException {
        BrowserThread thread = new BrowserThread();
        try {
            return thread.call(() -> {
                Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                        .setEnv(ChromiumInstall.runEnvironment()));
                try {
                    List<String> args = new ArrayList<>(List.of("--window-size=" + WIDTH + "," + HEIGHT,
                            // Nothing but the page talks to the network: no sync, no component or field-trial fetches.
                            "--disable-background-networking", "--disable-component-update", "--disable-sync",
                            "--disable-default-apps", "--mute-audio"));
                    args.addAll(switches == null ? List.of() : switches);
                    Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                            .setHeadless(!show).setArgs(args));
                    // The thread waits for work inside a context of its own, which holds no page.
                    thread.pumpOn(browser.newContext());
                    return new Chromium(thread, playwright, browser);
                } catch (RuntimeException e) {
                    playwright.close();
                    throw e;
                }
            });
        } catch (PlaywrightException e) {
            thread.close();
            throw new IOException("Chromium did not start: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            thread.close();
            throw e;
        }
    }

    /**
     * Opens a new blank tab in a context of its own (its own cookies), at {@link #WIDTH}×{@link #HEIGHT}. Service
     * workers are blocked: a Playwright route never sees what a worker fetches, so the guard could not judge it.
     */
    public WebPage newPage() {
        return thread.call(() -> {
            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(WIDTH, HEIGHT)
                    .setServiceWorkers(ServiceWorkerPolicy.BLOCK));
            Page page = context.newPage();
            CDPSession session = context.newCDPSession(page);
            return new WebPage(thread, page, new PageCdp(thread, session));
        });
    }

    /** How many pages are open in every context: the tabs and what they opened. */
    public int pages() {
        return thread.call(() -> browser.contexts().stream().mapToInt(c -> c.pages().size()).sum());
    }

    public boolean alive() {
        try {
            return thread.call(browser::isConnected);
        } catch (RuntimeException closed) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            thread.closeAfter(() -> {
                try {
                    browser.close();
                } catch (PlaywrightException gone) {
                    // Ended by itself; the driver is closed below either way.
                }
                playwright.close();
            });
        } catch (RuntimeException ignored) {
            // The browser thread is gone either way.
        }
    }
}
