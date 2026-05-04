package io.vidocq.champollion.bench;

import io.vidocq.champollion.jsonb.spi.JsonbStatic;

@JsonbStatic
public record SmallStaticRecord(long id, String name, boolean active) {
    public static SmallStaticRecord sample() {
        return new SmallStaticRecord(42L, "Champollion", true);
    }
}
