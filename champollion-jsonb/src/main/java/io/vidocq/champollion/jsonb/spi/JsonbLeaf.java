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
package io.vidocq.champollion.jsonb.spi;

import io.vidocq.champollion.jsonb.internal.LeafCodecs;
import jakarta.json.bind.JsonbException;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The codec of a type JSON-B maps natively (numbers, text, {@code java.time}, {@code UUID}, {@code URI}, arrays of
 * primitives…), as the runtime maps it under the default configuration. A generated {@link JsonbBinding} writes the
 * structure of its type and hands each such value to one of these, so its output is the runtime's, character for
 * character, and it needs no reflection.
 *
 * <p>Create one per property type in a static field: the lookup is done once.</p>
 */
public final class JsonbLeaf {

    private final BiConsumer<JsonGenerator, Object> writer;
    private final Function<JsonParser, Object> reader;

    private JsonbLeaf(BiConsumer<JsonGenerator, Object> writer, Function<JsonParser, Object> reader) {
        this.writer = writer;
        this.reader = reader;
    }

    /**
     * The codec of {@code type}.
     *
     * @throws IllegalArgumentException when JSON-B does not map {@code type} natively
     */
    public static JsonbLeaf of(Class<?> type) {
        BiConsumer<JsonGenerator, Object> w = LeafCodecs.writer(type);
        Function<JsonParser, Object> r = LeafCodecs.reader(type);
        if (w == null || r == null) {
            throw new IllegalArgumentException(type.getName() + " is not a type JSON-B maps natively");
        }
        return new JsonbLeaf(w, r);
    }

    /**
     * The codec of a date-like {@code type} ({@code java.time}, {@code Date}, {@code Calendar}) carrying
     * {@code @JsonbDateFormat(value = pattern, locale = locale)}, on its property, its type or its package.
     *
     * @throws IllegalArgumentException when {@code type} takes no date format
     */
    public static JsonbLeaf date(Class<?> type, String pattern, String locale) {
        if (!LeafCodecs.isDateLike(type)) {
            throw new IllegalArgumentException(type.getName() + " takes no @JsonbDateFormat");
        }
        return new JsonbLeaf(LeafCodecs.dateWriter(type, pattern, locale), LeafCodecs.dateReader(type, pattern, locale));
    }

    /**
     * The codec of a number {@code type}, primitive or not, carrying
     * {@code @JsonbNumberFormat(value = pattern, locale = locale)}, on its property, its type or its package.
     *
     * @throws IllegalArgumentException when {@code type} is no number
     */
    public static JsonbLeaf number(Class<?> type, String pattern, String locale) {
        Function<JsonParser, Object> r = LeafCodecs.numberReader(type, pattern, locale);
        if (r == null) throw new IllegalArgumentException(type.getName() + " takes no @JsonbNumberFormat");
        return new JsonbLeaf(LeafCodecs.numberWriter(pattern, locale), r);
    }

    /** Writes {@code value}, not {@code null}, as the runtime does. */
    public void write(JsonGenerator generator, Object value) {
        writer.accept(generator, value);
    }

    /**
     * Reads one value; the parser stands before its leading event, as for {@link JsonbBinding#read}. A JSON
     * {@code null} gives {@code null}.
     */
    public Object read(JsonParser parser) {
        try {
            return reader.apply(parser);
        } catch (JsonbException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new JsonbException(e.getMessage(), e);
        }
    }
}
