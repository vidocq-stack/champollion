package io.vidocq.champollion.jsonb.spi;

import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

/**
 * Static binding for a Java type to and from JSON. One instance per target type.
 *
 * <p>Bindings are produced either:</p>
 * <ul>
 *   <li>by the {@code champollion-codegen-apt} APT for classes annotated
 *       with {@code @JsonbStatic} or scanned by {@code champollion-codegen-maven-plugin} ;</li>
 *   <li>manually, for specific cases (e.g. adapting a third-party type that
 *       cannot be annotated or recompiled).</li>
 * </ul>
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} on this
 * interface and used lookup-first by {@code ChampollionJsonb}, with an automatic
 * fallback to the introspective runtime when no static binding is available for
 * a given type.</p>
 *
 * <p><b>Contracts for {@link #write}:</b> on entry, the {@link JsonGenerator} is
 * positioned just before value emission. The implementation must emit exactly
 * <em>one</em> complete JSON value (object, array, scalar) and leave the generator
 * in a valid state.</p>
 *
 * <p><b>Contracts for {@link #read}:</b> on entry, the {@link JsonParser} is
 * positioned just before the leading event for the value. The implementation
 * must call {@link JsonParser#next()} itself to consume the value, and return
 * after consuming exactly one complete JSON value.</p>
 *
 * @param <T> le type Java cible
 */
public interface JsonbBinding<T> {

    /** The Java type for which this binding is canonical. */
    Class<T> type();

    /** Serializes {@code value} into {@code generator}. */
    void write(JsonGenerator generator, T value);

    /** Deserializes a value from {@code parser}. */
    T read(JsonParser parser);
}
