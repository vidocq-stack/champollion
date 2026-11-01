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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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

        /** Whether the container has a bean of {@code type}, without creating it. */
        boolean serves(Class<?> type);
    }

    /** The installed sources, by the class loader of their application: several when containers share one. */
    private static final Map<ClassLoader, List<Source>> SOURCES = new WeakHashMap<>();
    /** Counts installs and uninstalls: an instance resolved under one generation is kept until it changes. */
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final AtomicInteger INSTALLED = new AtomicInteger();

    private JsonbInstances() {
    }

    /** Installs the source of the container whose application classes {@code loader} loads. */
    public static void install(ClassLoader loader, Source source) {
        synchronized (SOURCES) {
            SOURCES.computeIfAbsent(loader, l -> new ArrayList<>()).add(source);
            INSTALLED.incrementAndGet();
            GENERATION.incrementAndGet();
        }
    }

    /** Removes {@code source}, installed for {@code loader}. */
    public static void uninstall(ClassLoader loader, Source source) {
        synchronized (SOURCES) {
            List<Source> sources = SOURCES.get(loader);
            if (sources != null && sources.remove(source)) {
                INSTALLED.decrementAndGet();
                if (sources.isEmpty()) SOURCES.remove(loader);
                GENERATION.incrementAndGet();
            }
        }
    }

    /**
     * The source of the container that serves {@code type}: among those installed for its class loader or a parent
     * of it, else for the thread's context class loader or a parent of it, else among all, the one whose container
     * has a bean of {@code type} when there are several; {@code null} when none is installed or none has one.
     *
     * @throws JsonbException when several running containers have a bean of {@code type} and nothing tells which
     *                        one serves the caller
     */
    public static Source sourceFor(Class<?> type) {
        if (INSTALLED.get() == 0) return null;
        synchronized (SOURCES) {
            List<Source> candidates = lookup(type.getClassLoader());
            if (candidates.isEmpty()) candidates = lookup(Thread.currentThread().getContextClassLoader());
            if (candidates.isEmpty()) {
                candidates = new ArrayList<>();
                for (List<Source> sources : SOURCES.values()) candidates.addAll(sources);
            }
            if (candidates.size() == 1) return candidates.getFirst();
            List<Source> serving = candidates.stream().filter(s -> s.serves(type)).toList();
            if (serving.size() > 1) {
                throw new JsonbException(serving.size() + " running CDI containers have a bean of " + type.getName()
                        + " for the same application: JSON-B cannot tell which one serves it; give each"
                        + " application its own class loader, or stop one container");
            }
            return serving.isEmpty() ? null : serving.getFirst();
        }
    }

    /** The sources installed for {@code loader} or its closest ancestor that has some. */
    private static List<Source> lookup(ClassLoader loader) {
        for (ClassLoader l = loader; l != null; l = l.getParent()) {
            List<Source> sources = SOURCES.get(l);
            if (sources != null && !sources.isEmpty()) return List.copyOf(sources);
        }
        return List.of();
    }

    /**
     * The instance of {@code type} for a generated binding: from the installed container, else as the runtime obtains
     * it. Resolved once per container: kept until a container starts or stops.
     *
     * @param create creates it when no container provides it; {@code null} when the binding cannot call a
     *               constructor of {@code type} (a bean created by injection only)
     */
    public static <T> Supplier<T> of(Class<T> type, Supplier<T> create) {
        return new Resolved<>(type, new Fallback<>(type, create));
    }

    /**
     * As {@link #of}, with the runtime's own {@code fallback} for when no container provides the instance: for the
     * runtime's mapping, which keeps the CDI instances it obtains to destroy them.
     */
    public static <T> Supplier<T> cached(Class<T> type, Supplier<T> fallback) {
        return new Resolved<>(type, fallback);
    }

    /** The instance a binding calls, kept for the generation it was resolved under. */
    private static final class Resolved<T> implements Supplier<T> {

        private record Kept<T>(int generation, T instance) {}

        private final Class<T> type;
        private final Supplier<T> fallback;
        private final AtomicReference<Kept<T>> kept = new AtomicReference<>();

        Resolved(Class<T> type, Supplier<T> fallback) {
            this.type = type;
            this.fallback = fallback;
        }

        @Override
        public T get() {
            int generation = GENERATION.get();
            Kept<T> current = kept.get();
            if (current != null && current.generation() == generation) return current.instance();
            Source source = sourceFor(type);
            T bean = source == null ? null : source.get(type);
            T instance = bean != null ? bean : fallback.get();
            // Several containers sharing a loader answer by the caller's context: nothing to keep then.
            if (INSTALLED.get() <= 1) kept.set(new Kept<>(generation, instance));
            return instance;
        }
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
