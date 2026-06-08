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
package io.vidocq.champollion.bench;

import io.vidocq.champollion.jsonb.spi.JsonbStatic;

import java.util.ArrayList;
import java.util.List;

@JsonbStatic
public record OrderStaticRecord(
        long id,
        String customer,
        List<ItemStaticRecord> items,
        AddressStaticRecord shipping,
        double total,
        boolean priority
) {
    public static OrderStaticRecord sample() {
        List<ItemStaticRecord> items = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            items.add(new ItemStaticRecord("SKU-" + i, i + 1, 10.0 + i * 1.5));
        }
        return new OrderStaticRecord(
                1001L,
                "Acme Corp",
                items,
                new AddressStaticRecord("12 Rue de Rivoli", "Paris", "75001", "FR"),
                87.50,
                true
        );
    }

    public static List<OrderStaticRecord> batch(int count) {
        List<OrderStaticRecord> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            OrderStaticRecord base = sample();
            out.add(new OrderStaticRecord(1000L + i, base.customer(), base.items(),
                    base.shipping(), base.total(), base.priority()));
        }
        return out;
    }
}
