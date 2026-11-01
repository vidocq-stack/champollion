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

import io.vidocq.champollion.jsonb.cdi.invalid.Holder;
import org.jboss.weld.environment.se.Weld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The extension fails the deployment on an adapter its container cannot provide properly. */
class JsonbCdiExtensionTest {

    @Test
    void anAdapterThatInjectsWithoutBeingABeanFailsTheDeployment() {
        Weld weld = new Weld("invalid").disableDiscovery().addBeanClasses(Holder.class, Formatter.class)
                .addBuildCompatibleExtensions(JsonbCdiExtension.class);
        RuntimeException e = assertThrows(RuntimeException.class, weld::initialize);
        String message = String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : e.getCause().getMessage());
        assertTrue(message.contains("Injecting") && message.contains("is no bean"), message);
    }

    @Test
    void anAmbiguousAdapterFailsTheDeployment() {
        Weld weld = new Weld("ambiguous").disableDiscovery()
                .addBeanClasses(io.vidocq.champollion.jsonb.cdi.invalid.SharedHolder.class,
                        io.vidocq.champollion.jsonb.cdi.invalid.Shared.class,
                        io.vidocq.champollion.jsonb.cdi.invalid.Shared.Special.class)
                .addBuildCompatibleExtensions(JsonbCdiExtension.class);
        RuntimeException e = assertThrows(RuntimeException.class, weld::initialize);
        String message = String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : e.getCause().getMessage());
        assertTrue(message.contains("Shared") && message.contains("ambiguous"), message);
    }
}
