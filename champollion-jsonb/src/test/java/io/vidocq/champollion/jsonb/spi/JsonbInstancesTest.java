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

import jakarta.json.bind.JsonbException;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sources of instances a container installs, and what a generated binding gets from them. */
class JsonbInstancesTest {

    /** A plain class, no bean. */
    public static class Plain {
    }

    /** A container's source that has a bean of {@code type}, or none. */
    private record Container(Class<?> served, Object instance) implements JsonbInstances.Source {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Class<T> type) {
            return type == served ? (T) instance : null;
        }

        @Override
        public boolean serves(Class<?> type) {
            return type == served;
        }
    }

    @Test
    void withoutASourceTheInstanceIsCreatedOnceAndKept() {
        Supplier<Plain> plain = JsonbInstances.of(Plain.class, Plain::new);
        Plain first = plain.get();
        assertSame(first, plain.get());
        assertNull(JsonbInstances.sourceFor(Plain.class));
    }

    @Test
    void aSourceInstalledForTheLoaderServesUntilItIsRemoved() {
        Plain bean = new Plain();
        ClassLoader loader = Plain.class.getClassLoader();
        Container container = new Container(Plain.class, bean);
        Supplier<Plain> plain = JsonbInstances.of(Plain.class, Plain::new);
        JsonbInstances.install(loader, container);
        try {
            assertSame(bean, plain.get());
            assertSame(bean, plain.get(), "kept for the generation");
        } finally {
            JsonbInstances.uninstall(loader, container);
        }
        assertNotSame(bean, plain.get(), "created once the container is gone");
    }

    @Test
    void aSourceOfAnotherLoaderServesWhenItIsTheOnlyOne() {
        ClassLoader other = new ClassLoader(null) { };
        Plain bean = new Plain();
        Container container = new Container(Plain.class, bean);
        JsonbInstances.install(other, container);
        try {
            assertSame(container, JsonbInstances.sourceFor(Plain.class));
        } finally {
            JsonbInstances.uninstall(other, container);
        }
    }

    @Test
    void twoSourcesWithABeanOfTheClassAreAnError() {
        ClassLoader loader = Plain.class.getClassLoader();
        Container a = new Container(Plain.class, new Plain());
        Container b = new Container(Plain.class, new Plain());
        Container unrelated = new Container(String.class, "x");
        JsonbInstances.install(loader, a);
        JsonbInstances.install(loader, b);
        JsonbInstances.install(loader, unrelated);
        try {
            JsonbException e = assertThrows(JsonbException.class, () -> JsonbInstances.sourceFor(Plain.class));
            assertTrue(e.getMessage().startsWith("2 running CDI containers"), e.getMessage());
            assertSame(unrelated, JsonbInstances.sourceFor(String.class), "the only one with a bean of String");
        } finally {
            JsonbInstances.uninstall(loader, a);
            JsonbInstances.uninstall(loader, b);
            JsonbInstances.uninstall(loader, unrelated);
        }
    }

    @Test
    void aBeanWithoutAConstructorAndWithoutAContainerIsAJsonbException() {
        Supplier<Plain> none = JsonbInstances.of(Plain.class, null);
        assertEquals("is not provided by the CDI container",
                assertThrows(JsonbException.class, none::get).getMessage().substring(Plain.class.getName().length() + 1,
                        Plain.class.getName().length() + 1 + "is not provided by the CDI container".length()));
    }
}
