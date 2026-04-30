import jakarta.json.spi.JsonProvider;

module io.vidocq.champollion.jsonp {
    requires transitive io.vidocq.champollion.api;

    exports io.vidocq.champollion.jsonp;

    provides JsonProvider with io.vidocq.champollion.jsonp.internal.ChampollionJsonProvider;
}
