package com.botmaker.shared.config;

import com.botmaker.shared.Diag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A running bot's own {@code activities.json}, read off the classpath, as <b>untyped text</b>.
 *
 * <p>Every answer here is a {@code String} or a {@code List<String>} — exactly what the file holds. Nothing
 * in this class knows what a value <em>means</em>: turning {@code "3s500ms"} into a {@link java.time.Duration}
 * is a {@link ValueGrammar}'s job, and choosing which grammar is {@link Settings}'.
 *
 * <h2>Why the store is here and the schema is not</h2>
 *
 * <p><b>{@code activities.json} has one owner and it is the SDK</b> — {@code com.botmaker.sdk.authoring}
 * writes it, reads it back into records, and owns every rule about what an activity or a variable is. That
 * has not changed. What lives here is the <em>untyped key lookup</em>: given a name, what text is stored.
 * That question has no schema in it, and it has to be answerable by <b>every</b> plugin rather than by the
 * one that happens to own the file — which is the whole reason this class exists.
 *
 * <p>The placement is forced rather than chosen. A bot's classpath has the SDK and {@code botmaker-shared} on
 * it and <b>does not have {@code botmaker-studio-api}</b>, because the SDK declares the contract
 * {@code provided} on purpose. So the contract cannot hold this, and the SDK holding it is what made reading
 * a value a privilege of plugin #1. It is the same line the GitHub layer moved on: <i>shared owns the
 * request, not what the JSON means</i>.
 *
 * <h2>Nothing here throws</h2>
 *
 * <p>A missing file, a missing name and a value that will not parse are ordinary states with an answer.
 * <b>A bot does not fail to start because of its own configuration file</b> — the rule the generated
 * {@code Activities} class used to state in its javadoc, carried through the move from generated fields to a
 * runtime read, and carried again through this one.
 *
 * <h2>Read once</h2>
 *
 * <p>{@link #current()} parses on first use and holds the result for the life of the process, because a bot's
 * configuration cannot change while it runs — the editor writes the file and the bot is restarted. Tests and
 * the flow loader use {@link #of} instead, which parses whatever it is handed and caches nothing.
 */
public final class ProjectValues {

    /** Where a project's model lives on a bot's classpath — {@code src/main/resources/activities.json}. */
    public static final String RESOURCE = "/activities.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ProjectValues EMPTY = new ProjectValues(MAPPER.createObjectNode());

    private static ProjectValues current;

    private final JsonNode root;

    private ProjectValues(JsonNode root) {
        this.root = root;
    }

    // ---- loading ----------------------------------------------------------------------------------------

    /** This bot's own values, parsed once. Never {@code null}, and empty when there is nothing to read. */
    public static synchronized ProjectValues current() {
        if (current == null) current = load(RESOURCE);
        return current;
    }

    /**
     * Test seam: make {@code values} what {@link #current()} answers, or {@code null} to forget it and read
     * the classpath again.
     *
     * <p>Public because the things that read {@code current()} are spread across modules — a bot's own
     * {@code Settings.load}, an activity asking whether it is switched on, the SDK's flow loader — and each
     * of those wants to be tested against values written in the test rather than against a resource file per
     * case.
     */
    public static synchronized void use(ProjectValues values) {
        current = values;
    }

    /**
     * The values at {@code resource} on the classpath, or an empty set.
     *
     * <p>The two failures are told apart on purpose. A <b>missing</b> resource is silent: an empty project
     * has no model, and a bot that has never had an activity added is not misconfigured. A resource that
     * exists and will not parse says so once, because that is a real mistake somebody can act on — and it
     * still yields empty values rather than throwing, since a bot that refuses to start tells its user far
     * less than one that starts and reports empty configuration.
     */
    public static ProjectValues load(String resource) {
        try (InputStream in = ProjectValues.class.getResourceAsStream(resource)) {
            if (in == null) return EMPTY;
            return new ProjectValues(MAPPER.readTree(in));
        } catch (Exception e) {
            Diag.error("[config] " + resource + " could not be read (" + e.getMessage()
                    + "); running with no configuration");
            return EMPTY;
        }
    }

    /** The values in {@code json}, or an empty set — the seam a test and the flow loader read through. */
    public static ProjectValues of(String json) {
        if (json == null || json.isBlank()) return EMPTY;
        try {
            return new ProjectValues(MAPPER.readTree(json));
        } catch (Exception e) {
            Diag.error("[config] model could not be parsed (" + e.getMessage() + ")");
            return EMPTY;
        }
    }

    /** Values with nothing in them — every lookup below answers its own fallback. */
    public static ProjectValues empty() {
        return EMPTY;
    }

    // ---- variables --------------------------------------------------------------------------------------

    /**
     * The stored text of the named variable's first value, or {@code ""}.
     *
     * <p>Stored <em>text</em>, because that is what the file holds and what a {@link ValueGrammar} takes.
     * {@code Settings.load(name, T.class)} is this plus one parse.
     */
    public String one(String variable) {
        List<String> values = many(variable);
        return values.isEmpty() ? "" : values.get(0);
    }

    /** Every stored value of the named variable — one for a plain value, several for a list. */
    public List<String> many(String variable) {
        if (variable == null) return List.of();
        for (JsonNode candidate : root.path("variables")) {
            if (variable.equals(candidate.path("name").asText(null))) {
                return strings(candidate.path("value"));
            }
        }
        return List.of();
    }

    /** Whether the file declares a variable by this name at all — the question {@code ""} cannot answer. */
    public boolean declares(String variable) {
        if (variable == null) return false;
        for (JsonNode candidate : root.path("variables")) {
            if (variable.equals(candidate.path("name").asText(null))) return true;
        }
        return false;
    }

    /** Every variable's name, in the order the file lists them. */
    public List<String> variables() {
        return namesUnder("variables");
    }

    /**
     * The type id the file records for the named variable, or {@code ""} when it declares none.
     *
     * <p>An id, never a Java type: it is whatever the plugin that registered the type calls it
     * ({@code "WHOLE_NUMBER"}, {@code "discord.Channel"}), and this class does not resolve it. It is here
     * because {@link Settings} has to answer <i>was this name declared as the type you are asking for</i>
     * without loading any vocabulary at all.
     */
    public String typeId(String variable) {
        if (variable == null) return "";
        for (JsonNode candidate : root.path("variables")) {
            if (variable.equals(candidate.path("name").asText(null))) {
                return candidate.path("type").asText("");
            }
        }
        return "";
    }

    // ---- activities -------------------------------------------------------------------------------------

    /**
     * Whether the named activity is switched on, defaulting to {@code false}.
     *
     * <p>{@code false} rather than {@code true}: an activity nothing knows about should not run. The
     * generated {@code Activities} class defaulted the same way and for the same reason.
     */
    public boolean enabled(String activity) {
        return activityNamed(activity).path("enabled").asBoolean(false);
    }

    /** The outcomes the named activity declares, without the implicit one. Empty when it has none. */
    public List<String> outcomes(String activity) {
        return strings(activityNamed(activity).path("outcomes"));
    }

    /** Every activity's name, in the order the file lists them. */
    public List<String> activities() {
        return namesUnder("activities");
    }

    /** Whether the named activity goes home before running. */
    public boolean goHome(String activity) {
        return activityNamed(activity).path("goHome").asBoolean(false);
    }

    /** Whether the named activity checks for popups before running. */
    public boolean popupCheck(String activity) {
        return activityNamed(activity).path("popupCheck").asBoolean(false);
    }

    // ---- the rest of the file ---------------------------------------------------------------------------

    /**
     * A named top-level section of the file, as a tree — {@code "flow"} being the one that has a reader.
     *
     * <p>The seam for everything this class deliberately does not interpret. The flow's meaning is the SDK's:
     * what a node, an edge, a start and a step delay are is the authoring model's business, and reading it
     * through a second set of rules here is how the two would come to disagree.
     */
    public JsonNode section(String name) {
        return root.path(name);
    }

    /** Whether these values hold nothing at all. */
    public boolean isEmpty() {
        return activities().isEmpty() && variables().isEmpty();
    }

    // ---- internals --------------------------------------------------------------------------------------

    private JsonNode activityNamed(String activity) {
        if (activity == null) return MAPPER.missingNode();
        for (JsonNode candidate : root.path("activities")) {
            if (activity.equals(candidate.path("name").asText(null))) return candidate;
        }
        return MAPPER.missingNode();
    }

    private List<String> namesUnder(String section) {
        List<String> names = new ArrayList<>();
        for (JsonNode each : root.path(section)) {
            String name = each.path("name").asText("");
            if (!name.isEmpty()) names.add(name);
        }
        return List.copyOf(names);
    }

    private static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) return List.of();
        List<String> out = new ArrayList<>(array.size());
        for (JsonNode element : array) out.add(element.asText(""));
        return List.copyOf(out);
    }
}
