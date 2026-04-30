package io.vidocq.champollion.jsonp.internal;

/**
 * Tokens élémentaires reconnus par le scanner JSON RFC 8259.
 *
 * <p>Hiérarchie sealed pour exhaustive matching dans les niveaux supérieurs.</p>
 */
public sealed interface JsonToken {

    /** {@code {} */
    enum StartObject implements JsonToken { INSTANCE }

    /** {@code }} */
    enum EndObject implements JsonToken { INSTANCE }

    /** {@code [} */
    enum StartArray implements JsonToken { INSTANCE }

    /** {@code ]} */
    enum EndArray implements JsonToken { INSTANCE }

    /** {@code :} */
    enum NameSeparator implements JsonToken { INSTANCE }

    /** {@code ,} */
    enum ValueSeparator implements JsonToken { INSTANCE }

    /** {@code true} */
    enum True implements JsonToken { INSTANCE }

    /** {@code false} */
    enum False implements JsonToken { INSTANCE }

    /** {@code null} */
    enum Null implements JsonToken { INSTANCE }

    /** Fin de flux. */
    enum Eof implements JsonToken { INSTANCE }

    /** Chaîne JSON décodée (sans quotes, escapes appliqués). RFC 8259 §7. */
    record StringToken(String value) implements JsonToken {}

    /** Nombre JSON sous forme textuelle (parsing différé en {@code BigDecimal} si demandé). RFC 8259 §6. */
    record NumberToken(String literal) implements JsonToken {}
}
