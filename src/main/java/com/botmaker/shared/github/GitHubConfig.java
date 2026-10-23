package com.botmaker.shared.github;

import java.util.List;

/**
 * Static configuration for sharing bots and plugins through GitHub.
 *
 * <p>{@link #OAUTH_CLIENT_ID} is maintainer-owned: a GitHub OAuth App with device flow enabled, whose (public)
 * client id is pasted here. While blank, publishing degrades gracefully ("publishing not configured"). Browsing
 * and installing public bots needs no GitHub account; only publishing does.
 *
 * <p>Every published bot and plugin is listed in one repository, the catalog ({@link #CATALOG_REPO}, 2026-10-23):
 * the gallery repository and the plugin registry it replaced are gone, and so are their addresses here.
 *
 * <p><b>It lives in {@code botmaker-shared} since 2026-09-05</b>, with the rest of
 * {@code com.botmaker.shared.github}, because Studio is not the only operator of these repositories:
 * {@code botmaker-dashboard} reads the same catalog and the same pull requests. A second copy of the owner/repo
 * names is a second thing to edit when one of them moves — and the device flow beside it is emphatically not code
 * that may exist twice. Nothing here names a Studio type; the javadoc below mentions Studio's readers as prose
 * rather than as links, on purpose.
 */
public final class GitHubConfig {

    private GitHubConfig() {}

    public static final String OAUTH_CLIENT_ID = "Ov23lizugeHUiWl7WZhQ";

    /**
     * The person who maintains the project — a GitHub <b>login</b>, never a repository owner.
     *
     * <p>Separate from the owner constants since 2026-09-18, when every repository moved into the
     * {@code BotMakerDev} organization: an owner is now an organization, and a signed-in user's login can
     * never equal it. The readers that compare a login against it are asking *are you the maintainer*, and
     * comparing that to an organization name silently answers no for everybody, forever.
     *
     * <p>It is not a permission. What a login may actually do is read from the API
     * ({@code permissions.push}); this only decides what is worth offering.
     */
    public static final String MAINTAINER = "LiQiyeDev";

    /** The Studio's own repo, whose GitHub Releases host the app installers (used by the in-app updater). */
    public static final String STUDIO_OWNER = "BotMakerDev";
    public static final String STUDIO_REPO = "botmaker-studio";

    /**
     * The {@code botmaker} command-line tool's repo, whose latest release tag is the version Studio's
     * {@code CliUpdateService} compares the installed CLI against.
     *
     * <p>A repo name rather than a Maven coordinate on purpose: Studio does not depend on {@code botmaker-cli}
     * and must not — they are two hosts of one platform, and what they share lives in {@code botmaker-studio-api}
     * and {@code botmaker-plugin-host}. Reading the tag here is what lets a Studio that has not been
     * re-released still offer a newer CLI.
     */
    public static final String CLI_OWNER = "BotMakerDev";
    public static final String CLI_REPO = "botmaker-cli";

    /** Umbrella repo that receives in-app bug reports (Help ▸ Report Issue…). */
    public static final String ISSUE_OWNER = "BotMakerDev";
    public static final String ISSUE_REPO = "botmaker";

    /**
     * The catalog (2026-10-23): one repository for every listed bot and plugin, the plugin registry renamed.
     * Its entries are checked by machine and merged automatically; its CI generates {@link #CATALOG_PATH}, the
     * only file a client reads, and a client never writes it.
     */
    public static final String CATALOG_OWNER = "BotMakerDev";
    public static final String CATALOG_REPO = "botmaker-catalog";
    public static final String CATALOG_BRANCH = "main";

    /**
     * Every listed plugin and bot with its rank and derived facts, {@code "schemaVersion": 3}. The path is a
     * promise to every Studio in the wild, which has this address compiled into it: it never moves.
     */
    public static final String CATALOG_PATH = "catalog.json";

    public static final String API_BASE = "https://api.github.com";
    public static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    public static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    /**
     * Full {@code repo} rather than {@code public_repo}: the VCS panel's Push button creates and pushes to a
     * <em>private</em> backup repo, which {@code public_repo} cannot do. Tokens issued before this change keep
     * their narrower scope, so the private-repo calls translate the resulting 403/404 into a "sign out and back
     * in" hint rather than a raw API error.
     */
    public static final String SCOPE = "repo";

    /** Raw (CDN) URL of {@code catalog.json} — no API rate limit, no auth. */
    public static List<String> catalogRawUrls() {
        return List.of("https://raw.githubusercontent.com/" + CATALOG_OWNER + "/" + CATALOG_REPO + "/"
                + CATALOG_BRANCH + "/" + CATALOG_PATH);
    }

    /**
     * Release-archive endpoint for a tag. Deliberately the API's {@code zipball} rather than a direct
     * {@code codeload.github.com} URL: the API form honours an {@code Authorization} header (so a private or
     * rate-limited repo still installs) and answers a 302 to a signed codeload URL that needs no auth of its
     * own — the client follows it.
     */
    public static String archiveUrl(String owner, String repo, String tag) {
        return API_BASE + "/repos/" + owner + "/" + repo + "/zipball/refs/tags/" + tag;
    }

    /** REST endpoint for creating an issue on the umbrella repo (used with an authenticated token). */
    public static String issuesApiUrl() {
        return API_BASE + "/repos/" + ISSUE_OWNER + "/" + ISSUE_REPO + "/issues";
    }

    /** Browser URL of the umbrella repo's "New Issue" page (no auth handled by the app; the browser session is). */
    public static String newIssueBrowserUrl() {
        return "https://github.com/" + ISSUE_OWNER + "/" + ISSUE_REPO + "/issues/new";
    }

    public static boolean isPublishingConfigured() {
        return !OAUTH_CLIENT_ID.isBlank();
    }
}
