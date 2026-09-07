package com.botmaker.shared.config;

import com.botmaker.shared.Diag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * What a bot reads its own parameters through.
 *
 * <pre>{@code
 * Duration   wait  = Settings.load("wait", Duration.class);
 * int        health = Settings.load("minHealth", int.class);
 * List<Rect> zones = Settings.loadAll("zones", Rect.class);
 * boolean    on    = Settings.enabled("Mining");
 * }</pre>
 *
 * <p>The name is the one the user typed in the editor and {@code activities.json} holds; the {@link Class} is
 * what the bot wants back. Nothing else is needed, and in particular <b>no plugin is privileged</b>:
 * {@code Settings.load("channel", Channel.class)} works for a Discord plugin exactly as
 * {@code Duration.class} works for the SDK, because the type is a parameter rather than baked into a method
 * name and the parsers arrive by {@link ValueGrammar} rather than by import.
 *
 * <h2>Only load. Never write.</h2>
 *
 * <p>There is no {@code write}. A variable is <em>declared</em> in the editor, by the person who will run the
 * bot, and {@code activities.json} has one author — the SDK's {@code com.botmaker.sdk.authoring}. A bot that
 * could write back would be a second author of that file, and the two would race the moment the editor is
 * open while a bot runs. A bot's own scratch state is a file of the bot's own; it is not this.
 *
 * <h2>Every answer is total</h2>
 *
 * <table border="1">
 *   <caption>What each failure reads as</caption>
 *   <tr><th>situation</th><th>answer</th></tr>
 *   <tr><td>the name is not declared</td><td>the type's own fallback</td></tr>
 *   <tr><td>the stored text will not parse</td><td>the type's own fallback</td></tr>
 *   <tr><td>the file is missing or unreadable</td><td>the type's own fallback</td></tr>
 *   <tr><td>a misspelled name</td><td>the type's own fallback — see below</td></tr>
 * </table>
 *
 * <p><b>A misspelled name is still not a compile error</b>, and this class does not pretend otherwise:
 * {@code Settings.load("minHelath", int.class)} compiles and answers {@code 0}. What it does instead is say
 * so once, through {@link Diag}, naming the variables the file <em>does</em> declare — because the failure
 * that costs an afternoon is the silent one. Studio checks the same thing at edit time, where it can offer to
 * create the variable.
 *
 * <h2>The one thing that throws</h2>
 *
 * <p>A type <b>no grammar on the classpath claims</b> is an {@link IllegalArgumentException}, at the call. It
 * is the one failure here that is not about the file: the file can be empty, wrong or hand-mangled and a bot
 * still starts, but a bot asking for a type whose plugin is not on its classpath has been mis-packaged, and
 * there is no value of that type to hand back — a fallback has to come from somewhere and the only thing that
 * could supply one is the grammar that is missing. It is thrown rather than logged so it is found in a test
 * run instead of read as a value that is somehow always the default.
 */
public final class Settings {

    private static volatile Map<Class<?>, ValueGrammar.Reader<?>> readers;

    /** The names already complained about, so a lookup inside a loop says it once rather than every tick. */
    private static final Map<String, Boolean> WARNED = new HashMap<>();

    private Settings() {}

    // ---- reading ----------------------------------------------------------------------------------------

    /**
     * The value of the named variable, or the type's own fallback.
     *
     * <p>A list-shaped variable answers its first element here, which is what an author asking for one value
     * means; {@link #loadAll} is the shape that wants all of them.
     */
    public static <T> T load(String name, Class<T> type) {
        ValueGrammar.Reader<T> reader = readerFor(type);
        ProjectValues values = ProjectValues.current();
        if (!values.declares(name)) {
            warnOnce(name, values);
            return reader.fallback();
        }
        return reader.read(values.one(name));
    }

    /**
     * Every value of the named variable, in the order the file holds them.
     *
     * <p>Empty for a name nothing declares, and empty is also a legal <em>stored</em> state — a list the user
     * has not put anything in yet. The two are not told apart here on purpose: a bot iterating a list wants
     * the same code either way.
     */
    public static <T> List<T> loadAll(String name, Class<T> type) {
        ValueGrammar.Reader<T> reader = readerFor(type);
        ProjectValues values = ProjectValues.current();
        if (!values.declares(name)) {
            warnOnce(name, values);
            return List.of();
        }
        List<String> stored = values.many(name);
        List<T> out = new ArrayList<>(stored.size());
        for (String each : stored) out.add(reader.read(each));
        return List.copyOf(out);
    }

