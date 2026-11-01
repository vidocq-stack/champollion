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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On the module path, Vauban instantiates the registry bean of this jar reflectively when no generated provider
 * ships with it (an application whose tests or launch skip {@code vidocq:generate}): the package must be open to
 * Vauban, and to it only.
 */
class JsonbCdiModuleTest {

    private static ModuleDescriptor descriptor() throws IOException, URISyntaxException {
        // The tests run on the class path: read this module's own descriptor, not the first one the loader finds.
        Path classes = Path.of(JsonbCdiRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (InputStream in = Files.newInputStream(classes.resolve("module-info.class"))) {
            return ModuleDescriptor.read(in);
        }
    }

    @Test
    void registryPackageIsOpenToVaubanOnly() throws IOException, URISyntaxException {
        ModuleDescriptor.Opens opens = descriptor().opens().stream()
                .filter(o -> o.source().equals(JsonbCdiRegistry.class.getPackageName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the registry package is not open"));
        assertTrue(opens.isQualified(), "open to the container, not to everyone");
        assertEquals(Set.of("io.vidocq.vauban.core"), opens.targets());
    }
}
