/**
 * API Champollion : re-exposition contrôlée des specs Jakarta JSON-P 2.1
 * et Jakarta JSON-B 3.0. Les SPI internes Champollion seront ajoutées au fur
 * et à mesure de leur implémentation.
 */
module io.vidocq.champollion.api {
    requires transitive jakarta.json;
    requires transitive jakarta.json.bind;
    requires static jakarta.annotation;

    exports io.vidocq.champollion.spi;
}
