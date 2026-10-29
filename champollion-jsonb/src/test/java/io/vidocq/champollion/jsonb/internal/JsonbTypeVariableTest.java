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

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * JSON-B 3.0 §3.11: a property whose type is a type variable bound by a subclass ({@code Dto extends Base<Address>})
 * is read as the bound type, not as a generic JSON value.
 */
@DisplayName("JSON-B — type variables bound by a subclass")
class JsonbTypeVariableTest {

    private static Jsonb jsonb;

    @BeforeAll
    static void open() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    public static class Address {
        public String city;
    }

    /** Public fields typed by the class's own variables. */
    public static class Base<T> {
        public T value;
        public List<T> items;
        public Map<String, T> byName;
    }

    public static class Dto extends Base<Address> {
    }

    /** A variable bound two levels up, through an intermediate class that passes it on. */
    public static class Middle<U> extends Base<U> {
    }

    public static class Deep extends Middle<Address> {
    }

    /** A property read through a setter. */
    public static class Holder<T> {
        private T content;

        public T getContent() {
            return content;
        }

        public void setContent(T content) {
            this.content = content;
        }
    }

    public static class AddressHolder extends Holder<Address> {
    }

    private static final String JSON =
            "{\"value\":{\"city\":\"Paris\"},\"items\":[{\"city\":\"Lyon\"}],\"byName\":{\"home\":{\"city\":\"Nice\"}}}";

    @Test
    void a_variable_bound_by_the_subclass_is_read_as_its_bound_type() {
        Dto dto = jsonb.fromJson(JSON, Dto.class);

        assertEquals("Paris", assertInstanceOf(Address.class, dto.value).city);
        assertEquals("Lyon", assertInstanceOf(Address.class, dto.items.getFirst()).city);
        assertEquals("Nice", assertInstanceOf(Address.class, dto.byName.get("home")).city);
    }

    @Test
    void a_variable_passed_on_by_an_intermediate_class_is_read_as_its_final_bound() {
        Deep deep = jsonb.fromJson(JSON, Deep.class);

        assertEquals("Paris", assertInstanceOf(Address.class, deep.value).city);
        assertEquals("Lyon", assertInstanceOf(Address.class, deep.items.getFirst()).city);
    }

    @Test
    void a_setter_parameter_typed_by_a_variable_is_read_as_its_bound_type() {
        AddressHolder holder = jsonb.fromJson("{\"content\":{\"city\":\"Brest\"}}", AddressHolder.class);

        assertEquals("Brest", assertInstanceOf(Address.class, holder.getContent()).city);
    }
}
