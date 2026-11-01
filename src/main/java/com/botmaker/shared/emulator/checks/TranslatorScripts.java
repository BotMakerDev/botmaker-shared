package com.botmaker.shared.emulator.checks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The root scripts that swap a Waydroid's ARM translator and put the old one back, each run whole by one
 * {@link RootScript}. A script is POSIX {@code sh} built from BotMaker's own constants and paths, never from
 * text a user or a game typed; every path is single-quoted.
 *
 * <p>Each prints {@code STEP <n>/<m> <words>} before each step, {@code m} the same for every run of it, so the
 * caller shows progress. Every {@code waydroid} call reads {@code /dev/null}: the script itself arrives on the
 * shell's stdin, and a command reading it would swallow the rest. The paths it touches default to Waydroid's and are read from {@code CFG},
 * {@code OVERLAY}, {@code BACKUPS} and {@code WAYDROID} when set, so a test runs a script as the user on
 * temporary files; {@code pkexec} clears the environment, so the real run always gets the defaults.
 *
 * <p>A backup folder holds {@code manifest} ({@code saved <path>} for each overlay path it copied before the
 * swap, {@code added <path>} for each the swap put where nothing was, {@code section added} when it created
 * {@code [properties]}), {@code files/} (the saved paths) and {@code props.undo} (each property the swap
 * touched: {@code key=old value}, or {@code key} alone when it was absent).
 */
public final class TranslatorScripts {

    /** Where Undo finds the backups; one folder per fix, named by its stamp. */
    public static final String BACKUPS = "/var/lib/waydroid/botmaker-backup";

    /** The install script's step count. */
    static final int INSTALL_STEPS = 6;
    /** The undo script's step count. */
    static final int UNDO_STEPS = 5;

    private TranslatorScripts() {}

    /**
     * Replaces {@code current} with {@code target}: files copied from {@code prebuilts} (the archive's unpacked
     * {@code prebuilts}, {@link TranslatorPins#unpacked}) into the overlay, properties set in {@code waydroid.cfg},
     * the old ones saved first under {@code BACKUPS/<stamp>}, then {@code waydroid upgrade --offline}.
     */
    public static String install(Translator target, Translator current, Path prebuilts, String stamp) {
        Set<String> touched = new LinkedHashSet<>(TranslatorPins.files(current));
        touched.addAll(TranslatorPins.files(target));
        Map<String, String> targetProps = TranslatorPins.props(target);
        Set<String> keys = new LinkedHashSet<>(TranslatorPins.props(current).keySet());
        keys.addAll(targetProps.keySet());

        StringBuilder sh = header();
        sh.append("B=\"$BACKUPS\"/").append(quote(stamp)).append('\n')
                .append("P=").append(quote(prebuilts.toString())).append('\n')
                .append("[ -d \"$P\" ] || { echo 'The translator files are missing.' >&2; exit 2; }\n")
                .append("[ -f \"$CFG\" ] || { echo 'waydroid.cfg is missing.' >&2; exit 2; }\n");

        step(sh, 1, INSTALL_STEPS, "Stopping the Waydroid session");
        sh.append("\"$WAYDROID\" session stop </dev/null >/dev/null 2>&1 || true\n");

        step(sh, 2, INSTALL_STEPS, "Saving the current translator");
        sh.append("mkdir -p \"$B/files\" \"$OVERLAY\"\n")
                .append(": > \"$B/manifest\"\n")
                .append("grep -q '^[[:space:]]*\\[properties\\][[:space:]]*$' \"$CFG\" || echo 'section added' >> \"$B/manifest\"\n")
                .append("cd \"$OVERLAY\"\n")
                .append("for f in ").append(patterns(touched)).append("; do\n")
                .append("  [ -e \"$f\" ] || [ -L \"$f\" ] || continue\n")
                .append("  grep -qx \"saved $f\" \"$B/manifest\" && continue\n")
                .append("  mkdir -p \"$B/files/$(dirname \"$f\")\"\n")
                .append("  cp -a \"$f\" \"$B/files/$f\"\n")
                .append("  echo \"saved $f\" >> \"$B/manifest\"\n")
                .append("done\n");
        sh.append("awk -v keys=").append(quote(String.join(" ", keys))).append(' ').append(quote(AWK_LINES + OLD_VALUES))
                .append(" \"$CFG\" > \"$B/props.undo\"\n");

        step(sh, 3, INSTALL_STEPS, "Removing the old translator");
        sh.append("for f in ").append(patterns(touched)).append("; do\n")
                .append("  [ -e \"$f\" ] || [ -L \"$f\" ] || continue\n")
                .append("  rm -rf \"$f\"\n")
                .append("done\n");

        step(sh, 4, INSTALL_STEPS, "Copying " + target.displayName());
        List<String> targetFiles = TranslatorPins.files(target);
        sh.append("cd \"$P\"\n")
                .append("for f in ").append(patterns(targetFiles)).append("; do\n")
                .append("  [ -e \"$f\" ] || continue\n")
                .append("  mkdir -p \"$OVERLAY/$(dirname \"$f\")\"\n")
                .append("  cp -a \"$f\" \"$OVERLAY/$f\"\n")
                .append("  owned \"$f\"\n")
                .append("  grep -qx \"saved $f\" \"$B/manifest\" || echo \"added $f\" >> \"$B/manifest\"\n")
                .append("done\n");
        if (target == Translator.LIBHOUDINI) {
            String rc = TranslatorPins.HOUDINI_RC_PATH;
            sh.append("mkdir -p \"$OVERLAY/$(dirname ").append(quote(rc)).append(")\"\n")
                    .append("cat > \"$OVERLAY\"/").append(quote(rc)).append(" <<'BOTMAKER_RC'\n")
                    .append(TranslatorPins.HOUDINI_RC)
                    .append("BOTMAKER_RC\n")
                    .append("owned ").append(quote(rc)).append('\n')
                    .append("grep -qx ").append(quote("saved " + rc)).append(" \"$B/manifest\" || grep -qx ")
                    .append(quote("added " + rc)).append(" \"$B/manifest\" || echo ").append(quote("added " + rc))
                    .append(" >> \"$B/manifest\"\n");
        }

        step(sh, 5, INSTALL_STEPS, "Setting the translator's properties");
        sh.append(": > \"$B/props.edit\"\n");
        for (String key : keys) {
            String line = targetProps.containsKey(key) ? key + "=" + targetProps.get(key) : key;
            sh.append("echo ").append(quote(line)).append(" >> \"$B/props.edit\"\n");
        }
        sh.append("props \"$B/props.edit\" 0\n");

        step(sh, 6, INSTALL_STEPS, "Rebuilding Waydroid's image");
        sh.append("\"$WAYDROID\" upgrade --offline </dev/null\n");
        return sh.toString();
    }

