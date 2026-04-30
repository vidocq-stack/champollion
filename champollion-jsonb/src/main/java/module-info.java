import jakarta.json.bind.spi.JsonbProvider;

module io.vidocq.champollion.jsonb {
    requires transitive io.vidocq.champollion.api;
    requires io.vidocq.champollion.jsonp;

    exports io.vidocq.champollion.jsonb;
    exports io.vidocq.champollion.jsonb.spi;

    uses io.vidocq.champollion.jsonb.spi.BindingFactoryProvider;
    provides JsonbProvider with io.vidocq.champollion.jsonb.internal.ChampollionJsonbProvider;
}
