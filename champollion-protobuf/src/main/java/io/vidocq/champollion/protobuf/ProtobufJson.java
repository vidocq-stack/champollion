package io.vidocq.champollion.protobuf;

import io.vidocq.champollion.protobuf.internal.ProtobufJsonRuntime;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Objects;

/**
 * Proto3 / Editions 2023 JSON Canonical Mapping.
 *
 * <p>Spec : <a href="https://protobuf.dev/programming-guides/proto3/#json">Proto3 §JSON Mapping</a>.</p>
 *
 * <p>Règles principales appliquées en écriture (strict canonical) :</p>
 * <ul>
 *   <li>{@code int32 / uint32 / sint32 / fixed32 / sfixed32} → JSON number.</li>
 *   <li>{@code int64 / uint64 / sint64 / fixed64 / sfixed64} → <b>JSON string</b>
 *       (préserve la précision &gt; 2^53 — exigé par la spec canonical).</li>
 *   <li>{@code float / double} → JSON number, sauf NaN/Infinity → JSON string.</li>
 *   <li>{@code bool} → JSON boolean.</li>
 *   <li>{@code string} → JSON string.</li>
 *   <li>{@code bytes} → JSON string base64 (RFC 4648 §4 standard).</li>
 *   <li>{@code enum} → JSON string (nom du constant, lenient en lecture
 *       accepte aussi un number).</li>
 *   <li>{@code message} → JSON object récursif.</li>
 *   <li>{@code repeated} → JSON array. Vide = [], omis si proto3 default.</li>
 *   <li>Noms en {@code lowerCamelCase} (cf. {@link Descriptors#toJsonName(String)}).</li>
 * </ul>
 *
 * <p>Branchement sur {@code champollion-jsonp} : utilise {@code Json.createGenerator}
 * et {@code Json.createParser}.</p>
 */
public final class ProtobufJson {

    private ProtobufJson() {}

    public static String toJson(Object message) {
        Objects.requireNonNull(message, "message");
        return ProtobufJsonRuntime.toJsonString(message);
    }

    public static void toJson(Object message, Writer writer) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(writer, "writer");
        ProtobufJsonRuntime.toJson(message, writer);
    }

    public static void toJson(Object message, OutputStream out) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(out, "out");
        ProtobufJsonRuntime.toJson(message, out);
    }

    public static <T> T fromJson(Class<T> type, String json) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(json, "json");
        try {
            return ProtobufJsonRuntime.fromJsonString(type, json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
