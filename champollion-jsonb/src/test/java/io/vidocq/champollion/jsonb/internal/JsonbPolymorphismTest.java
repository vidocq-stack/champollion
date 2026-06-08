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

import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.annotation.JsonbSubtype;
import jakarta.json.bind.annotation.JsonbTypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@DisplayName("M4.5 — JSON-B 3.0 polymorphism (@JsonbTypeInfo + @JsonbSubtype)")
class JsonbPolymorphismTest {

    @JsonbTypeInfo(key = "@type", value = {
            @JsonbSubtype(alias = "dog", type = Dog.class),
            @JsonbSubtype(alias = "cat", type = Cat.class)
    })
    public sealed interface Animal {}

    public record Dog(String name, String breed) implements Animal {}
    public record Cat(String name, int lives) implements Animal {}

    @Test
    void writes_subtype_with_discriminator() {
        try (var j = JsonbBuilder.create()) {
            String json = j.toJson(new Dog("Rex", "Labrador"), (java.lang.reflect.Type) Animal.class);
            assertEquals("{\"@type\":\"dog\",\"name\":\"Rex\",\"breed\":\"Labrador\"}", json);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void writes_other_subtype_with_correct_alias() {
        try (var j = JsonbBuilder.create()) {
            String json = j.toJson(new Cat("Whiskers", 9), (java.lang.reflect.Type) Animal.class);
            assertEquals("{\"@type\":\"cat\",\"name\":\"Whiskers\",\"lives\":9}", json);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void reads_subtype_via_discriminator() {
        try (var j = JsonbBuilder.create()) {
            Animal a = j.fromJson(
                    "{\"@type\":\"dog\",\"name\":\"Rex\",\"breed\":\"Labrador\"}",
                    Animal.class);
            assertInstanceOf(Dog.class, a);
            assertEquals(new Dog("Rex", "Labrador"), a);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void reads_other_subtype_correctly() {
        try (var j = JsonbBuilder.create()) {
            Animal a = j.fromJson(
                    "{\"@type\":\"cat\",\"name\":\"Whiskers\",\"lives\":9}",
                    Animal.class);
            assertInstanceOf(Cat.class, a);
            assertEquals(new Cat("Whiskers", 9), a);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void roundtrip_via_animal_interface() {
        try (var j = JsonbBuilder.create()) {
            Animal original = new Dog("Buddy", "Beagle");
            String json = j.toJson(original, (java.lang.reflect.Type) Animal.class);
            Animal back = j.fromJson(json, Animal.class);
            assertEquals(original, back);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
