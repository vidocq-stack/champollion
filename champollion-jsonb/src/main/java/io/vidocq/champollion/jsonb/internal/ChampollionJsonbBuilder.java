package io.vidocq.champollion.jsonb.internal;

import io.vidocq.champollion.jsonb.spi.JsonbBinding;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.spi.JsonProvider;

import java.util.List;

/**
 * Champollion {@link JsonbBuilder}. For now the configuration and underlying
 * JSON-P provider are fixed; M4.1 is the MVP. {@link JsonbConfig} support will
 * be expanded later.
 */
public final class ChampollionJsonbBuilder implements JsonbBuilder {

    private JsonbConfig config = new JsonbConfig();
    private JsonProvider jsonProvider;
    private List<? extends JsonbBinding<?>> staticBindings;

    @Override public JsonbBuilder withConfig(JsonbConfig config) {
        this.config = config;
        return this;
    }

    @Override public JsonbBuilder withProvider(JsonProvider jsonProvider) {
        this.jsonProvider = jsonProvider;
        return this;
    }

    /**
     * Preloads static {@link JsonbBinding}s (in addition to or instead of
     * {@link java.util.ServiceLoader} discovery). Useful for tests and for
     * manually written bindings that do not go through the APT.
     */
    public ChampollionJsonbBuilder withStaticBindings(List<? extends JsonbBinding<?>> bindings) {
        this.staticBindings = bindings;
        return this;
    }

    @Override public Jsonb build() {
        return buildChampollion();
    }

    /**
     * Typed {@link ChampollionJsonb} variant to expose internal methods in tests
     * without casts.
     */
    ChampollionJsonb buildChampollion() {
        JsonProvider provider = jsonProvider != null ? jsonProvider : JsonProvider.provider();
        StaticBindings sb = staticBindings != null
                ? StaticBindings.of(staticBindings)
                : StaticBindings.fromServiceLoader(ChampollionJsonbBuilder.class.getClassLoader());
        return new ChampollionJsonb(config, provider, sb);
    }
}
