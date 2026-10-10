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
package io.vidocq.champollion.it.weld;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.spi.JsonProvider;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Champollion jars, unchanged, with Weld SE on a class path (vidocq-workspace#15,
 * champollion#19): the JSON-P and JSON-B providers are found through {@code META-INF/services}, and
 * a JSON-B adapter is a CDI bean, injected (JSON-B 3.0 §5).
 */
class WeldPortabilityTest {

    private static WeldContainer container;

    @BeforeAll
    static void start() {
        container = new Weld().initialize();
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.close();
        }
    }

    @Test
    void vaubanIsNotOnTheClassPath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.vidocq.vauban.core.container.VaubanContainer"));
    }

    @Test
    void providersAreChampollion() throws Exception {
        assertTrue(JsonProvider.provider().getClass().getName().startsWith("io.vidocq.champollion."),
                JsonProvider.provider().getClass().getName());
        try (Jsonb jsonb = JsonbBuilder.create()) {
            assertTrue(jsonb.getClass().getName().startsWith("io.vidocq.champollion."), jsonb.getClass().getName());
        }
    }

    @Test
    void adapterIsACdiBeanWithInjection() throws Exception {
        Order order = new Order();
        order.customer = "alice";
        order.quantity = 3;
        try (Jsonb jsonb = JsonbBuilder.create()) {
            String json = jsonb.toJson(order);
            assertTrue(json.contains("\"customer\":\"<ALICE>\""), json);

            Order back = jsonb.fromJson(json, Order.class);
            assertEquals("alice", back.customer);
            assertEquals(3, back.quantity);
        }
    }
}
