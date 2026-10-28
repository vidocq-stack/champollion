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
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.annotation.JsonbSubtype;
import jakarta.json.bind.annotation.JsonbTransient;
import jakarta.json.bind.annotation.JsonbTypeInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Which getters make a JSON property on write (JSON-B 3.0 §3.7.1), and the {@code @JsonbTypeInfo} validations. */
@DisplayName("JSON-B — getter discovery and type-info validation")
class JsonbGetterDiscoveryTest {

    private static Jsonb jsonb;

    @BeforeAll
    static void open() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    /** A non-public getter hides its property; static and transient backing fields too. */
    public static class Hidden {
        public static String shared = "s";
        private transient String temp = "t";
        @JsonbTransient private String skipped = "k";
        public String open = "o";
        private String secret = "x";

        public static String getShared() { return shared; }
        public String getTemp() { return temp; }
        public String getSkipped() { return skipped; }
        public String getOpen() { return open; }
        String getSecret() { return secret; }
    }

    /** Both {@code isFlag} and {@code getFlag}: the {@code get} form is the property's getter. */
    public static class BothForms {
        public Boolean isFlag() { return Boolean.FALSE; }
        public Boolean getFlag() { return Boolean.TRUE; }
    }

    public static class Base {
        public Object getValue() { return "base"; }
    }

    /** A covariant override: the more specific return type is kept. */
    public static class Derived extends Base {
        @Override public String getValue() { return "derived"; }
    }

    /** No visible property at all. */
    public static class Empty {
        private String nothing = "n";
        String nothing() { return nothing; }
    }

    @JsonbTypeInfo(key = "@a", value = @JsonbSubtype(alias = "x", type = Both.class))
    public interface Left {}

    @JsonbTypeInfo(key = "@b", value = @JsonbSubtype(alias = "y", type = Both.class))
    public interface Right {}

    public static class Both implements Left, Right {
        public String name = "b";
    }

    public static class NotAPet {
        public String name = "n";
    }

    @JsonbTypeInfo(key = "@t", value = @JsonbSubtype(alias = "z", type = NotAPet.class))
    public interface Animal {}

    public static class Lion implements Animal {
        public String name = "l";
    }

    @Test
    void hidden_static_and_transient_properties_are_not_written() {
        assertEquals("{\"open\":\"o\"}", jsonb.toJson(new Hidden()));
    }

    @Test
    void the_get_form_wins_over_the_is_form() {
        assertEquals("{\"flag\":true}", jsonb.toJson(new BothForms()));
    }

    @Test
    void a_covariant_getter_is_written_once() {
        assertEquals("{\"value\":\"derived\"}", jsonb.toJson(new Derived()));
    }

    @Test
    void a_bean_without_visible_property_is_an_empty_object() {
        assertEquals("{}", jsonb.toJson(new Empty()));
        assertEquals("[{},null]", jsonb.toJson(java.util.Arrays.asList(new Empty(), null),
                new java.util.ArrayList<Empty>() {}.getClass().getGenericSuperclass()));
    }

    @Test
    void two_sibling_type_infos_are_rejected() {
        var both = new Both();
        assertThrows(JsonbException.class, () -> jsonb.toJson(both));
    }

    @Test
    void an_alias_that_is_not_a_subtype_is_rejected() {
        var lion = new Lion();
        assertThrows(JsonbException.class, () -> jsonb.toJson(lion, (Type) Animal.class));
        // The read side accepts it: BUG-20261028-06 (still open).
    }
}
