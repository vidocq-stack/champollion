package io.vidocq.champollion.jsonp.internal;

/**
 * Elementary tokens recognized by the RFC 8259 JSON scanner.
 *
 * <p>Sealed hierarchy for exhaustive matching in higher layers.</p>
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

    /** End of stream. */
    enum Eof implements JsonToken { INSTANCE }

    /** Decoded JSON string (without quotes, escapes applied). RFC 8259 §7. */
    record StringToken(String value) implements JsonToken {}

    /** JSON number as text (deferred parsing to {@code BigDecimal} if needed). RFC 8259 §6. */
    record NumberToken(String literal) implements JsonToken {}
}
