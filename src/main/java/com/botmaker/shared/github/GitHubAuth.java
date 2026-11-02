package com.botmaker.shared.github;

import com.botmaker.shared.oauth.Credentials;
import com.botmaker.shared.oauth.DeviceCode;
import com.botmaker.shared.oauth.DeviceFlow;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * "Sign in with GitHub" via the OAuth <b>device flow</b> ({@link DeviceFlow}) — the user authorizes in their
 * browser and never pastes a personal access token.
 *
 * <p>The obtained token is persisted in {@link Credentials} under the BotMaker cache dir — never inside
 * {@code ~/BotMakerProjects/}, which may itself be published.
 *
 * <p>Requires {@link GitHubConfig#OAUTH_CLIENT_ID} to be set; otherwise {@link #isConfigured()} is false and
 * the publish UI degrades gracefully.
 */
public final class GitHubAuth {

    private static final String TOKEN_KEY = "github_token";
    /**
     * The signed-in login, cached beside the token. Persisted because it answers an <em>offline</em> question
     * asked before any window exists: {@code ProjectMode} needs to know whether an installed bot is one of
     * <em>yours</em> — and "ask GitHub" is a network round-trip on the project-open path, which would make
     * opening your own bot behave differently on a train.
     */
    private static final String LOGIN_KEY = "github_login";

    private static final DeviceFlow FLOW = new DeviceFlow("GitHub",
            GitHubConfig.DEVICE_CODE_URL, GitHubConfig.ACCESS_TOKEN_URL,
            Map.of("client_id", GitHubConfig.OAUTH_CLIENT_ID, "scope", GitHubConfig.SCOPE),
            Map.of("client_id", GitHubConfig.OAUTH_CLIENT_ID),
            900);

    private volatile String token;
    private volatile String login;

    public GitHubAuth() {
        this.token = emptyToNull(Credentials.read(TOKEN_KEY));
        this.login = emptyToNull(rememberedLogin());
    }

    public boolean isConfigured() {
        return GitHubConfig.isPublishingConfigured();
    }

    public boolean isAuthenticated() {
        return token != null && !token.isBlank();
    }

    public String token() {
        return token;
    }

    /**
     * The signed-in user's GitHub login, resolved lazily from {@code /user} and cached for the session.
     * Resolves to {@code ""} when not signed in or unreachable. Runs the call off the FX thread.
     */
    public CompletableFuture<String> login(GitHubClient client) {
        if (!isAuthenticated()) return CompletableFuture.completedFuture("");
        String cached = login;
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return client.get(GitHubConfig.API_BASE + "/user", token).thenApply(node -> {
            String l = node == null ? "" : node.path("login").asText("");
            if (!l.isBlank()) {
                login = l;
                Credentials.store(LOGIN_KEY, l);
            }
            return l;
        });
    }

    /**
     * The last resolved login, read straight from the credentials file — no instance, no network, no FX
     * thread. {@code ""} when nobody has signed in on this machine yet.
     *
     * <p>It is a <em>recollection</em>, not an authorization: the only thing it decides is whether a bot you
     * installed opens for editing or for running. Nothing is unlocked by it that a sign-in wouldn't unlock
     * anyway, and {@link #signOut()} forgets it.
     */
    public static String rememberedLogin() {
        return Credentials.read(LOGIN_KEY);
    }

    public void signOut() {
        token = null;
        login = null;
        Credentials.remove(LOGIN_KEY);
        Credentials.remove(TOKEN_KEY);
    }

    /** Step 1: request a device + user code from GitHub. */
    public CompletableFuture<DeviceCode> requestDeviceCode() {
        return FLOW.requestCode();
    }

    /**
     * Step 2: poll GitHub until the user authorizes (or the code expires / is denied). On success the token
     * is stored and returned. Runs on a background thread — do not call on the FX thread.
     */
    public CompletableFuture<String> pollForToken(DeviceCode code) {
        return pollForToken(code, () -> false);
    }

    /** {@link #pollForToken(DeviceCode)}, stopping when {@code cancelled} says so ({@link DeviceFlow#pollForToken}). */
    public CompletableFuture<String> pollForToken(DeviceCode code, BooleanSupplier cancelled) {
        return FLOW.pollForToken(code, cancelled).thenApply(t -> {
            Credentials.store(TOKEN_KEY, t);
            this.token = t;
            this.login = null; // re-resolve for the (possibly new) account
            return t;
        });
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
