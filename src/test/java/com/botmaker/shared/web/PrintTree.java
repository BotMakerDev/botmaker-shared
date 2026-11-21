// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.botmaker.shared.tools.Downloads;

import java.util.List;

/**
 * Prints one page's tree, by hand only: one GET of the URL, no press, no typing (the read-only guard comes later).
 * Never run by a test. {@code mvn -q exec:java} is not set up; run it from the IDE or with the test classpath.
 */
public final class PrintTree {

    private PrintTree() {}

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "https://nunu.ai";
        if (!ChromiumInstall.install(Downloads.Progress.IGNORED)) {
            throw new IllegalStateException("no Chromium");
        }
        try (Chromium chromium = Chromium.start(false, List.of()); WebPage page = chromium.newPage()) {
            WebPage.Opened opened = page.open(url);
            System.out.println("opened " + url + " -> " + page.url() + " ok=" + opened.ok() + " settled="
                    + opened.settled() + " " + opened.error());
            WebTree tree = page.tree();
            System.out.print(tree.outline());
            System.out.println("-- " + tree.nodes().size() + " nodes, " + tree.interactive().size() + " inputs");
            page.console().forEach(m -> System.out.println("console " + m.level().id() + ": " + m.text()));
            page.failures().forEach(f -> System.out.println("failed " + f.status() + " " + f.error() + " " + f.url()));
        }
    }
}
