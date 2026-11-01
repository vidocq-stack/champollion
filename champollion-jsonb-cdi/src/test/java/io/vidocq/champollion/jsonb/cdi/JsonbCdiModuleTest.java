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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * On the module path, Vauban creates the registry bean through the components the Vauban processor generated in this
 * module at its build, so the jar needs no {@code opens}, with or without {@code vidocq:generate}.
 */
class JsonbCdiModuleTest {

    private static final String PACKAGE = JsonbCdiRegistry.class.getPackageName();

    private static ModuleDescriptor descriptor() throws IOException, URISyntaxException {
        // The tests run on the class path: read this module's own descriptor, not the first one the loader finds.
        Path classes = Path.of(JsonbCdiRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (InputStream in = Files.newInputStream(classes.resolve("module-info.class"))) {
            return ModuleDescriptor.read(in);
        }
    }

    @Test
    void shipsItsVaubanComponents() throws IOException, URISyntaxException {
        List<String> providers = descriptor().provides().stream()
                .filter(p -> p.service().equals("io.vidocq.vauban.api.VaubanComponentProvider"))
                .flatMap(p -> p.providers().stream())
                .toList();
        assertEquals(List.of(PACKAGE + "._VaubanComponents"), providers);
    }

    @Test
    void opensNothing() throws IOException, URISyntaxException {
        assertEquals(Set.of(), descriptor().opens(), "Vauban creates the registry without deep reflection");
    }
}
