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

import java.util.function.Supplier;

/**
 * The adapters, serializers and deserializers a generated {@link JsonbBinding} calls, obtained as the runtime obtains
 * them: from the CDI container when the class is a bean there (a scope, {@code @Singleton}, an {@code @Inject}
 * member), so that its injections are done, else created with its no-argument constructor. Resolved on first use, then
 * kept, as the runtime keeps the instance it resolved for a property.
 */
public final class JsonbInstances {

    private JsonbInstances() {
    }

    /**
     * The instance of {@code type}, resolved on first use.
     *
     * @param create creates it when the container does not provide it; {@code null} when the binding cannot call a
     *               constructor of {@code type} (a bean created by injection only)
     */
    public static <T> Supplier<T> of(Class<T> type, Supplier<T> create) {
        return new Lazy<>(type, create);
    }

    private static final class Lazy<T> implements Supplier<T> {

        private final Class<T> type;
        private final Supplier<T> create;
        private volatile T instance;

        Lazy(Class<T> type, Supplier<T> create) {
            this.type = type;
            this.create = create;
        }

        @Override
        public T get() {
            T current = instance;
            if (current == null) {
                current = resolve();
                instance = current;
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