    /**
     * Puts back what the install script saved in {@code backup}: removes what it added, restores what it
     * replaced, sets each property back (removing the ones it added, and {@code [properties]} if it created it
     * and nothing else is left in it), then {@code waydroid upgrade --offline}. The folder is renamed
     * {@code <stamp>-undone} so the next Undo finds the backup before it.
     */
    public static String undo(Path backup) {
        StringBuilder sh = header();
        sh.append("B=").append(quote(backup.toString())).append('\n')
                .append("[ -f \"$B/manifest\" ] || { echo 'The backup has no manifest.' >&2; exit 2; }\n");

        step(sh, 1, UNDO_STEPS, "Stopping the Waydroid session");
        sh.append("\"$WAYDROID\" session stop </dev/null >/dev/null 2>&1 || true\n");

        step(sh, 2, UNDO_STEPS, "Removing the translator the fix added");
        sh.append("mkdir -p \"$OVERLAY\"\n")
                .append("cd \"$OVERLAY\"\n")
                .append("while read -r kind f; do\n")
                .append("  case \"$kind\" in added|saved) rm -rf \"./$f\" ;; esac\n")
                .append("done < \"$B/manifest\"\n");

        step(sh, 3, UNDO_STEPS, "Restoring the translator it replaced");
        sh.append("while read -r kind f; do\n")
                .append("  [ \"$kind\" = saved ] || continue\n")
                .append("  mkdir -p \"$(dirname \"./$f\")\"\n")
                .append("  cp -a \"$B/files/$f\" \"./$f\"\n")
                .append("done < \"$B/manifest\"\n");

        step(sh, 4, UNDO_STEPS, "Restoring the properties");
        sh.append("drop=0\n")
                .append("grep -qx 'section added' \"$B/manifest\" && drop=1\n")
                .append("props \"$B/props.undo\" \"$drop\"\n");

        step(sh, 5, UNDO_STEPS, "Rebuilding Waydroid's image");
        sh.append("\"$WAYDROID\" upgrade --offline </dev/null\n")
                .append("mv \"$B\" \"$B-undone\"\n");
        return sh.toString();
    }

    /**
     * awk over {@code waydroid.cfg}: prints, for each key in {@code keys} (space-separated), {@code key=value}
     * from {@code [properties]}, or the key alone when absent.
     */
    private static final String OLD_VALUES = """
            BEGIN { n = split(keys, k, " ") }
            { h = header($0); if (h != "") { inside = (h == "properties"); next } }
            inside { key = keyof($0); if (key != "") {
                     val = substr($0, index($0, "=") + 1); sub(/^[ \\t]+/, "", val); sub(/[ \\t\\r]+$/, "", val)
                     old[key] = val; has[key] = 1 } }
            END { for (j = 1; j <= n; j++) if (has[k[j]]) print k[j] "=" old[k[j]]; else print k[j] }
            """;

