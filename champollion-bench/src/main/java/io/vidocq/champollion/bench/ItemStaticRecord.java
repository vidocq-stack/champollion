package io.vidocq.champollion.bench;

import io.vidocq.champollion.jsonb.spi.JsonbStatic;

@JsonbStatic
public record ItemStaticRecord(String sku, int quantity, double unitPrice) {}
