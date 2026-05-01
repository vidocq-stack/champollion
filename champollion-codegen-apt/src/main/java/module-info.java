import javax.annotation.processing.Processor;

/**
 * Annotation Processor Champollion : génère un {@code <FQN>$$Binding} pour
 * chaque type annoté {@code @JsonbStatic}.
 */
module io.vidocq.champollion.codegen.apt {
    requires java.compiler;
    requires io.vidocq.champollion.jsonb;

    exports io.vidocq.champollion.codegen.apt;

    provides Processor with io.vidocq.champollion.codegen.apt.JsonbStaticProcessor;
}
