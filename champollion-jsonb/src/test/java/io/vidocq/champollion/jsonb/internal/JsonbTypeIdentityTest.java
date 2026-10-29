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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A parameterized type is read by the reader of that very type: {@code List<Item>} with an {@code Item} from another
 * class loader is another type, though it has the same name (BUG-20261029-08).
 */
@DisplayName("JSON-B — parameterized types told apart by identity, not by name")
class JsonbTypeIdentityTest {

    private static Jsonb jsonb;

    @BeforeAll
    static void open() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    public static class Item {
        public String name;
    }

    /** Defines its own copy of {@link Item}, so that two classes share its name. */
    private static final class CopyingLoader extends ClassLoader {
        CopyingLoader() {
            super(JsonbTypeIdentityTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(Item.class.getName())) return super.loadClass(name, resolve);
            // One test thread loads it once: no class-loading lock needed.
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** {@code List<element>}, equal to the JDK's own {@code List<element>}. */
    private record ListOf(Class<?> element) implements ParameterizedType {
        @Override
        public Type[] getActualTypeArguments() {
            return new Type[] {element};
        }

        @Override
        public Type getRawType() {
            return List.class;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType p && p.getRawType() == List.class && p.getOwnerType() == null
                    && Arrays.equals(p.getActualTypeArguments(), getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(getActualTypeArguments()) ^ List.class.hashCode();
        }

        @Override
        public String getTypeName() {
            return "java.util.List<" + element.getTypeName() + ">";
        }
    }

    @Test
    void a_list_of_a_same_named_class_from_another_loader_holds_that_class() throws Exception {
        Class<?> copy = new CopyingLoader().loadClass(Item.class.getName());
        assertNotSame(Item.class, copy, "the loader defines a class of its own");

        List<?> mine = jsonb.fromJson("[{\"name\":\"a\"}]", new ListOf(Item.class));
        List<?> theirs = jsonb.fromJson("[{\"name\":\"b\"}]", new ListOf(copy));

        assertSame(Item.class, mine.getFirst().getClass());
        assertSame(copy, theirs.getFirst().getClass(), "read with the reader of the copy's list");
        assertEquals("b", copy.getField("name").get(theirs.getFirst()));
    }
}