    /**
     * The awk functions both programs read the file with: a section header's name (empty when the line is not
     * one) and a property line's key. No bracket in a regular expression, which busybox's awk reads otherwise,
     * and a CR ending ignored.
     */
    private static final String AWK_LINES = """
            function trim(s) { sub(/\\r$/, "", s); sub(/^[ \\t]+/, "", s); sub(/[ \\t]+$/, "", s); return s }
            function header(line,   s) { s = trim(line)
                      if (substr(s, 1, 1) != "[" || substr(s, length(s), 1) != "]") return ""
                      return trim(substr(s, 2, length(s) - 2)) }
            function keyof(line,   i) { i = index(line, "="); if (!i) return ""; return trim(substr(line, 1, i - 1)) }
            """;

    /**
     * The edit of {@code [properties]}, as a shell function {@code props <edits> <drop>}: each line of
     * {@code edits} is {@code key=value} (set, in place when the key is there, else right after the section's
     * header, which is appended when absent) or {@code key} (removed). {@code drop} 1 removes the header when
     * the section is left with no line. Two passes over the file: the first finds what is there.
     */
    private static final String PROPS_AWK = """
            BEGIN { while ((getline l < edits) > 0) { if (l == "") continue; i = index(l, "=")
                      if (i) { key = substr(l, 1, i - 1); n++; order[n] = key; val[key] = substr(l, i + 1); set[key] = 1 }
                      else del[l] = 1 } }
            FNR == NR { h = header($0); if (h != "") { inside = (h == "properties"); if (inside) found = 1; next }
                        if (inside && trim($0) != "") { key = keyof($0); if (key in set) present[key] = 1
                          if (!(key in del)) left++ } next }
            FNR == 1 { inside = 0 }
            { h = header($0)
              if (h != "") { inside = (h == "properties")
                if (inside) { if (!(drop && left == 0 && n == 0)) print; for (j = 1; j <= n; j++)
                  if (!(order[j] in present)) print order[j] " = " val[order[j]]; next } }
              if (inside) { key = keyof($0); if (key in del) next
                if (key in set) { print key " = " val[key]; next } }
              print }
            END { if (!found && n > 0) { print "[properties]"; for (j = 1; j <= n; j++) print order[j] " = " val[order[j]] } }
            """;

    private static StringBuilder header() {
        return new StringBuilder()
                .append("set -e\n")
                .append("umask 022\n")
                .append("CFG=\"${CFG:-/var/lib/waydroid/waydroid.cfg}\"\n")
                .append("OVERLAY=\"${OVERLAY:-/var/lib/waydroid/overlay/system}\"\n")
                .append("BACKUPS=\"${BACKUPS:-").append(BACKUPS).append("}\"\n")
                .append("WAYDROID=\"${WAYDROID:-waydroid}\"\n")
                // Owner and modes as waydroid_script sets them: root, the shell group (2000) under bin.
                .append("owned() {\n")
                .append("  case \"$1\" in bin/*) g=2000; fm=755 ;; *) g=0; fm=644 ;; esac\n")
                .append("  if [ \"$(id -u)\" = 0 ]; then chown -R 0:\"$g\" \"$OVERLAY/$1\"; fi\n")
                .append("  find \"$OVERLAY/$1\" -type d -exec chmod 755 {} +\n")
                .append("  find \"$OVERLAY/$1\" -type f -exec chmod \"$fm\" {} +\n")
                .append("}\n")
                .append("props() {\n")
                .append("  awk -v edits=\"$1\" -v drop=\"$2\" ").append(quote(AWK_LINES + PROPS_AWK))
                .append(" \"$CFG\" \"$CFG\" > \"$CFG.botmaker\"\n")
                .append("  cat \"$CFG.botmaker\" > \"$CFG\"\n")
                .append("  rm -f \"$CFG.botmaker\"\n")
                .append("}\n");
    }

    private static void step(StringBuilder sh, int n, int of, String words) {
        sh.append("echo ").append(quote("STEP " + n + "/" + of + " " + words)).append('\n');
    }

    /** The paths as shell words: quoted, except a {@code *} left bare so the shell expands it. */
    private static String patterns(Iterable<String> paths) {
        List<String> words = new ArrayList<>();
        for (String path : paths) {
            int star = path.indexOf('*');
            words.add(star < 0 ? quote(path) : quote(path.substring(0, star)) + path.substring(star));
        }
        return String.join(" ", words);
    }

    /** {@code text} single-quoted for {@code sh}. */
    static String quote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }
}
