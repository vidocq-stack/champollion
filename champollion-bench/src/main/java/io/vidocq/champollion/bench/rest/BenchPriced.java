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
package io.vidocq.champollion.bench.rest;

import jakarta.json.bind.annotation.JsonbTypeAdapter;

import java.math.BigDecimal;
import java.util.List;

/** A record whose amounts go through an adapter, a REST resource exchanging it. */
public record BenchPriced(String sku, @JsonbTypeAdapter(CentsAdapter.class) BigDecimal price, List<Line> lines) {

    /** A line, its amount adapted too. */
    public record Line(int quantity, @JsonbTypeAdapter(CentsAdapter.class) BigDecimal amount) {}

    /** A value of {@code lines} lines. */
    public static BenchPriced sample(int lines) {
        var all = new java.util.ArrayList<Line>();
        for (int i = 0; i < lines; i++) all.add(new Line(i + 1, BigDecimal.valueOf(1999 + i, 2)));
        return new BenchPriced("SKU", new BigDecimal("12.34"), all);
    }
}
