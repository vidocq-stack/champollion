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
package io.vidocq.champollion.jsonb.cdi;

import io.vidocq.champollion.jsonb.spi.JsonbInstances;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Two containers, one after the other, in one JVM: each serves its own instances to the generated bindings and the
 * runtime, and destroys them when it stops, a {@code @Dependent} one included.
 */
class JsonbCdiRegistryTest {

    @BeforeEach
    void reset() {
        DependentAdapter.DESTROYED.set(0);
    }

    private static WeldContainer start(String id) {
        return new Weld(id).initialize();
    }

    @Test
    void eachContainerServesAndDestroysItsOwnInstances() {
        Supplier<DependentAdapter> generated = JsonbInstances.of(DependentAdapter.class, DependentAdapter::new);
        DependentAdapter first;
        try (WeldContainer a = start("a")) {
            first = generated.get();
            assertEquals("<x>", first.adaptToJson("x"), "injected by the container");
            assertSame(first, generated.get(), "one instance per container");
        }
        assertEquals(1, DependentAdapter.DESTROYED.get(), "destroyed with its container");
        assertNull(JsonbInstances.sourceFor(DependentAdapter.class), "the stopped container serves no more");
        try (WeldContainer b = start("b")) {
            DependentAdapter second = generated.get();
            assertNotSame(first, second, "the second container's own instance");
            assertEquals("<y>", second.adaptToJson("y"));
        }
        assertEquals(2, DependentAdapter.DESTROYED.get());
    }

    @Test
    void theRuntimeUsesTheInstanceOfTheRunningContainer() throws Exception {
        Tagged tagged = new Tagged();
        tagged.name = "n";
        // One Jsonb across both containers, as a JVM-wide one is.
        try (Jsonb jsonb = JsonbBuilder.create()) {
            try (WeldContainer a = start("ra")) {
                assertEquals("{\"name\":\"<n>\"}", jsonb.toJson(tagged));
            }
            assertEquals(1, DependentAdapter.DESTROYED.get(), "the runtime's instance went with its container");
            try (WeldContainer b = start("rb")) {
                assertEquals("{\"name\":\"<n>\"}", jsonb.toJson(tagged));
                assertEquals("n", jsonb.fromJson("{\"name\":\"<n>\"}", Tagged.class).name);
            }
        }
        assertEquals(2, DependentAdapter.DESTROYED.get());
    }

    /**
     * Without the registry (a container that runs no build compatible extension), the runtime takes the bean from
     * {@code CDI.current()}, and destroys it when the {@code Jsonb} closes (BUG-20261101-12).
     */
    @Test
    void withoutTheRegistryTheJsonbDestroysWhatItObtained() throws Exception {
        Tagged tagged = new Tagged();
        tagged.name = "n";
        try (WeldContainer c = new Weld("plain").disableDiscovery()
                .addBeanClasses(DependentAdapter.class, Formatter.class).initialize()) {
            assertNull(JsonbInstances.sourceFor(DependentAdapter.class), "no registry in this container");
            Jsonb jsonb = JsonbBuilder.create();
            assertEquals("{\"name\":\"<n>\"}", jsonb.toJson(tagged));
            assertEquals(0, DependentAdapter.DESTROYED.get());
            jsonb.close();
            assertEquals(1, DependentAdapter.DESTROYED.get(), "destroyed with the Jsonb that obtained it");
        }
    }
}
