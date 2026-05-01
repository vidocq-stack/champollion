import jakarta.json.bind.spi.JsonbProvider;

/**
 * Implémentation Jakarta JSON Binding 3.0 — runtime reflectif (M4) avec
 * fallback automatique vers les factories statiques générées par
 * {@code champollion-codegen-apt} (M5).
 */
module io.vidocq.champollion.jsonb {
    requires transitive io.vidocq.champollion.api;
    requires io.vidocq.champollion.jsonp;

    provides JsonbProvider with io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider;
}
