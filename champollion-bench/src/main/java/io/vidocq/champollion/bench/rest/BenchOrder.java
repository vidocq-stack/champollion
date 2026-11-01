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

import jakarta.json.bind.annotation.JsonbDateFormat;
import jakarta.json.bind.annotation.JsonbNumberFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A plain DTO a REST resource returns: a superclass, a formatted date and amount, an array, a list of records. */
public class BenchOrder extends BenchEntity {
    @JsonbDateFormat("dd/MM/yyyy")
    private LocalDate placed;
    private BigDecimal total;
    private String[] tags;
    private List<BenchLine> lines;

    public LocalDate getPlaced() { return placed; }
    public void setPlaced(LocalDate placed) { this.placed = placed; }
    @JsonbNumberFormat("#,##0.00")
    public BigDecimal getTotal() { return total; }
    @JsonbNumberFormat("#,##0.00")
    public void setTotal(BigDecimal total) { this.total = total; }
    public String[] getTags() { return tags; }
    public void setTags(String[] tags) { this.tags = tags; }
    public List<BenchLine> getLines() { return lines; }
    public void setLines(List<BenchLine> lines) { this.lines = lines; }

    /** An order of {@code lines} lines. */
    public static BenchOrder sample(int lines) {
        var order = new BenchOrder();
        order.setId(42L);
        order.setCreatedBy("bench");
        order.setPlaced(LocalDate.of(2026, 11, 1));
        order.setTags(new String[] {"priority", "gift"});
        List<BenchLine> all = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < lines; i++) {
            var line = new BenchLine("SKU-" + i, i % 5 + 1, BigDecimal.valueOf(1999 + i, 2));
            all.add(line);
            total = total.add(line.price().multiply(BigDecimal.valueOf(line.quantity())));
        }
        order.setLines(all);
        order.setTotal(total);
        return order;
    }
}
