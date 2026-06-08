/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
 * <p>Main rules applied when writing (strict canonical):</p>
 * <ul>
 *   <li>{@code int32 / uint32 / sint32 / fixed32 / sfixed32} → JSON number.</li>
 *   <li>{@code int64 / uint64 / sint64 / fixed64 / sfixed64} → <b>JSON string</b>
 *       (preserves precision &gt; 2^53 — required by the canonical spec).</li>
 *   <li>{@code float / double} → JSON number, sauf NaN/Infinity → JSON string.</li>
 *   <li>{@code bool} → JSON boolean.</li>
 *   <li>{@code string} → JSON string.</li>
 *   <li>{@code bytes} → JSON string base64 (RFC 4648 §4 standard).</li>
 *   <li>{@code enum} → JSON string (constant name, lenient read also accepts a number).</li>
 *   <li>{@code message} → recursive JSON object.</li>
 *   <li>{@code repeated} → JSON array. Empty = [], omitted if proto3 default.</li>
 *   <li>Names in {@code lowerCamelCase} (cf. {@link Descriptors#toJsonName(String)}).</li>
 * </ul>
 *
 * <p>Wired to {@code champollion-jsonp}: uses {@code Json.createGenerator}
 * and {@code Json.createParser}.</p>
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
