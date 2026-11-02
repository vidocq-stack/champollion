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
package io.vidocq.champollion.jsonb.internal;

import io.vidocq.champollion.jsonb.spi.JsonbBinding;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.WeakHashMap;

/**
 * Immutable index of runtime-available static {@link JsonbBinding}s, keyed by
 * {@link Class}. One-shot construction from an explicit list or via
 * {@link ServiceLoader}.
 *
 * <p>Collision strategy: <em>first registered wins</em> (deterministic source
 * order). Consistent with Java Modules {@code provides ... with} semantics.</p>
 *
 * <p>An index made from {@link ServiceLoader} also answers for a type its loaders did not know when it was made: a
 * {@code Jsonb} outlives the application layer it was built for ({@code vidocq dev} boots a fresh layer on every hot
 * reload, under the same library), and the entities of the new layer are new {@link Class} objects that the first
 * layer's index cannot hold. Such a type is looked up once, in the services files of its own class loader and of the
 * context class loader, and the answer is kept with the class itself, so that a discarded layer is collectable
 * (BUG-20261102-01).</p>
 */
final class StaticBindings {

    private static final System.Logger LOG = System.getLogger(StaticBindings.class.getName());

    static final StaticBindings EMPTY = new StaticBindings(Map.of(), List.of());

    /**
     * The index of each class loader that a type of a later layer asked for, weakly held: the bindings it holds
     * reach their types and so the loader, which must not stay reachable from here once nothing else holds it.
     */
    private static final Map<ClassLoader, WeakReference<StaticBindings>> LAYERS = new WeakHashMap<>();

    private final Map<Class<?>, JsonbBinding<?>> byType;
    /** The loaders this index was made from, whose services files it has read: a type of theirs is not looked up again. */
    private final List<WeakReference<ClassLoader>> read;
    /** What a type this index does not hold has in the loaders that came after it; {@code null} when it never looks. */
    private final ClassValue<Optional<JsonbBinding<?>>> later;

    private StaticBindings(Map<Class<?>, JsonbBinding<?>> byType, List<WeakReference<ClassLoader>> read) {
        this.byType = Map.copyOf(byType);
        this.read = read;
        this.later = read.isEmpty() ? null : new ComputedClassValue<>(this::lookLater);
    }

    static StaticBindings of(List<? extends JsonbBinding<?>> bindings) {
        return of(bindings, List.of());
    }

    private static StaticBindings of(List<? extends JsonbBinding<?>> bindings,
                                     List<WeakReference<ClassLoader>> read) {
        if (bindings == null || bindings.isEmpty()) return read.isEmpty() ? EMPTY : new StaticBindings(Map.of(), read);
        var idx = new LinkedHashMap<Class<?>, JsonbBinding<?>>();
        for (JsonbBinding<?> b : bindings) {
            idx.putIfAbsent(b.type(), b);
        }
        return new StaticBindings(idx, read);
    }

    /**
     * The bindings the services files of {@code loaders} list, the first loader's first. A loader given twice, or
     * {@code null}, counts once: a binding is created once whatever the number of paths that lead to it.
     *
     * <p>The library's own loader is not enough in a layered runtime: Champollion sits in a parent layer, and the
     * application's {@code META-INF/services/...JsonbBinding} in a child one that the parent cannot see.
     * Vidocq/vidocq#382.</p>
     */
    static StaticBindings fromServiceLoader(ClassLoader... loaders) {
        var found = new ArrayList<JsonbBinding<?>>();
        var seen = new ArrayList<ClassLoader>();
        for (ClassLoader loader : loaders) {
            if (loader == null || seen.contains(loader)) continue;
            seen.add(loader);
            scan(loader, found);
        }
        return of(found, seen.stream().map(WeakReference::new).toList());
    }

    /** Adds to {@code found} the bindings the services files of {@code loader} list. */
    private static void scan(ClassLoader loader, List<JsonbBinding<?>> found) {
        // Provider by provider: a stale entry (a binding class an incremental build left listed but deleted)
        // is reported and skipped, it does not take the other bindings down with it.
        var providers = ServiceLoader.load(JsonbBinding.class, loader).stream().iterator();
        while (true) {
            try {
                if (!providers.hasNext()) break;
                found.add(providers.next().get());
            } catch (java.util.ServiceConfigurationError e) {
                LOG.log(System.Logger.Level.WARNING, "Skipping a JSON-B binding listed in META-INF/services: "
                        + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    <T> JsonbBinding<T> get(Class<T> type) {
        JsonbBinding<?> known = byType.get(type);
        if (known != null || later == null || type.getClassLoader() == null) return (JsonbBinding<T>) known;
        return (JsonbBinding<T>) later.get(type).orElse(null);
    }

    /**
     * The binding of a type this index did not hold: the class loader of the type, then the context class loader,
     * unless this index was made from them. Computed once per class (the {@link ClassValue}), a type with none
     * included, as every call of {@code toJson} and {@code fromJson} asks.
     */
    private Optional<JsonbBinding<?>> lookLater(Class<?> type) {
        ClassLoader own = type.getClassLoader();
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        for (ClassLoader loader : context == null || context == own ? new ClassLoader[] {own}
                : new ClassLoader[] {own, context}) {
            if (wasRead(loader)) continue;
            JsonbBinding<?> binding = layer(loader).byType.get(type);
            if (binding != null) return Optional.of(binding);
        }
        return Optional.empty();
    }

    private boolean wasRead(ClassLoader loader) {
        for (WeakReference<ClassLoader> ref : read) {
            if (ref.get() == loader) return true;
        }
        return false;
    }

    /** The bindings the services files of {@code loader} list: read once while the answer is reachable. */
    private static StaticBindings layer(ClassLoader loader) {
        synchronized (LAYERS) {
            WeakReference<StaticBindings> known = LAYERS.get(loader);
            StaticBindings layer = known == null ? null : known.get();
            if (layer == null) {
                var found = new ArrayList<JsonbBinding<?>>();
                scan(loader, found);
                layer = of(found);
                LAYERS.put(loader, new WeakReference<>(layer));
            }
            return layer;
        }
    }

    Map<Class<?>, JsonbBinding<?>> view() { return byType; }

    boolean isEmpty() { return byType.isEmpty(); }
}
