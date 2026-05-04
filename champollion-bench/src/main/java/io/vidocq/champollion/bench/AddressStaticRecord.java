package io.vidocq.champollion.bench;

import io.vidocq.champollion.jsonb.spi.JsonbStatic;

@JsonbStatic
public record AddressStaticRecord(String street, String city, String zip, String country) {}