    /**
     * Whether the named activity is switched on, defaulting to {@code false}.
     *
     * <p>Here rather than on {@link ProjectValues} because this is the class a bot's own source names, and an
     * activity's enable flag is a parameter like any other from where the author is standing. It needs no
     * grammar: the answer is a {@code boolean} the file holds as one.
     */
    public static boolean enabled(String activity) {
        return ProjectValues.current().enabled(activity);
    }

    /**
     * Whether the file declares this name at all — the question a fallback cannot answer.
     *
     * <p>{@code load("wait", Duration.class)} answers {@code Duration.ZERO} both for a variable set to zero
     * and for a variable nobody declared. Where those two are genuinely different, ask this first.
     */
    public static boolean declares(String name) {
        return ProjectValues.current().declares(name);
    }

    // ---- the grammar index ------------------------------------------------------------------------------

    /**
     * Test seam: use exactly these grammars, or {@code null} to go back to scanning the classpath.
     *
     * <p>Public because a grammar is found by {@link ServiceLoader}, and a test that wants one has otherwise
     * to write a {@code META-INF/services} file into its own test resources and can never then test a
     * <em>second</em> arrangement in the same run.
     */
    public static synchronized void use(List<ValueGrammar> grammars) {
        readers = grammars == null ? null : index(grammars);
    }

    @SuppressWarnings("unchecked")
    private static <T> ValueGrammar.Reader<T> readerFor(Class<T> type) {
        Class<?> wanted = boxed(type);
        ValueGrammar.Reader<?> reader = index().get(wanted);
        if (reader == null) {
            throw new IllegalArgumentException(
                    "no value grammar on this bot's classpath reads " + wanted.getName()
                    + ". The plugin that introduced the type ships one, as a "
                    + ValueGrammar.class.getName() + " in META-INF/services; known types are "
                    + known());
        }
        return (ValueGrammar.Reader<T>) reader;
    }

    private static Map<Class<?>, ValueGrammar.Reader<?>> index() {
        Map<Class<?>, ValueGrammar.Reader<?>> known = readers;
        if (known == null) {
            synchronized (Settings.class) {
                known = readers;
                if (known == null) {
                    List<ValueGrammar> found = new ArrayList<>();
                    ServiceLoader.load(ValueGrammar.class).forEach(found::add);
                    known = index(found);
                    readers = known;
                }
            }
        }
        return known;
    }

    /**
     * Every reader in {@code grammars}, keyed by type.
     *
     * <p>Two grammars claiming one type is refused rather than resolved by classpath order — that is two
     * plugins disagreeing about what a {@code Duration} is, and whichever answer a bot got would depend on
     * jar ordering nobody controls. The message names both grammars, because the fix is to remove one of
     * them and the author has to know which two to choose between.
     */
    private static Map<Class<?>, ValueGrammar.Reader<?>> index(List<ValueGrammar> grammars) {
        Map<Class<?>, ValueGrammar.Reader<?>> out = new LinkedHashMap<>();
        Map<Class<?>, String> claimedBy = new LinkedHashMap<>();
        for (ValueGrammar grammar : grammars) {
            List<ValueGrammar.Reader<?>> readers = grammar.readers();
            if (readers == null) continue;
            for (ValueGrammar.Reader<?> reader : readers) {
                if (reader == null) continue;
                String owner = grammar.getClass().getName();
                String already = claimedBy.putIfAbsent(reader.type(), owner);
                if (already != null) {
                    throw new IllegalStateException(
                            "two value grammars read " + reader.type().getName() + ": " + already + " and "
                            + owner + ". One of them must go — a bot cannot hold two spellings of one type.");
                }
                out.put(reader.type(), reader);
            }
        }
        return Map.copyOf(out);
    }

    private static String known() {
        List<String> names = new ArrayList<>();
        for (Class<?> type : index().keySet()) names.add(type.getName());
        return names.isEmpty() ? "(none — no grammar was found at all)" : String.join(", ", names);
    }

    /**
     * {@code int.class} to {@code Integer.class}, and so on.
     *
     * <p>A bot writes {@code Settings.load("minHealth", int.class)} because the field it assigns to is an
     * {@code int}; a grammar is written against {@code Integer.class} because a {@code Function<String, int>}
     * cannot be spelled. Neither side should have to know about the other, so the mapping is here.
     */
    private static Class<?> boxed(Class<?> type) {
        if (type == null) throw new IllegalArgumentException("a type is required");
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        throw new IllegalArgumentException("void is not a value type");
    }

    private static synchronized void warnOnce(String name, ProjectValues values) {
        if (name == null || WARNED.putIfAbsent(name, Boolean.TRUE) != null) return;
        List<String> declared = values.variables();
        Diag.error("[config] no variable named \"" + name + "\" is declared; using the default. Declared: "
                   + (declared.isEmpty() ? "(none)" : String.join(", ", declared)));
    }
}
