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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The module is container-neutral: it depends on the CDI and JSON-B APIs only, reads no Vauban module and ships no
 * Vauban components (workspace AGENTS.md, "A brick depends on APIs; the Vidocq runtime extension assembles"). The
 * Vauban glue is {@code champollion-jsonb-cdi-vauban}, the only module the registry's package is exported to.
 */
class JsonbCdiModuleTest {

    private static final String REGISTRY_PACKAGE = "io.vidocq.champollion.jsonb.cdi.registry";
    private static final String VAUBAN_ADAPTER = "io.vidocq.champollion.jsonb.cdi.vauban";

    private static ModuleDescriptor descriptor() throws IOException, URISyntaxException {
        // The tests run on the class path: read this module's own descriptor, not the first one the loader finds.
        Path classes = Path.of(JsonbCdiExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (InputStream in = Files.newInputStream(classes.resolve("module-info.class"))) {
            return ModuleDescriptor.read(in);
        }
    }

    @Test
    void readsNoVaubanModule() throws IOException, URISyntaxException {
        List<String> vauban = descriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name)
                .filter(name -> name.startsWith("io.vidocq.vauban"))
                .toList();
        assertEquals(List.of(), vauban, "a brick depends on APIs; the Vauban glue is " + VAUBAN_ADAPTER);
    }

    @Test
    void providesNoVaubanComponents() throws IOException, URISyntaxException {
        List<String> services = descriptor().provides().stream()
                .map(ModuleDescriptor.Provides::service)
                .filter(service -> service.startsWith("io.vidocq.vauban"))
                .toList();
        assertEquals(List.of(), services);
    }

    @Test
    void vaubanIsNoDependency() {
        // Neither compile nor runtime: the class path of the tests is the module's own, Weld included.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.vidocq.vauban.api.VaubanComponentProvider"));
    }

    @Test
    void exportsTheRegistryToTheVaubanAdapterOnly() throws IOException, URISyntaxException {
        List<Set<String>> targets = descriptor().exports().stream()
                .filter(export -> export.source().equals(REGISTRY_PACKAGE))
                .map(ModuleDescriptor.Exports::targets)
                .toList();
        assertEquals(List.of(Set.of(VAUBAN_ADAPTER)), targets,
                "the adapter creates the registry and calls its observers; no other module reaches them");
    }

    @Test
    void opensNothing() throws IOException, URISyntaxException {
        assertEquals(Set.of(), descriptor().opens(), "Vauban creates the registry without deep reflection");
    }
}
