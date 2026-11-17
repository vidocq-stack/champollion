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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.json.bind.adapter.JsonbAdapter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * An adapter whose class has a bounded type variable. It is a bean, necessarily {@code @Dependent} (CDI 4.1, managed
 * beans), and its raw class is not one of the types it can be looked up by (CDI 4.1 section 5.2.4). It injects a bean
 * and counts the instances the container made and destroyed. It declares a producer of a parameterized type: a
 * producer is a bean of the class that declares it too, and it is not the adapter.
 *
 * @param <T> unused by the adapter
 */
@Dependent
public class BoundedAdapter<T extends Number> implements JsonbAdapter<String, String> {

    static final AtomicInteger CREATED = new AtomicInteger();
    static final AtomicInteger DESTROYED = new AtomicInteger();

    @Inject
    Formatter formatter;

    @Produces
    Supplier<Formatter> formatterOfTheAdapter() {
        return () -> formatter;
    }

    @PostConstruct
    void created() {
        CREATED.incrementAndGet();
    }

    @PreDestroy
    void destroyed() {
        DESTROYED.incrementAndGet();
    }

    /** Decorated when the container made this instance, unchanged when its constructor alone did. */
    @Override
    public String adaptToJson(String value) {
        return formatter == null ? value : formatter.decorate(value);
    }

    @Override
    public String adaptFromJson(String json) {
        return formatter == null ? json : formatter.strip(json);
    }
}
