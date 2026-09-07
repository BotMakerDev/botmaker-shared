package com.botmaker.shared.config;

import java.util.List;
import java.util.function.Function;

/**
 * How one plugin's value types are spelled as text — {@code String} in, a typed value out, and back.
 *
 * <p>A plugin ships one of these and declares it in {@code META-INF/services}; {@link Settings} finds every
 * one on the classpath through {@link java.util.ServiceLoader} and asks whichever claims the {@link Class} at
 * the call site. That is the whole mechanism, and it is deliberately smaller than it could be.
 *
 * <h2>What this is not</h2>
 *
 * <p><b>It is not a second {@code ValueCodec}.</b> A {@code com.botmaker.plugin.api.value.ValueCodec} is the
 * <em>editor's</em> half: it also emits a Java literal, it is registered in a {@code ValueCatalog} under a
 * persisted id, and it lives in the plugin contract — which is <b>not on a running bot's classpath</b>, since
 * the SDK declares that dependency {@code provided} on purpose. This is the half a bot needs and can reach:
 * no literal, no id, no catalog, no host.
 *
 * <p>The two are meant to share an implementation rather than agree by discipline. The SDK is the worked
 * example: {@code WireText} is its grammar, one static parser per type, and {@code SdkValueTypes} wraps those
 * same parsers as {@code ValueCodec}s in one line each. A plugin writes its parsers once and hands them to
 * both halves; what it must never do is write them twice, which is how an editor and a bot come to disagree
 * about what {@code "3s500ms"} means.
 *
 * <p><b>There is no grammar in {@code botmaker-shared} itself, and that is the point.</b> This module owns
 * the mechanism; a vocabulary belongs to whoever introduced it. Shipping "the obvious JDK ones" here would
 * put a second `Duration` parser beside the SDK's and start exactly the drift the design exists to prevent.
 *
 * <h2>Totality</h2>
 *
 * <p>A {@link Reader} never throws and never returns {@code null}. Text that will not parse answers
 * {@link Reader#fallback()}, which is also what an undeclared name answers — so <i>enforce a default for
 * every type</i> is a property of the grammar rather than a rule somebody has to remember at each call site.
 */
public interface ValueGrammar {

    /**
     * One type this grammar reads.
     *
     * @param type     the Java type as a bot writes it — {@code Duration.class}, {@code Rect.class}. Use the
     *                 boxed class for a value that is also spelled as a primitive; {@link Settings} maps
     *                 {@code int.class} onto {@code Integer.class} before it looks anything up.
     * @param parse    stored text to a value. Must be total: no exception, no {@code null}. Text that makes
     *                 no sense is a hand-edited file or a value written by a newer plugin, and both must
     *                 read as the fallback rather than stop a bot starting.
     * @param store    a value back to stored text, for whoever writes the file. The round trip
     *                 {@code parse(store(v))} must equal {@code v} for every value the editor can produce.
     * @param fallback what an absent, mistyped or unparseable value reads as. Never {@code null}.
     */
    record Reader<T>(Class<T> type, Function<String, T> parse, Function<T, String> store, T fallback) {

        public Reader {
            if (type == null) throw new IllegalArgumentException("a reader must name its type");
            if (parse == null || store == null) throw new IllegalArgumentException(type + ": parse and store are required");
            if (fallback == null) throw new IllegalArgumentException(type + ": a reader must have a fallback");
            if (type.isPrimitive()) {
                throw new IllegalArgumentException(type + ": name the boxed type — Settings maps primitives onto it");
            }
        }

        /** {@code stored} as a value, or {@link #fallback()} when it cannot be read. Never throws. */
        public T read(String stored) {
            if (stored == null) return fallback;
            try {
                T value = parse.apply(stored);
                return value == null ? fallback : value;
            } catch (RuntimeException e) {
                return fallback;
            }
        }

        /** {@code value} as stored text, or the fallback's text when it cannot be written. Never throws. */
        public String write(T value) {
            try {
                String text = store.apply(value == null ? fallback : value);
                return text == null ? "" : text;
            } catch (RuntimeException e) {
                return "";
            }
        }
    }

    /**
     * Every type this grammar reads.
     *
     * <p>Asked once, when the classpath is first scanned, so it may be built eagerly. Two grammars claiming
     * one type is a packaging mistake — two plugins fighting over what {@code Duration} means — and
     * {@link Settings} refuses it by name rather than letting classpath order decide.
     */
    List<Reader<?>> readers();
}
