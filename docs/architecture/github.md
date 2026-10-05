# GitHub (`com.botmaker.shared.github`)

Four classes, moved here from `botmaker-studio`'s `sharing/` package on 2026-09-05 and otherwise unchanged:
`GitHubClient` (async REST over the JDK `HttpClient` + Jackson — no third-party GitHub SDK, none is
official), `GitHubAuth` (**OAuth device flow**, hand-rolled because no library implements it, with the token
persisted best-effort `0600` under `CacheDirs`), `GitHubConfig` (the owner/repo names of the gallery, the
plugin registry, Studio and the CLI, plus the raw-CDN URLs) and `SemVer`.

**Why it is here rather than copied.** Studio stopped being the only operator of those repositories:
`botmaker-dashboard` reads the same registry, the same gallery and the same pull requests. A device flow with
token storage and a polling loop is exactly the code that must not exist twice, and the owner/repo names are
one edit rather than two when a repository moves. It is also what this module is for — *the capabilities the
host and every plugin it loads may consume* — and none of the four names a JavaFX type, which is what made
the move imports-only.

**The counter-example is `botmaker-cli`, and it went the other way on purpose.** The CLI copies ~40 lines of
*file shapes* (`RegistryEntry`, `GalleryEntry`) rather than depending on shared, because nothing maintains a
record's field list after the first commit and the alternative there was depending on an **application**.
This is neither: live protocol code, and shared is a library both consumers already resolve.

**Two things stayed in Studio.** `GoogleAuth`/`GoogleConfig` — nothing else wants them, and the twin
device-flow implementation is not a duplicate of anything a second consumer needs. And every *reader* built
on this layer (`BotPublisher`, `GitHubGallery`, `PluginRegistry`, `BotInstaller`, `UpdateService`,
`CliUpdateService`): those know what a bot, a gallery entry and a plugin index are, which is the editor's
vocabulary rather than the platform's. `GitHubConfig`'s javadoc names them as prose, never as `{@link}` —
a link pointing out of this module is how the dependency would come back in the reader's mind if not in the
pom.

**`credentials.json` is shared with `GoogleAuth`, which still lives in Studio**, so every write merges the
whole map rather than overwriting it. Signing into one provider must not wipe the other's token — that is
why `store` reads before it writes, and it is now a rule spanning two repositories.
