/**
 * Implémentation Jakarta JSON Processing 2.1.
 * Le {@code provides JsonProvider} sera activé dès que {@code ChampollionJsonProvider}
 * sera implémenté (M1.4).
 */
module io.vidocq.champollion.jsonp {
    requires transitive io.vidocq.champollion.api;
}
