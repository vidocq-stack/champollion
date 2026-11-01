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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The adapters, serializers and deserializers of one container, for Champollion's generated bindings and runtime:
 * installed when the container starts ({@link JsonbInstances#install}), for the application's class loader; each
 * instance obtained once from the container, through its own {@link Instance}, and destroyed with its dependents when
 * the container stops — a {@code @Dependent} one included.
 */
@ApplicationScoped
public class JsonbCdiRegistry implements JsonbInstances.Source {

    private final Map<Class<?>, Instance.Handle<?>> handles = new ConcurrentHashMap<>();
    private Instance<Object> beans;
    private ClassLoader application;

    /** For the container's proxy. */
    protected JsonbCdiRegistry() {
    }

    @Inject
    JsonbCdiRegistry(Instance<Object> beans) {
        this.beans = beans;
    }

    /** Serves the generated bindings and the runtime of the application starting. */
    void install(@Observes Startup event) {
        application = Thread.currentThread().getContextClassLoader();
        if (application == null) application = JsonbCdiRegistry.class.getClassLoader();
        JsonbInstances.install(application, this);
    }

    /** Stops serving, and destroys the instances obtained. */
    void uninstall(@Observes Shutdown event) {
        if (application != null) JsonbInstances.uninstall(application, this);
        handles.values().forEach(Instance.Handle::destroy);
        handles.clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        Instance.Handle<?> handle = handles.get(type);
        if (handle == null) {
            Instance<T> candidates = beans.select(type);
            // Not a bean here (the class is created with new), or ambiguous (the build reported it).
            if (candidates.isUnsatisfied() || candidates.isAmbiguous()) return null;
            handle = handles.computeIfAbsent(type, t -> candidates.getHandle());
        }
        return (T) handle.get();
    }
}
