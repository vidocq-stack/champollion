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
package io.vidocq.champollion.jsonb.spi;

import io.vidocq.champollion.jsonb.internal.LeafCodecs;
import jakarta.json.bind.JsonbException;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The adapters, serializers and deserializers a generated {@link JsonbBinding} calls, and those the runtime calls.
 *
 * <p>A CDI container that runs Champollion's build compatible extension ({@code champollion-jsonb-cdi}) installs a
 * {@link Source} for the class loader of its application: the instances then come from that container, one per
 * container, destroyed when it stops. Without one (no CDI, a container that runs no build compatible extension), the
 * runtime's standard behaviour applies: the CDI bean through {@code CDI.current()} when the class looks like one,
 * else an instance created with its no-argument constructor.</p>
 */
public final class JsonbInstances {

    /** The instances of one CDI container. */
    public interface Source {

        /** The container's instance of {@code type}, or {@code null} when it has no bean of that type. */
        <T> T get(Class<T> type);
    }

    private static final Map<ClassLoader, Source> SOURCES = new WeakHashMap<>();

    private JsonbInstances() {
    }

    /** Installs the source of the container whose application classes {@code loader} loads. */
    public static void install(ClassLoader loader, Source source) {
        synchronized (SOURCES) {
            SOURCES.put(loader, source);
        }
    }

    /** Removes {@code source}, when it is still the one installed for {@code loader}. */
    public static void uninstall(ClassLoader loader, Source source) {
        synchronized (SOURCES) {
            SOURCES.remove(loader, source);
        }
    }

    /**
     * The source of the container that serves {@code type}: the one installed for its class loader or a parent of
     * it, else for the thread's context class loader or a parent of it, else the only one installed; {@code null}
     * when none is.
     */
    public static Source sourceFor(Class<?> type) {
        synchronized (SOURCES) {
            if (SOURCES.isEmpty()) return null;
            Source source = lookup(type.getClassLoader());
            if (source == null) source = lookup(Thread.currentThread().getContextClassLoader());
            // A container whose start-up loader is none of those (Vauban's composite loader, say) and the only one
            // running: it serves the application.
            if (source == null && SOURCES.size() == 1) source = SOURCES.values().iterator().next();
            return source;
        }
    }

    private static Source lookup(ClassLoader loader) {
        for (ClassLoader l = loader; l != null; l = l.getParent()) {
            Source source = SOURCES.get(l);
            if (source != null) return source;
        }
        return null;
    }

    /**
     * The instance of {@code type} for a generated binding: from the installed container, else as the runtime obtains
     * it, resolved on first use and kept.
     *
     * @param create creates it when no container provides it; {@code null} when the binding cannot call a
     *               constructor of {@code type} (a bean created by injection only)
     */
    public static <T> Supplier<T> of(Class<T> type, Supplier<T> create) {
        Supplier<T> fallback = new Fallback<>(type, create);
        return () -> {
            Source source = sourceFor(type);
            T bean = source == null ? null : source.get(type);
            return bean != null ? bean : fallback.get();
        };
    }

    /** The runtime's standard behaviour, kept once resolved. */
    private static final class Fallback<T> implements Supplier<T> {

        private final Class<T> type;
        private final Supplier<T> create;
        private final AtomicReference<T> instance = new AtomicReference<>();

        Fallback(Class<T> type, Supplier<T> create) {
            this.type = type;
            this.create = create;
        }

        @Override
        public T get() {
            T current = instance.get();
            if (current == null) {
                // Two first uses may both resolve: the first one kept wins.
                instance.compareAndSet(null, resolve());
                current = instance.get();
            }
            return current;
        }

        private T resolve() {
            T bean = LeafCodecs.bean(type);
            if (bean != null) return bean;
            if (create == null) {
                throw new JsonbException(type.getName() + " is not provided by the CDI container, and has no"
                        + " no-argument constructor to create it otherwise");
            }
            return create.get();
        }
    }
}
