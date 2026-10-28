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

import java.util.function.Function;

/**
 * A {@link ClassValue} whose value for a class a function computes, once, on its first {@link #get}: the registries
 * resolve their writers and readers with their own methods, which stay theirs instead of moving into an anonymous
 * subclass.
 *
 * @param <T> the type of the values
 */
final class ComputedClassValue<T> extends ClassValue<T> {

    private final Function<Class<?>, T> compute;

    /** A cache whose value for a class is {@code compute} applied to it. */
    ComputedClassValue(Function<Class<?>, T> compute) {
        this.compute = compute;
    }

    @Override
    protected T computeValue(Class<?> type) {
        return compute.apply(type);
    }
}
