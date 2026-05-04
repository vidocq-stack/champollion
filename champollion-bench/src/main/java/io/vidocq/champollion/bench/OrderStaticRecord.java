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
