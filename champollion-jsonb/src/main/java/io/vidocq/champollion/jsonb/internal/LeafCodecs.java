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
package io.vidocq.champollion.jsonb.internal;

import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The runtime's own codecs of the types JSON-B maps natively, for {@link io.vidocq.champollion.jsonb.spi.JsonbLeaf}.
 * Public for the SPI package of this module only: the package is not exported.
 */
public final class LeafCodecs {

    private LeafCodecs() {
    }

    /** The writer of {@code type}, or {@code null} when JSON-B does not map it natively. */
    public static BiConsumer<JsonGenerator, Object> writer(Class<?> type) {
        BindingWriter w = type.isArray() ? RuntimeBindingRegistry.primitiveArrayWriter(type)
                : RuntimeBindingRegistry.builtinWriter(type);
        return w == null ? null : w::write;
    }

    /** Whether {@code type} takes a {@code @JsonbDateFormat}. */
    public static boolean isDateLike(Class<?> type) {
        return RuntimeBindingRegistry.isDateLikeType(type);
    }

    /** The writer of a date-like {@code type} under {@code @JsonbDateFormat(value = pattern, locale = locale)}. */
    public static BiConsumer<JsonGenerator, Object> dateWriter(Class<?> type, String pattern, String locale) {
        BindingWriter w = RuntimeBindingRegistry.formattedDateWriter(type, pattern, locale);
        return w::write;
    }

    /** The reader of a date-like {@code type} under {@code @JsonbDateFormat(value = pattern, locale = locale)}. */
    public static Function<JsonParser, Object> dateReader(Class<?> type, String pattern, String locale) {
        BindingReader r = RuntimeReadRegistry.formattedDateReader(type, pattern, locale);
        return r::read;
    }

    /** The writer of a number under {@code @JsonbNumberFormat(value = pattern, locale = locale)}. */
    public static BiConsumer<JsonGenerator, Object> numberWriter(String pattern, String locale) {
        BindingWriter w = RuntimeBindingRegistry.formattedNumberWriter(pattern, locale);
        return w::write;
    }

    /** The reader of {@code type} under {@code @JsonbNumberFormat(value = pattern, locale = locale)}, or {@code null}. */
    public static Function<JsonParser, Object> numberReader(Class<?> type, String pattern, String locale) {
        BindingReader r = RuntimeReadRegistry.numberReader(type, pattern, locale);
        return r == null ? null : r::read;
    }

    /** The reader of {@code type}, or {@code null}; it consumes the value's leading event itself. */
    public static Function<JsonParser, Object> reader(Class<?> type) {
        BindingReader r = type.isArray() ? RuntimeReadRegistry.primitiveArrayReaderOf(type)
                : RuntimeReadRegistry.builtinReader(type);
        return r == null ? null : r::read;
    }

    /**
     * The contexts of a {@code Jsonb} of the default configuration, created on first use: for a generated binding
     * called without the context of the {@code Jsonb} that uses it. Generated bindings serve the default
     * configuration only, so its contexts map values the same way.
     */
    private static final class DefaultContexts {
        static final ChampollionJsonb JSONB = (ChampollionJsonb) new ChampollionJsonbBuilder().build();
    }

    /** The serialization context of a default {@code Jsonb}. */
    public static jakarta.json.bind.serializer.SerializationContext defaultSerializationContext() {
        return DefaultContexts.JSONB.serializationContext();
    }

    /** The deserialization context of a default {@code Jsonb}. */
    public static jakarta.json.bind.serializer.DeserializationContext defaultDeserializationContext() {
        return DefaultContexts.JSONB.deserializationContext();
    }
}
