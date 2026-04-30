/**
 * Implémentation Jakarta JSON Binding 3.0. Les exports et le {@code provides JsonbProvider}
 * seront activés dès que les premières classes seront livrées (M4).
 */
module io.vidocq.champollion.jsonb {
    requires transitive io.vidocq.champollion.api;
    requires io.vidocq.champollion.jsonp;
}
