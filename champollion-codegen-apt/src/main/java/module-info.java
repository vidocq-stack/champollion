/**
 * Annotation Processor Champollion. Le {@code provides Processor} sera activé
 * dès que le {@code JsonbStaticProcessor} sera implémenté (M5).
 */
module io.vidocq.champollion.codegen.apt {
    requires java.compiler;
    requires io.vidocq.champollion.api;
}
