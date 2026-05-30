/**
 * Champollion API: controlled re-exposure of the Jakarta JSON-P 2.1
 * and Jakarta JSON-B 3.0 specs. Champollion internal SPIs will be added as
 * their implementation progresses.
 */
module io.vidocq.champollion.api {
    requires transitive jakarta.json;
    requires transitive jakarta.json.bind;
    requires static jakarta.annotation;

    exports io.vidocq.champollion.spi;
}
