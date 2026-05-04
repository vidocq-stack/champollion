import jakarta.json.spi.JsonProvider;

/**
 * Implémentation Jakarta JSON Processing 2.1.
 */
module io.vidocq.champollion.jsonp {
    requires transitive io.vidocq.champollion.api;

    // P9 — exporte le parser interne uniquement à champollion-jsonb pour le
    // pool thread-local de parsers (évite la ré-allocation de tokenizer +
    // buffers à chaque fromJson).
    exports io.vidocq.champollion.jsonp.internal to io.vidocq.champollion.jsonb;

    provides JsonProvider with io.vidocq.champollion.jsonp.internal.ChampollionJsonProvider;
}
