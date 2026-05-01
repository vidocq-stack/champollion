import jakarta.json.spi.JsonProvider;

/**
 * Implémentation Jakarta JSON Processing 2.1.
 */
module io.vidocq.champollion.jsonp {
    requires transitive io.vidocq.champollion.api;

    provides JsonProvider with io.vidocq.champollion.jsonp.internal.ChampollionJsonProvider;
}
