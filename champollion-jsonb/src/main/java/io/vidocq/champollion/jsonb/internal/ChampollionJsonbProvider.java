package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.spi.JsonbProvider;

/**
 * Jakarta JSON Binding 3.0 provider — ServiceLoader entry point.
 */
public final class ChampollionJsonbProvider extends JsonbProvider {

    @Override public jakarta.json.bind.JsonbBuilder create() {
        return new ChampollionJsonbBuilder();
    }
}
