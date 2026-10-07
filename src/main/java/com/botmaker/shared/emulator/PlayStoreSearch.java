package com.botmaker.shared.emulator;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds an Android app by name on Google Play, from this computer: no emulator has to run, and no account or key
 * is needed — it reads Play's public search page, the one a browser gets, for each result's package, name and
 * icon. Only the install itself needs a running instance ({@link EmulatorInstall#fromStore}).
 *
 * <p>The page is HTML built for a browser, so the parsing leans on what a link to an app is
 * ({@code /store/apps/details?id=<package>}) rather than on the page's generated class names: the name is the
 * link's {@code aria-label} or the first text after it, the icon the first square Play image after it.
 */
public final class PlayStoreSearch {

    /** One app Google Play lists: its package, its name and its icon's address ({@code null} when none was found). */
    public record StoreApp(String packageName, String name, String iconUrl) {}

    /** An Android package name: two or more dot-separated Java identifiers. */
    public static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");

    private static final Pattern APP_LINK =
            Pattern.compile("<a\\b[^>]*href=\"/store/apps/details\\?id=(" + PACKAGE.pattern() + ")[^\"]*\"[^>]*>");
    private static final Pattern ARIA_LABEL = Pattern.compile("aria-label=\"([^\"]*)\"");
    private static final Pattern TEXT = Pattern.compile(">([^<>]+)<");
    private static final Pattern ICON = Pattern.compile(
            "<img\\b[^>]*src=\"(https://play-lh\\.googleusercontent\\.com/[^\"=]+)=s\\d+[^\"]*\"");
    private static final Pattern ID_PARAMETER = Pattern.compile("[?&]id=([^&#\\s]+)");
    /** How far past its link an app's name and icon are looked for, when the next app's link isn't nearer. */
    private static final int CARD_MAX = 8_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private PlayStoreSearch() {}

    /**
     * Google Play's results for {@code query}, in its order. A typed package Play lists comes first; a pasted Play
     * address is first whether or not the search lists it, and so is a typed package when Play can't be reached —
     * named by its package. Text that only looks like a package ("Dr.Stone") is searched like any other. Blocking;
     * throws with a sentence a UI can show when Play can't be reached.
     */
    public static List<StoreApp> search(String query) throws IOException {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) return List.of();
        Optional<String> typed = packageIn(q);
        List<StoreApp> found;
        try {
            found = parse(fetch(searchUrl(typed.orElse(q))));
        } catch (IOException e) {
            if (typed.isPresent()) return List.of(new StoreApp(typed.get(), typed.get(), null));
            throw e;
        }
        return typed.isEmpty() ? found : typedFirst(found, typed.get(), q.contains("play.google.com"));
    }

    /** {@code found} with {@code pkg}'s result first, or a bare one for it when {@code address} named it. Pure. */
    static List<StoreApp> typedFirst(List<StoreApp> found, String pkg, boolean address) {
        List<StoreApp> results = new ArrayList<>();
        found.stream().filter(app -> app.packageName().equals(pkg)).findFirst().ifPresentOrElse(results::add, () -> {
            if (address) results.add(new StoreApp(pkg, pkg, null));
        });
        found.stream().filter(app -> !app.packageName().equals(pkg)).forEach(results::add);
        return List.copyOf(results);
    }

    /** The package a pasted Play address ({@code …details?id=<package>}) or a typed package name names. */
    public static Optional<String> packageIn(String text) {
        if (text == null) return Optional.empty();
        String t = text.strip();
        Matcher id = ID_PARAMETER.matcher(t);
        if (t.contains("play.google.com") && id.find()) {
            String pkg = URLDecoder.decode(id.group(1), StandardCharsets.UTF_8);
            return PACKAGE.matcher(pkg).matches() ? Optional.of(pkg) : Optional.empty();
        }
        return PACKAGE.matcher(t).matches() ? Optional.of(t) : Optional.empty();
    }

    /** Every app the search page links to, once each, in page order. Pure, for the tests. */
    static List<StoreApp> parse(String html) {
        if (html == null) return List.of();
        List<StoreApp> apps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher link = APP_LINK.matcher(html);
        List<int[]> spans = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        while (link.find()) {
            spans.add(new int[]{link.start(), link.end()});
            packages.add(link.group(1));
        }
        for (int i = 0; i < spans.size(); i++) {
            String pkg = packages.get(i);
            if (!seen.add(pkg)) continue;
            int end = spans.get(i)[1];
            int next = Math.min(html.length(), end + CARD_MAX);
            for (int j = i + 1; j < spans.size(); j++) {
                if (!packages.get(j).equals(pkg)) {
                    next = Math.min(next, spans.get(j)[0]);
                    break;
                }
            }
            String tag = html.substring(spans.get(i)[0], end);
            String card = html.substring(end, next);
            String name = null;
            Matcher aria = ARIA_LABEL.matcher(tag);
            if (aria.find() && !aria.group(1).isBlank()) name = unescape(aria.group(1));
            if (name == null) {
                Matcher text = TEXT.matcher(card);
                while (text.find()) {
                    String candidate = unescape(text.group(1)).strip();
                    if (!candidate.isEmpty()) {
                        name = candidate;
                        break;
                    }
                }
            }
            Matcher icon = ICON.matcher(card);
            String iconUrl = icon.find() ? icon.group(1) + "=s128-rw" : null;
            apps.add(new StoreApp(pkg, name == null ? pkg : name, iconUrl));
        }
        return List.copyOf(apps);
    }

    private static String searchUrl(String query) {
        String language = Locale.getDefault().getLanguage();
        return "https://play.google.com/store/search?c=apps&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + (language.isEmpty() ? "" : "&hl=" + language);
    }

    /** One client for every search: each owns a selector thread. */
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private static String fetch(String url) throws IOException {
        HttpClient client = CLIENT;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                // The page a desktop browser gets; Play serves a script-only shell to an unknown agent.
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/128.0 Safari/537.36")
                .GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Google Play answered " + response.statusCode() + ".");
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("The search was stopped.", e);
        } catch (IOException e) {
            throw new IOException("Google Play couldn't be reached (" + e.getMessage() + "). Type the game's package "
                    + "instead, e.g. com.supercell.clashofclans.", e);
        }
    }

    private static String unescape(String text) {
        return text.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
