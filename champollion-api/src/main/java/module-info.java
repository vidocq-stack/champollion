module io.vidocq.champollion.api {
    requires transitive jakarta.json;
    requires transitive jakarta.json.bind;
    requires static jakarta.annotation;

    exports io.vidocq.champollion.spi;
}
