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
import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbSubtype;
import jakarta.json.bind.annotation.JsonbTransient;
import jakarta.json.bind.annotation.JsonbTypeInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Mapping errors the runtime reports as {@link JsonbException}, and the polymorphic read and write paths. */
@DisplayName("JSON-B — binding errors and polymorphism")
class JsonbBindingErrorsTest {

    private static final String DOG_ALIAS = "dog";

    private static Jsonb jsonb;

    @BeforeAll
    static void open() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    public record Simple(String name) {}

    public static class NoDefaultConstructor {
        public String name;
        public NoDefaultConstructor(String name) { this.name = name; }
    }

    public static class PrivateConstructor {
        public String name;
        private PrivateConstructor() {}
    }

    public static class ProtectedConstructor {
        public String name;
        protected ProtectedConstructor() {}
    }

    public static class DuplicateNames {
        @JsonbProperty("same") public String first;
        @JsonbProperty("same") public String second;
    }

    public static class TwoCreators {
        @JsonbCreator public TwoCreators(@JsonbProperty("a") String a) {}
        @JsonbCreator public static TwoCreators of(@JsonbProperty("b") String b) { return new TwoCreators(b); }
    }

    public static class WrongFactory {
        @JsonbCreator public static String of(@JsonbProperty("a") String a) { return a; }
    }

    public static class FactoryCreator {
        private final String value;
        private FactoryCreator(String value) { this.value = value; }
        @JsonbCreator public static FactoryCreator of(@JsonbProperty("value") String value) {
            return new FactoryCreator(value);
        }
        public String getValue() { return value; }
    }

    public static class TransientConflict {
        @JsonbTransient @JsonbProperty("x") public String value;
    }

    /** A bean with overloaded setters: the most specific parameter type wins. */
    public static class Overloaded {
        private Object value;
        public Overloaded() {}
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
        public void setValue(String value) { this.value = "string:" + value; }
    }

    /** A bean whose setter throws. */
    public static class ThrowingSetter {
        public ThrowingSetter() {}
        public String getName() { return "n"; }
        public void setName(String name) { throw new IllegalStateException("refused " + name); }
    }

    /** A bean with a hidden (non-public) setter and a transient one: both are skipped on read. */
    public static class HiddenSetters {
        private String a = "a";
        private String b = "b";
        private transient String c = "c";
        public HiddenSetters() {}
        public String getA() { return a; }
        void setA(String a) { this.a = a; }
        public String getB() { return b; }
        @JsonbTransient public void setB(String b) { this.b = b; }
        public String getC() { return c; }
        public void setC(String c) { this.c = c; }
    }

    @JsonbTypeInfo(key = "@kind", value = {
            @JsonbSubtype(alias = DOG_ALIAS, type = Dog.class),
            @JsonbSubtype(alias = "cat", type = Cat.class),
            @JsonbSubtype(alias = "fish", type = Fish.class)
    })
    public interface Pet {}

    public static class Dog implements Pet {
        private String name;
        public Dog() {}
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class Cat implements Pet {
        private final String name;
        public int lives;
        @JsonbCreator public Cat(@JsonbProperty("name") String name) { this.name = name; }
        public String getName() { return name; }
    }

    public record Fish(String name, @JsonbTransient String secret) implements Pet {}

    @JsonbTypeInfo(key = "name", value = @JsonbSubtype(alias = "x", type = Clash.class))
    public static class Clash {
        public String name;
    }

    @Test
    void a_record_rejects_a_non_object_and_reads_null() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("[1]", Simple.class));
        assertNull(jsonb.fromJson("null", Simple.class));
    }

    @Test
    void a_bean_needs_an_accessible_no_arg_constructor() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{}", NoDefaultConstructor.class));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{}", PrivateConstructor.class));
        assertEquals("p", jsonb.fromJson("{\"name\":\"p\"}", ProtectedConstructor.class).name);
    }

    @Test
    void a_bean_rejects_a_non_object_and_reads_null() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("\"x\"", ProtectedConstructor.class));
        assertNull(jsonb.fromJson("null", ProtectedConstructor.class));
    }

    @Test
    void duplicate_json_names_are_rejected_both_ways() {
        var bean = new DuplicateNames();
        assertThrows(JsonbException.class, () -> jsonb.toJson(bean));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{}", DuplicateNames.class));
    }

    @Test
    void creator_misconfigurations_are_rejected() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{}", TwoCreators.class));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{}", WrongFactory.class));
    }

    @Test
    void a_static_factory_creator() {
        assertEquals("v", jsonb.fromJson("{\"value\":\"v\"}", FactoryCreator.class).getValue());
    }

    @Test
    void transient_combined_with_another_annotation_is_rejected() {
        var bean = new TransientConflict();
        assertThrows(JsonbException.class, () -> jsonb.toJson(bean));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{}", TransientConflict.class));
    }

    @Test
    void the_most_specific_overloaded_setter_wins() {
        assertEquals("string:v", jsonb.fromJson("{\"value\":\"v\"}", Overloaded.class).getValue());
    }

    @Test
    void a_throwing_setter_is_a_jsonb_exception() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"name\":\"x\"}", ThrowingSetter.class));
    }

    @Test
    void hidden_and_transient_setters_are_not_used_on_read() {
        var out = jsonb.fromJson("{\"a\":\"A\",\"b\":\"B\",\"c\":\"C\"}", HiddenSetters.class);
        assertEquals("a", out.getA());
        assertEquals("b", out.getB());
        assertEquals("c", out.getC());
    }

    @Test
    void polymorphic_bean_creator_and_record_subtypes_round_trip() {
        var dog = new Dog();
        dog.setName("Rex");
        String dogJson = jsonb.toJson(dog, (Type) Pet.class);
        assertEquals("{\"@kind\":\"dog\",\"name\":\"Rex\"}", dogJson);
        assertEquals("Rex", assertInstanceOf(Dog.class, jsonb.fromJson(dogJson, Pet.class)).getName());

        var cat = new Cat("Tom");
        cat.lives = 9;
        String catJson = jsonb.toJson(cat, (Type) Pet.class);
        assertEquals("{\"@kind\":\"cat\",\"lives\":9,\"name\":\"Tom\"}", catJson);
        var catBack = assertInstanceOf(Cat.class, jsonb.fromJson(catJson, Pet.class));
        assertEquals("Tom", catBack.getName());
        assertEquals(9, catBack.lives);

        String fishJson = jsonb.toJson(new Fish("Nemo", "s"), (Type) Pet.class);
        assertEquals("{\"@kind\":\"fish\",\"name\":\"Nemo\"}", fishJson);
        assertEquals(new Fish("Nemo", null),
                jsonb.fromJson("{\"@kind\":\"fish\",\"name\":\"Nemo\",\"secret\":\"s\",\"other\":[1]}", Pet.class));
    }

    @Test
    void polymorphic_null_values() {
        assertNull(jsonb.fromJson("null", Pet.class));
        assertEquals("null", jsonb.toJson(null, (Type) Pet.class));
    }

    @Test
    void polymorphic_read_errors() {
        assertThrows(JsonbException.class, () -> jsonb.fromJson("[]", Pet.class));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{}", Pet.class));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"name\":\"Rex\",\"@kind\":\"dog\"}", Pet.class));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"@kind\":1}", Pet.class));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"@kind\":\"cow\"}", Pet.class));
    }

    @Test
    void a_discriminator_that_collides_with_a_property_is_rejected() {
        var clash = new Clash();
        assertThrows(JsonbException.class, () -> jsonb.toJson(clash));
        assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"name\":\"x\"}", Clash.class));
    }
}
