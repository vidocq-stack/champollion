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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A per-class cache whose value for a class a function computes, once, on its first {@link #get}: the registries
 * resolve their writers and readers with their own methods, which stay theirs instead of moving into an anonymous
 * subclass.
 *
 * <p>Where the value lives depends on the class, so that neither a long-lived class nor a discarded one keeps the
 * owner of the cache alive (BUG-20261102-02):</p>
 * <ul>
 *   <li>A class of a loader that lives as long as the JVM (the bootstrap, platform and application loaders: the JDK's
 *       collections, the classes of the class path) keeps its value in a map the cache owns. A
 *       {@link ClassValue} would store it in the class itself, for good, and the value reaches the registry that owns
 *       the cache, which reaches the {@link ClassValue} that is the key of the entry: a weak key held by its own
 *       value, so that the registry, the bindings it holds and the application layer they come from would never be
 *       collected after a hot reload.</li>
 *   <li>The class of any other loader (an application layer) keeps its value in the class, as a {@link ClassValue}
 *       does: the value lives and dies with the class, so a cache that outlives the layer does not hold it back.</li>
 * </ul>
 *
 * @param <T> the type of the values
 */
final class ComputedClassValue<T> extends ClassValue<T> {

    private static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();
    private static final ClassLoader APPLICATION = ClassLoader.getSystemClassLoader();

    private final Function<Class<?>, T> compute;
    /** The values of the classes that never die, owned by the cache and so collected with it. */
    private final Map<Class<?>, T> permanent = new ConcurrentHashMap<>();

    /** A cache whose value for a class is {@code compute} applied to it. */
    ComputedClassValue(Function<Class<?>, T> compute) {
        this.compute = compute;
    }

    @Override
    public T get(Class<?> type) {
        if (!lastsAsLongAsTheJvm(type)) {
            return super.get(type);
        }
        T value = permanent.get(type);
        if (value == null) {
            // No computeIfAbsent: computing a value resolves the values of the members, which re-enters this cache.
            value = compute.apply(type);
            T raced = permanent.putIfAbsent(type, value);
            if (raced != null) value = raced;
        }
        return value;
    }

    @Override
    protected T computeValue(Class<?> type) {
        return compute.apply(type);
    }

    /** Whether the loader of {@code type} is one that is never discarded. */
    private static boolean lastsAsLongAsTheJvm(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader == PLATFORM || loader == APPLICATION;
    }
}
