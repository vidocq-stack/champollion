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
import io.vidocq.vauban.api.GeneratedCoverage;
import io.vidocq.vauban.api.GeneratedCoverage.Generator;
import io.vidocq.vauban.api.VaubanComponentProvider;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanContainer;

import java.util.function.Supplier;

/**
 * What Vauban needs to run the registry of {@code champollion-jsonb-cdi}, as plain Java code: its creation, the calls
 * of its two observers and its client proxy. Vauban asks every provider it loads, so this one, in another module than
 * the registry, serves it; the registry's package is exported to this module alone and opened to none, so Vauban
 * reaches it through this class or not at all.
 *
 * <p>Written by hand, not by {@code vauban-processor}: the processor generates the components of the classes it
 * compiles, in their own package, and the registry is compiled in a module that knows no Vauban.
 * {@code JsonbCdiVaubanComponentsTest} fails when the registry gains a member the container calls and this class
 * does not.</p>
 *
 * <p>Under {@code vidocq:generate}, the enriched copy of {@code champollion-jsonb-cdi} carries components of its own for
 * the registry. Both answer the same, and Vauban asks its providers in turn and keeps the first answer: the registry
 * stays one bean (Vauban registers a bean class once, by name), its observers are called once each, and it gets one
 * client proxy. Vidocq's {@code TodoResourceTest} checks it on that path.</p>
 */
public final class JsonbCdiVaubanComponents implements VaubanComponentProvider {

    /** The registry's class, as Vauban names it. */
    static final String REGISTRY = JsonbCdiRegistry.class.getName();
    /** Its client proxy, as Vauban names it: the bean class followed by {@code _ClientProxy}. */
    static final String REGISTRY_PROXY = REGISTRY + "_ClientProxy";
    /** Its observers, as Vauban names them: the method and the erasure of its parameters. */
    static final String INSTALL = "install(" + Startup.class.getName() + ")";
    static final String UNINSTALL = "uninstall(" + Shutdown.class.getName() + ")";

    /** For the {@link java.util.ServiceLoader}. */
    public JsonbCdiVaubanComponents() {
        // Stateless.
    }

    @Override
    public Object create(String className) {
        // The registry has no constructor without parameters but the one of its proxy.
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object create(String className, Object[] args) {
        if (!REGISTRY.equals(className) || args == null || args.length != 2) return null;
        return new JsonbCdiRegistry((Instance<Object>) args[0], (BeanContainer) args[1]);
    }

    @Override
    public Object invoke(Object target, String className, String methodId, Object[] args) {
        if (!REGISTRY.equals(className) || !(target instanceof JsonbCdiRegistry registry)) return NOT_INVOKED;
        if (INSTALL.equals(methodId)) {
            registry.install((Startup) args[0]);
            return null;
        }
        if (UNINSTALL.equals(methodId)) {
            registry.uninstall((Shutdown) args[0]);
            return null;
        }
        return NOT_INVOKED;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object createClientProxy(String proxyClassName, Supplier<?> delegate) {
        if (!REGISTRY_PROXY.equals(proxyClassName)) return null;
        return new JsonbCdiRegistryClientProxy((Supplier<? extends JsonbCdiRegistry>) delegate);
    }

    /**
     * What this class runs, keyed as the container asks for it, so the dev console names it instead of reporting the
     * registry as run by reflection. {@link Generator#APT}: Java source compiled with the module, which is what the
     * processor would have written; the enum has no value for code written by hand.
     */
    @Override
    public GeneratedCoverage coverage() {
        return GeneratedCoverage.of(Generator.APT, new String[] {REGISTRY}, new String[0],
                new String[] {REGISTRY + "#" + INSTALL, REGISTRY + "#" + UNINSTALL}, new String[] {REGISTRY_PROXY});
    }
}
