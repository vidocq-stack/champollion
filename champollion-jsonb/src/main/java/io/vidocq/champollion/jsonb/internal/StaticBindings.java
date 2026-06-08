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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Immutable index of runtime-available static {@link JsonbBinding}s, keyed by
 * {@link Class}. One-shot construction from an explicit list or via
 * {@link ServiceLoader}.
 *
 * <p>Collision strategy: <em>first registered wins</em> (deterministic source
 * order). Consistent with JPMS {@code provides ... with} semantics.</p>
 */
final class StaticBindings {

    static final StaticBindings EMPTY = new StaticBindings(Map.of());

    private final Map<Class<?>, JsonbBinding<?>> byType;

    private StaticBindings(Map<Class<?>, JsonbBinding<?>> byType) {
        this.byType = Map.copyOf(byType);
    }

    static StaticBindings of(List<? extends JsonbBinding<?>> bindings) {
        if (bindings == null || bindings.isEmpty()) return EMPTY;
        var idx = new LinkedHashMap<Class<?>, JsonbBinding<?>>();
        for (JsonbBinding<?> b : bindings) {
            idx.putIfAbsent(b.type(), b);
        }
        return new StaticBindings(idx);
    }

    static StaticBindings fromServiceLoader(ClassLoader loader) {
        var found = new java.util.ArrayList<JsonbBinding<?>>();
        for (JsonbBinding<?> b : ServiceLoader.load(JsonbBinding.class, loader)) {
            found.add(b);
        }
        return of(found);
    }

    @SuppressWarnings("unchecked")
    <T> JsonbBinding<T> get(Class<T> type) {
        return (JsonbBinding<T>) byType.get(type);
    }

    Map<Class<?>, JsonbBinding<?>> view() { return byType; }

    boolean isEmpty() { return byType.isEmpty(); }
}
