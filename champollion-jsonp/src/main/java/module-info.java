import jakarta.json.spi.JsonProvider;

/**
 * Jakarta JSON Processing 2.1 implementation.
 */
module io.vidocq.champollion.jsonp {
    requires transitive io.vidocq.champollion.api;

    // P9 — exports the internal parser only to champollion-jsonb for the
    // parser pool (avoids reallocating the tokenizer + buffers on every fromJson).
    exports io.vidocq.champollion.jsonp.internal to io.vidocq.champollion.jsonb;

    provides JsonProvider with io.vidocq.champollion.jsonp.internal.ChampollionJsonProvider;
}
