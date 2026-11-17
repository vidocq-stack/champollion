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
import io.vidocq.vauban.api.VaubanComponentProvider;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-written components serve what Vauban asks of the registry, and nothing else; and they keep up with the
 * registry: every member the container calls on it is one they call. The registry's members are read here by
 * reflection, in a test, to hold the components to them.
 */
class JsonbCdiVaubanComponentsTest {

    private final JsonbCdiVaubanComponents components = new JsonbCdiVaubanComponents();

    @Test
    void createsTheRegistryWithTheArgumentsVaubanResolved() {
        Object created = components.create(JsonbCdiVaubanComponents.REGISTRY, new Object[] {null, null});
        assertInstanceOf(JsonbCdiRegistry.class, created);
    }

    @Test
    void ownsNothingButTheRegistry() {
        JsonbCdiRegistry registry = new JsonbCdiRegistry(null, null);
        assertNull(components.create(JsonbCdiVaubanComponents.REGISTRY), "no constructor without parameters");
        assertNull(components.create("com.example.Other", new Object[] {null, null}));
        assertNull(components.create(JsonbCdiVaubanComponents.REGISTRY, new Object[] {null}));
        assertNull(components.create(JsonbCdiVaubanComponents.REGISTRY, null));
        assertSame(VaubanComponentProvider.NOT_INVOKED, components.invoke(registry, "com.example.Other",
                JsonbCdiVaubanComponents.INSTALL, new Object[] {new Startup()}));
        assertSame(VaubanComponentProvider.NOT_INVOKED, components.invoke(registry,
                JsonbCdiVaubanComponents.REGISTRY, "serves(java.lang.Class)", new Object[] {Object.class}));
        assertSame(VaubanComponentProvider.NOT_INVOKED, components.invoke(new Object(),
                JsonbCdiVaubanComponents.REGISTRY, JsonbCdiVaubanComponents.INSTALL, new Object[] {new Startup()}));
        assertNull(components.createClientProxy("com.example.Other_ClientProxy", () -> registry));
    }

    @Test
    void callsTheObserversThatInstallAndUninstallTheRegistry() {
        JsonbCdiRegistry registry = new JsonbCdiRegistry(null, null);
        Object installed = components.invoke(registry, JsonbCdiVaubanComponents.REGISTRY,
                JsonbCdiVaubanComponents.INSTALL, new Object[] {new Startup()});
        try {
            assertNull(installed, "a void method");
            assertSame(registry, JsonbInstances.sourceFor(JsonbCdiVaubanComponentsTest.class));
        } finally {
            components.invoke(registry, JsonbCdiVaubanComponents.REGISTRY, JsonbCdiVaubanComponents.UNINSTALL,
                    new Object[] {new Shutdown()});
        }
        assertNull(JsonbInstances.sourceFor(JsonbCdiVaubanComponentsTest.class), "uninstalled");
        assertFalse(registry.serves(Object.class), "stopped: serves nothing more");
    }

    @Test
    void theProxyForwardsEachCallToTheRegistryOfTheContext() {
        Recording recording = new Recording();
        Object proxy = components.createClientProxy(JsonbCdiVaubanComponents.REGISTRY_PROXY, () -> recording);
        JsonbCdiRegistry registry = assertInstanceOf(JsonbCdiRegistry.class, proxy);

        registry.install(new Startup());
        assertTrue(registry.serves(String.class));
        assertEquals("served", registry.get(String.class));
        registry.uninstall(new Shutdown());
        assertEquals(recording.hashCode(), registry.hashCode());
        assertEquals(recording.toString(), registry.toString());
        assertTrue(registry.equals(recording), "the registry's own equals, on the registry");

        assertEquals(List.of("install", "serves", "get", "uninstall"), recording.calls);
    }

    /** Vauban creates the registry through its {@code @Inject} constructor, with these arguments in this order. */
    @Test
    void theRegistryIsCreatedThroughTheConstructorTheComponentsCall() {
        List<List<Class<?>>> injected = Arrays.stream(JsonbCdiRegistry.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Inject.class))
                .map(Constructor::getParameterTypes)
                .map(List::of)
                .toList();
        assertEquals(List.of(List.of(Instance.class, BeanContainer.class)), injected);
    }

    /** Each observer method of the registry, by the key Vauban passes to {@code invoke}, is one the components call. */
    @Test
    void theComponentsCallEveryObserverOfTheRegistry() {
        Set<String> observers = new TreeSet<>();
        for (Method method : JsonbCdiRegistry.class.getDeclaredMethods()) {
            boolean observes = Arrays.stream(method.getParameters())
                    .anyMatch(parameter -> parameter.isAnnotationPresent(Observes.class));
            if (observes) observers.add(methodId(method));
        }
        assertEquals(new TreeSet<>(Set.of(JsonbCdiVaubanComponents.INSTALL, JsonbCdiVaubanComponents.UNINSTALL)),
                observers);
    }

    /** The proxy forwards every method of the registry a caller can reach: none runs on the proxy's own state. */
    @Test
    void theProxyOverridesEveryPublicMethodOfTheRegistry() {
        List<String> missing = new ArrayList<>();
        for (Method method : JsonbCdiRegistry.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || Modifier.isFinal(method.getModifiers())) continue;
            try {
                JsonbCdiRegistryClientProxy.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException _) {
                missing.add(methodId(method));
            }
        }
        assertEquals(List.of(), missing);
    }

    private static String methodId(Method method) {
        return method.getName() + Arrays.stream(method.getParameters())
                .map(Parameter::getType)
                .map(Class::getName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    /** A registry that records the calls the proxy forwards to it. */
    private static final class Recording extends JsonbCdiRegistry {

        final List<String> calls = new ArrayList<>();

        @Override
        public void install(Startup event) {
            calls.add("install");
        }

        @Override
        public void uninstall(Shutdown event) {
            calls.add("uninstall");
        }

        @Override
        public boolean serves(Class<?> type) {
            calls.add("serves");
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Class<T> type) {
            calls.add("get");
            return (T) "served";
        }
    }
}
