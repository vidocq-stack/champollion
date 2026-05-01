package io.vidocq.champollion.jsonb.internal;

import io.vidocq.champollion.jsonb.spi.JsonbBinding;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.spi.JsonProvider;

import java.util.List;

/**
 * {@link JsonbBuilder} Champollion. Pour l'instant la configuration et le
 * provider JSON-P sous-jacent sont fixes ; M4.1 = MVP. L'enrichissement de
 * {@link JsonbConfig} suivra.
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
     * Pré-charge des {@link JsonbBinding} statiques (en plus / à la place de la
     * découverte par {@link java.util.ServiceLoader}). Utile pour les tests et
     * pour les bindings écrits à la main qui ne passent pas par l'APT.
     */
    public ChampollionJsonbBuilder withStaticBindings(List<? extends JsonbBinding<?>> bindings) {
        this.staticBindings = bindings;
        return this;
    }

    @Override public Jsonb build() {
        return buildChampollion();
    }

    /**
     * Variante typée {@link ChampollionJsonb} pour exposer les méthodes internes
     * dans les tests sans cast.
     */
    ChampollionJsonb buildChampollion() {
        JsonProvider provider = jsonProvider != null ? jsonProvider : JsonProvider.provider();
        StaticBindings sb = staticBindings != null
                ? StaticBindings.of(staticBindings)
                : StaticBindings.fromServiceLoader(ChampollionJsonbBuilder.class.getClassLoader());
        return new ChampollionJsonb(config, provider, sb);
    }
}
