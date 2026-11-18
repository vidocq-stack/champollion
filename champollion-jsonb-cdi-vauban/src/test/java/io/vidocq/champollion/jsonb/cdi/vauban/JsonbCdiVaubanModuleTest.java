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
package io.vidocq.champollion.jsonb.cdi.vauban;

import io.vidocq.champollion.jsonb.cdi.registry.JsonbCdiRegistry;
import io.vidocq.champollion.jsonb.spi.JsonbInstances;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.container.CodegenCoverage;
import io.vidocq.vauban.core.container.CodegenCoverage.Coverage;
import io.vidocq.vauban.core.container.CodegenCoverage.Verdict;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On the module path, Vauban runs the registry of {@code champollion-jsonb-cdi} through this module's components:
 * the registry's package is exported to this module alone and opened to none, so a reflective fallback of the
 * container, or a client proxy generated at start, could not reach it and the start would fail.
 */
class JsonbCdiVaubanModuleTest {

    private static final String REGISTRY_MODULE = "io.vidocq.champollion.jsonb.cdi";

    @Test
    void theTestsRunOnTheModulePath() {
        // Guards the proof below: on a class path, everything is open to the container.
        assertTrue(JsonbCdiVaubanComponents.class.getModule().isNamed(), "this module");
        assertTrue(JsonbCdiRegistry.class.getModule().isNamed(), REGISTRY_MODULE);
        assertEquals(REGISTRY_MODULE, JsonbCdiRegistry.class.getModule().getName());
    }

    @Test
    void theRegistryIsOpenToNoModuleAndExportedToThisOneAlone() {
        Module registry = JsonbCdiRegistry.class.getModule();
        String pkg = JsonbCdiRegistry.class.getPackageName();
        assertTrue(registry.isExported(pkg, JsonbCdiVaubanComponents.class.getModule()));
        assertFalse(registry.isExported(pkg), "exported to everyone");
        assertFalse(registry.isOpen(pkg, VaubanContainer.class.getModule()), "opened to Vauban");
        assertFalse(registry.isExported(pkg, VaubanContainer.class.getModule()), "exported to Vauban");
    }

    @Test
    void providesTheComponentsAndOpensNothing() {
        ModuleDescriptor descriptor = JsonbCdiVaubanComponents.class.getModule().getDescriptor();
        List<String> providers = descriptor.provides().stream()
                .filter(p -> p.service().equals("io.vidocq.vauban.api.VaubanComponentProvider"))
                .flatMap(p -> p.providers().stream())
                .toList();
        assertEquals(List.of(JsonbCdiVaubanComponents.class.getName()), providers);
        assertEquals(Set.of(), descriptor.opens());
        assertEquals(Set.of(), descriptor.exports(), "Vauban loads the components as a service");
    }

    /** On a class path, where a {@code provides} clause is ignored, the services file declares the same provider. */
    @Test
    void theServicesFileDeclaresTheComponentsForAClassPath() throws IOException {
        String file = "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider";
        try (InputStream in = JsonbCdiVaubanComponents.class.getModule().getResourceAsStream(file)) {
            assertNotNull(in, file);
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            assertEquals(List.of(JsonbCdiVaubanComponents.class.getName()), lines);
        }
    }

    /** CDI and the Vauban API, besides Champollion's JSON-B: an engine adapter, nothing else. */
    @Test
    void readsTheVaubanApiAndNoContainer() {
        Set<String> required = new TreeSet<>();
        JsonbCdiVaubanComponents.class.getModule().getDescriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name)
                .filter(name -> !name.equals("java.base"))
                .forEach(required::add);
        assertEquals(new TreeSet<>(Set.of("io.vidocq.champollion.jsonb", REGISTRY_MODULE, "io.vidocq.vauban.api",
                "jakarta.cdi")), required);
    }

    @Test
    void vaubanRunsTheRegistryWithoutReflection() {
        VaubanContainer container = VaubanContainer.builder().addBeanClass(JsonbCdiRegistry.class).build();
        try {
            JsonbInstances.Source source = JsonbInstances.sourceFor(JsonbCdiVaubanModuleTest.class);
            JsonbCdiRegistry installed = assertInstanceOf(JsonbCdiRegistry.class, source,
                    "created, and its Startup observer called, by the components");
            assertTrue(installed.serves(BeanContainer.class), "with the container's lookup injected");

            JsonbCdiRegistry reference = container.select(JsonbCdiRegistry.class);
            assertSame(JsonbCdiRegistryClientProxy.class, reference.getClass(),
                    "the client proxy of the components, none generated at start");
            assertTrue(reference.serves(BeanContainer.class));
        } finally {
            container.close();
        }
        assertNull(JsonbInstances.sourceFor(JsonbCdiVaubanModuleTest.class), "its Shutdown observer uninstalled it");
    }

    /**
     * The dev console names the code that runs the registry: the components declare what they cover, so the bean and
     * its two observers read as build-time code, not reflection nor an unknown provider.
     */
    @Test
    void theDevConsoleSeesTheRegistryRunByBuildTimeCode() {
        VaubanContainer container = VaubanContainer.builder().addBeanClass(JsonbCdiRegistry.class).build();
        try {
            CodegenCoverage coverage = container.codegenCoverage();
            Coverage generated = new Coverage(Verdict.APT, List.of());
            Set<Bean<?>> beans = container.getBeanManager().getBeans(JsonbCdiRegistry.class);
            assertEquals(1, beans.size(), beans.toString());
            Bean<?> bean = beans.iterator().next();
            assertEquals(generated, coverage.of(bean), "the bean: constructor and client proxy");
            List<ObserverDescriptor> observers = container.eventDispatcher().observers().stream()
                    // The class name through Object: this module does not read the one of the indexer's DotName.
                    .filter(observer -> String.valueOf(observer.declaringClass())
                            .equals(JsonbCdiVaubanComponents.REGISTRY))
                    .toList();
            assertEquals(2, observers.size(), observers.toString());
            for (ObserverDescriptor observer : observers) {
                assertEquals(generated, coverage.of(observer), observer.methodName());
            }
        } finally {
            container.close();
        }
    }
}
