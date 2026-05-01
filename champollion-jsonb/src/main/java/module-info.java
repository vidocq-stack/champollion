import io.vidocq.champollion.jsonb.spi.JsonbBinding;
import jakarta.json.bind.spi.JsonbProvider;

/**
 * Implémentation Jakarta JSON Binding 3.0 — runtime reflectif (M4) avec
 * lookup-first vers les bindings statiques générés par
 * {@code champollion-codegen-apt} (M5) puis fallback runtime.
 */
module io.vidocq.champollion.jsonb {
    requires transitive io.vidocq.champollion.api;
    requires io.vidocq.champollion.jsonp;

    exports io.vidocq.champollion.jsonb.spi;

    uses JsonbBinding;

    provides JsonbProvider with io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider;
}
